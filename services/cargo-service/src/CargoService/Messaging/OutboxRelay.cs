using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using CargoService.Configuration;
using CargoService.Data;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using RabbitMQ.Client;

namespace CargoService.Messaging;

/// <summary>
/// Outbox'taki olayları RabbitMQ'ya iletir. Her turda, parti parti:
///  1. Bir işlem açar, yayınlanmamış satırları <c>FOR UPDATE SKIP LOCKED</c> ile kilitler (başka bir
///     tur ya da başka bir servis kopyası aynı satırları alamaz, atlar),
///  2. Satırları sırayla yayınlar ve her biri için RabbitMQ'nun onayını (publisher confirm) bekler,
///  3. Onaylananlara aynı işlemde <c>published_at</c> yazar; onay gelmeyen satır yayınlanmamış kalır,
///     <c>attempts</c> artar ve <c>last_error</c>'a yalnızca hata özeti (istisna tipleri + SqlState; mesaj ve payload ASLA) yazılır.
///     Hatadan sonraki satırlar o turda denenmez, sonraki turda gider.
///
/// Tur iki şekilde başlar: outbox'a yazan kod commit sonrası <see cref="OutboxSignal"/> ile haber
/// verir (hemen), ya da yedek olarak <c>Workers:OutboxInterval</c> (varsayılan 1 sn) dolunca.
/// Hata alan turdan sonra 5 sn beklenir (RabbitMQ kapalıyken saniyede bir log yazılmasın).
///
/// En az bir kez teslim: 2. ile 3. arasında süreç ölürse olay bir kez daha gönderilir; dinleyiciler
/// tekrar gelen olayı messageId ile ayıklar. <c>Workers:Enabled=false</c> iken arka plan döngüsü
/// çalışmaz; testler <see cref="RunOnceAsync"/> ile tur attırır.
/// </summary>
public sealed partial class OutboxRelay(
    IServiceScopeFactory scopes,
    RabbitConnectionProvider rabbit,
    OutboxSignal signal,
    IOptions<CargoOptions> options,
    ILogger<OutboxRelay> logger) : BackgroundService
{
    private const int BatchSize = 20;
    private static readonly TimeSpan ConfirmTimeout = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan FailureBackoff = TimeSpan.FromSeconds(5);

    // Aynı süreçteki turlar sırayla çalışır (yayın kanalı paylaşılır); süreçler arası ayrımı kilitler sağlar.
    private readonly SemaphoreSlim _turn = new(1, 1);
    private IChannel? _channel;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (!options.Value.Workers.Enabled)
        {
            return;
        }
        if (!rabbit.IsConfigured)
        {
            logger.LogWarning("RabbitMq:Url tanımlı değil: olaylar outbox'ta bekleyecek, gönderilmeyecek");
            return;
        }

        while (!stoppingToken.IsCancellationRequested)
        {
            var failed = false;
            try
            {
                failed = !(await RunTurnAsync(stoppingToken)).Ok;
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                // Veritabanı hatası vb.: satırlar yerinde kalır, sonraki turda tekrar denenir.
                logger.LogWarning("Outbox turu başarısız, tekrar denenecek: {Error}", CommandFailures.Describe(ex));
                failed = true;
            }

            try
            {
                if (failed)
                {
                    await Task.Delay(FailureBackoff, stoppingToken);
                }
                else
                {
                    await signal.WaitAsync(options.Value.Workers.OutboxInterval, stoppingToken);
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    /// <summary>Bekleyen satırları gönderir; gönderilen satır sayısını döner. RabbitMQ ayarı yoksa 0.</summary>
    public async Task<int> RunOnceAsync(CancellationToken ct) => (await RunTurnAsync(ct)).Published;

    /// <summary>Partiler bitene ya da bir yayın hatası olana kadar gönderir.</summary>
    private async Task<(int Published, bool Ok)> RunTurnAsync(CancellationToken ct)
    {
        if (!rabbit.IsConfigured)
        {
            return (0, true);
        }
        await _turn.WaitAsync(ct);
        try
        {
            var total = 0;
            while (true)
            {
                var (published, locked, ok) = await PublishBatchAsync(ct);
                total += published;
                if (!ok || locked < BatchSize)
                {
                    return (total, ok);
                }
            }
        }
        finally
        {
            _turn.Release();
        }
    }

    private async Task<(int Published, int Locked, bool Ok)> PublishBatchAsync(CancellationToken ct)
    {
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<CargoDbContext>();
        await using var tx = await db.Database.BeginTransactionAsync(ct);

        var rows = await db.OutboxMessages
            .FromSql($"""
                SELECT * FROM cargo.outbox_messages
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT {BatchSize}
                FOR UPDATE SKIP LOCKED
                """)
            .ToListAsync(ct);
        if (rows.Count == 0)
        {
            await tx.CommitAsync(ct);
            return (0, 0, true);
        }

        var published = 0;
        Exception? failure = null;
        try
        {
            var channel = await GetChannelAsync(ct);
            foreach (var row in rows)
            {
                try
                {
                    await PublishAsync(channel, row, ct);
                }
                catch (Exception ex) when (!ct.IsCancellationRequested)
                {
                    row.Attempts++;
                    row.LastError = Truncate(CommandFailures.Describe(ex));
                    failure = ex;
                    break;
                }
                row.Attempts++;
                row.PublishedAt = DateTimeOffset.UtcNow;
                published++;
            }
        }
        catch (Exception ex) when (!ct.IsCancellationRequested)
        {
            // Bağlantı/kanal açılamadı: kilitlenen bütün satırlar bu denemeyi hata olarak sayar.
            failure = ex;
            foreach (var row in rows)
            {
                row.Attempts++;
                row.LastError = Truncate(CommandFailures.Describe(ex));
            }
        }

        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);

        if (failure is not null)
        {
            await DropChannelAsync();
            logger.LogWarning("Outbox: {Published} olay gönderildi, kalanlar sonra tekrar denenecek: {Error}",
                published, CommandFailures.Describe(failure));
            return (published, rows.Count, false);
        }
        logger.LogInformation("{Published} olay RabbitMQ'ya gönderildi", published);
        return (published, rows.Count, true);
    }

    private static async Task PublishAsync(IChannel channel, OutboxMessage row, CancellationToken ct)
    {
        var headers = new Dictionary<string, object?> { [CargoTopology.EventVersionHeader] = CargoTopology.EventVersion };
        if (TraceParentOf(row.Payload) is { } traceparent)
        {
            headers["traceparent"] = traceparent;
        }
        var props = new BasicProperties
        {
            MessageId = row.Id.ToString(),
            Type = row.Type,
            ContentType = "application/json",
            Persistent = true, // RabbitMQ yeniden başlasa da kaybolmaz
            Timestamp = new AmqpTimestamp(row.CreatedAt.ToUnixTimeSeconds()),
            Headers = headers,
        };

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(ConfirmTimeout);
        // Kanal onay takibiyle açıldığından bu çağrı RabbitMQ'nun ack'i gelince tamamlanır; nack ya da
        // bağlantı kopması istisna fırlatır, onay süresinde gelmezse iptal edilir.
        await channel.BasicPublishAsync(row.Exchange, row.RoutingKey, mandatory: false, props,
            Encoding.UTF8.GetBytes(row.Payload), timeout.Token);
    }

    private async Task<IChannel> GetChannelAsync(CancellationToken ct)
    {
        if (_channel is { IsOpen: true } open)
        {
            return open;
        }
        await DropChannelAsync();
        var connection = await rabbit.GetConnectionAsync(ct);
        var channel = await connection.CreateChannelAsync(
            new CreateChannelOptions(publisherConfirmationsEnabled: true, publisherConfirmationTrackingEnabled: true),
            ct);
        await CargoTopology.DeclareAsync(channel, ct);
        _channel = channel;
        return channel;
    }

    private async Task DropChannelAsync()
    {
        var channel = _channel;
        _channel = null;
        if (channel is null)
        {
            return;
        }
        try
        {
            await channel.DisposeAsync();
        }
        catch
        {
            // Kopmuş kanalı kapatırken çıkan hata önemsizdir.
        }
    }

    /// <summary>
    /// Olaydaki traceId'den W3C traceparent üretir (yeni rastgele span id ile); traceId yoksa ya da
    /// geçersizse null. Olay geç yayınlansa da isteği başlatan izle aynı zincirde görünür.
    /// </summary>
    private static string? TraceParentOf(string payload)
    {
        try
        {
            using var doc = JsonDocument.Parse(payload);
            if (doc.RootElement.ValueKind == JsonValueKind.Object
                && doc.RootElement.TryGetProperty("traceId", out var traceId)
                && traceId.ValueKind == JsonValueKind.String
                && traceId.GetString() is { } value
                && TraceIdPattern().IsMatch(value)
                && value.Any(c => c != '0'))
            {
                return $"00-{value}-{Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(8))}-01";
            }
        }
        catch (JsonException)
        {
            // payload jsonb'den gelir, bozuk olamaz; yine de iz başlığı olmadan yayınlanır.
        }
        return null;
    }

    private static string Truncate(string value) => value.Length <= 500 ? value : value[..500];

    public override void Dispose()
    {
        _channel?.Dispose();
        _turn.Dispose();
        base.Dispose();
    }

    [GeneratedRegex("^[0-9a-f]{32}$")]
    private static partial Regex TraceIdPattern();
}
