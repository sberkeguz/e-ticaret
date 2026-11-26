using System.Text.Json;
using CargoService.Configuration;
using CargoService.Messaging.Contracts;
using CargoService.Shipping;
using Microsoft.Extensions.Options;
using RabbitMQ.Client;
using RabbitMQ.Client.Events;

namespace CargoService.Messaging;

/// <summary>
/// <c>cargo.shipment-requests</c> kuyruğunu dinler ve her komutu <see cref="ShipmentIntake"/>'e verir.
///  - İşlem commit edildikten SONRA ack: arada servis düşerse mesaj kuyruğa döner, tekrar gelince
///    processed_messages sayesinde ikinci kez işlenmez.
///  - Bozuk JSON ya da eksik/geçersiz alan → <c>BasicReject(requeue:false)</c>; kuyruğun dead-letter
///    ayarı mesajı <c>cargo.shipment-requests.dlq</c>'ya taşır.
///  - İşleme hatası <see cref="CommandFailures.Classify"/> ile sınıflandırılır:
///    · Geçici (bağlantı, zaman aşımı, 40001, 40P01 …) → 5 sn bekleyip <c>BasicNack(requeue:true)</c>.
///      Deneme sınırı YOKTUR: veritabanı kesintisinde mesaj DLQ'ya düşmez, veritabanı gelince işlenir.
///    · Kalıcı veri hatası (PostgresException SqlState sınıf 22/23) → hemen DLQ (her denemede aynı hata).
///    · Tanınmayan hata → 5 sn bekleyip kopya <see cref="CommandFailures.AttemptsHeader"/> başlığı bir
///      artırılarak kuyruğa yeniden yayınlanır (onay beklenir), sonra aslı ack'lenir;
///      <see cref="MaxUnknownAttempts"/>. denemede DLQ. Kuyruk klasik olduğundan (quorum delivery-count
///      yok) ve argümanları backend ile ortak olduğundan sayaç başlıkta taşınır.
/// DLQ'daki mesaj silinmez; sorun giderilince elle kuyruğa geri taşınabilir (kayıp yok).
/// Prefetch 4: aynı anda en fazla 4 mesaj bu kopyada bekler. Bağlantı koparsa 5 sn sonra yeniden
/// bağlanılır; onaylanmamış mesajlar RabbitMQ tarafından kuyruğa geri konur.
/// Loglara yalnızca messageId, sipariş no, geçersiz alan ADLARI ve istisna tipleri + SqlState yazılır;
/// gövde, alıcı bilgisi ve istisna mesajı (Detail/Where satır verisi taşıyabilir) yazılmaz.
/// </summary>
public sealed class ShipmentRequestsConsumer(
    IServiceScopeFactory scopes,
    RabbitConnectionProvider rabbit,
    IOptions<CargoOptions> options,
    ILogger<ShipmentRequestsConsumer> logger) : BackgroundService
{
    private const ushort Prefetch = 4;
    private static readonly TimeSpan RetryDelay = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan ReconnectDelay = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan ConfirmTimeout = TimeSpan.FromSeconds(10);

    /// <summary>Tanınmayan hatayla en fazla bu kadar deneme; bu denemede de işlenemezse DLQ.</summary>
    public const int MaxUnknownAttempts = 5;

    protected override Task ExecuteAsync(CancellationToken stoppingToken) =>
        options.Value.Workers.Enabled ? ConsumeAsync(stoppingToken) : Task.CompletedTask;

    /// <summary>
    /// <paramref name="ct"/> iptal edilene kadar kuyruğu dinler (Workers:Enabled'dan bağımsız; testler
    /// doğrudan çağırır). RabbitMQ ayarı yoksa hemen döner.
    /// </summary>
    public async Task ConsumeAsync(CancellationToken ct)
    {
        if (!rabbit.IsConfigured)
        {
            logger.LogWarning("RabbitMq:Url tanımlı değil: kargo komutları dinlenmeyecek");
            return;
        }

        while (!ct.IsCancellationRequested)
        {
            try
            {
                var connection = await rabbit.GetConnectionAsync(ct);
                // Yayın onayı (publisher confirm): sayaçlı kopya kuyruğa yazıldığı kesinleşmeden aslı ack'lenmez.
                await using var channel = await connection.CreateChannelAsync(
                    new CreateChannelOptions(publisherConfirmationsEnabled: true, publisherConfirmationTrackingEnabled: true), ct);
                await CargoTopology.DeclareAsync(channel, ct);
                await channel.BasicQosAsync(prefetchSize: 0, prefetchCount: Prefetch, global: false, ct);

                var closed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                channel.ChannelShutdownAsync += (_, _) =>
                {
                    closed.TrySetResult();
                    return Task.CompletedTask;
                };
                var consumer = new AsyncEventingBasicConsumer(channel);
                consumer.ReceivedAsync += (_, delivery) => HandleAsync(channel, delivery, ct);
                await channel.BasicConsumeAsync(CargoTopology.ShipmentRequestsQueue, autoAck: false, consumer, ct);
                logger.LogInformation("Kargo komutları dinleniyor ({Queue})", CargoTopology.ShipmentRequestsQueue);

                await closed.Task.WaitAsync(ct);
                logger.LogWarning("Kargo komut kanalı kapandı; {Seconds} sn sonra yeniden bağlanılacak", ReconnectDelay.TotalSeconds);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                logger.LogWarning("Kargo komut kuyruğuna bağlanılamadı ({Error}); {Seconds} sn sonra yeniden denenecek",
                    CommandFailures.Describe(ex), ReconnectDelay.TotalSeconds);
            }

            try
            {
                await Task.Delay(ReconnectDelay, ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private async Task HandleAsync(IChannel channel, BasicDeliverEventArgs delivery, CancellationToken ct)
    {
        var amqpMessageId = delivery.BasicProperties.MessageId;
        var command = Parse(delivery.Body, out var errors);
        if (command is null)
        {
            logger.LogWarning("Geçersiz kargo komutu DLQ'ya gönderildi (messageId {MessageId}; sorunlu alanlar: {Fields})",
                amqpMessageId, string.Join(", ", errors));
            await channel.BasicRejectAsync(delivery.DeliveryTag, requeue: false, CancellationToken.None);
            return;
        }

        try
        {
            await using var scope = scopes.CreateAsyncScope();
            var outcome = await scope.ServiceProvider.GetRequiredService<ShipmentIntake>().HandleAsync(command, ct);
            await channel.BasicAckAsync(delivery.DeliveryTag, multiple: false, CancellationToken.None);
            logger.LogInformation("Kargo komutu işlendi: sipariş {OrderId}, istek {RequestNo}, sonuç {Outcome}",
                command.OrderId, command.RequestNo, outcome);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            // Kapanıyoruz: ack edilmeyen mesajı RabbitMQ kanal kapanınca kuyruğa geri koyar.
        }
        catch (Exception ex)
        {
            await HandleFailureAsync(channel, delivery, command, ex, ct);
        }
    }

    /// <summary>
    /// İşlenemeyen komutu hata türüne göre yönlendirir (bkz. <see cref="CommandFailures.Classify"/>).
    /// Loga yalnızca sipariş no, messageId ve istisna tipleri + SqlState yazılır (istisna mesajı yazılmaz).
    /// </summary>
    private async Task HandleFailureAsync(
        IChannel channel, BasicDeliverEventArgs delivery, ShipmentRequested command, Exception ex, CancellationToken ct)
    {
        var kind = CommandFailures.Classify(ex);
        var error = CommandFailures.Describe(ex);
        var attempts = CommandFailures.ReadAttempts(delivery.BasicProperties.Headers) + 1;
        try
        {
            if (kind == CommandFailureKind.Permanent || (kind == CommandFailureKind.Unknown && attempts >= MaxUnknownAttempts))
            {
                logger.LogError(
                    "Kargo komutu işlenemedi, DLQ'ya gönderildi (sipariş {OrderId}, messageId {MessageId}, {Attempts}. deneme, {Kind}): {Error}",
                    command.OrderId, delivery.BasicProperties.MessageId, attempts, kind, error);
                await channel.BasicRejectAsync(delivery.DeliveryTag, requeue: false, CancellationToken.None);
                return;
            }

            logger.LogWarning(
                "Kargo komutu işlenemedi (sipariş {OrderId}, messageId {MessageId}, {Kind}), {Seconds} sn sonra tekrar denenecek: {Error}",
                command.OrderId, delivery.BasicProperties.MessageId, kind, RetryDelay.TotalSeconds, error);
            await Task.Delay(RetryDelay, ct);
            if (kind == CommandFailureKind.Transient)
            {
                // Sayılmaz: veritabanı kesintisi ne kadar sürerse sürsün mesaj DLQ'ya düşmez.
                await channel.BasicNackAsync(delivery.DeliveryTag, multiple: false, requeue: true, CancellationToken.None);
                return;
            }
            await RepublishWithAttemptsAsync(channel, delivery, attempts);
        }
        catch (Exception)
        {
            // Kapanış ya da kopan kanal: onaylanmamış mesaj zaten kuyruğa döner (kayıp yok).
        }
    }

    /// <summary>
    /// Kopyayı <see cref="CommandFailures.AttemptsHeader"/> = <paramref name="attempts"/> ile varsayılan
    /// exchange üzerinden aynı kuyruğa yayınlar, broker onayını (publisher confirm) bekler, SONRA aslını
    /// onaylar. Yayın başarısızsa aslı nack(requeue) edilir. Onaydan sonra ack'ten önce düşülürse ikisi de
    /// kuyrukta kalır; aynı messageId processed_messages sayesinde bir kez işlenir.
    /// </summary>
    private static async Task RepublishWithAttemptsAsync(IChannel channel, BasicDeliverEventArgs delivery, int attempts)
    {
        var props = new BasicProperties(delivery.BasicProperties)
        {
            Headers = new Dictionary<string, object?>(delivery.BasicProperties.Headers ?? new Dictionary<string, object?>())
            {
                [CommandFailures.AttemptsHeader] = attempts,
            },
        };
        try
        {
            using var timeout = new CancellationTokenSource(ConfirmTimeout);
            await channel.BasicPublishAsync("", CargoTopology.ShipmentRequestsQueue, mandatory: false, props,
                delivery.Body, timeout.Token);
        }
        catch (Exception)
        {
            await channel.BasicNackAsync(delivery.DeliveryTag, multiple: false, requeue: true, CancellationToken.None);
            return;
        }
        await channel.BasicAckAsync(delivery.DeliveryTag, multiple: false, CancellationToken.None);
    }

    /// <summary>Gövdeyi çözer ve doğrular; geçersizse null döner, <paramref name="errors"/> alan adlarını taşır.</summary>
    private static ShipmentRequested? Parse(ReadOnlyMemory<byte> body, out IReadOnlyList<string> errors)
    {
        ShipmentRequested? command;
        try
        {
            command = JsonSerializer.Deserialize<ShipmentRequested>(body.Span, JsonSerializerOptions.Web);
        }
        catch (JsonException ex)
        {
            // JsonException mesajı değer değil, yalnızca konum (path/satır) içerir.
            errors = [$"JSON çözülemedi ({ex.Path ?? "kök"})"];
            return null;
        }
        if (command is null)
        {
            errors = ["gövde boş"];
            return null;
        }
        errors = command.Validate();
        return errors.Count == 0 ? command : null;
    }
}
