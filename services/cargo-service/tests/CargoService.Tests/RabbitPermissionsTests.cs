using System.Text;
using CargoService.Messaging;
using CargoService.Tests.Infrastructure;
using RabbitMQ.Client;
using RabbitMQ.Client.Exceptions;

namespace CargoService.Tests;

/// <summary>
/// cargo kullanıcısının RabbitMQ izinleri (docker/rabbitmq/init-cargo-user.sh, fixture'da gerçek betik
/// çalışır) ile topolojinin uyumu: servis kendi tanımlarını yapabilir, ama varsayılan exchange ("")
/// üzerinden başka servislerin kuyruklarına (ör. iyzico.charge-requests) mesaj bırakamaz.
/// </summary>
[Collection("Integration")]
public sealed class RabbitPermissionsTests(ContainersFixture containers)
{
    private const ushort AccessRefused = 403;

    [Fact]
    public async Task Cargo_kullanıcısı_topolojiyi_tanımlayabilir()
    {
        await using var connection = await ConnectAsCargoAsync();
        await using var channel = await connection.CreateChannelAsync(cancellationToken: Ct);

        await CargoTopology.DeclareAsync(channel, Ct);

        Assert.True(channel.IsOpen);
    }

    [Theory]
    [InlineData("iyzico.charge-requests")]
    [InlineData(CargoTopology.ShipmentRequestsQueue)]
    public async Task Cargo_kullanıcısı_varsayılan_exchangee_yayınlayamaz(string queue)
    {
        await using var admin = await RabbitTestClient.ConnectAsync(containers);
        await using var connection = await ConnectAsCargoAsync();
        await using var channel = await ConfirmChannelAsync(connection);

        await Assert.ThrowsAnyAsync<Exception>(async () =>
            await channel.BasicPublishAsync("", queue, mandatory: false, new BasicProperties(),
                Encoding.UTF8.GetBytes("{}"), Ct));

        Assert.Equal(AccessRefused, channel.CloseReason?.ReplyCode);
    }

    [Fact]
    public async Task Cargo_kullanıcısı_komut_yayınlayamaz()
    {
        await using var admin = await RabbitTestClient.ConnectAsync(containers);
        await using var connection = await ConnectAsCargoAsync();
        await using var channel = await ConfirmChannelAsync(connection);

        await Assert.ThrowsAnyAsync<Exception>(async () =>
            await channel.BasicPublishAsync(CargoTopology.Exchange, CargoTopology.ShipmentCreateRoutingKey,
                mandatory: false, new BasicProperties(), Encoding.UTF8.GetBytes("{}"), Ct));

        Assert.Equal(AccessRefused, channel.CloseReason?.ReplyCode);
    }

    /// <summary>
    /// Yeniden yayın yolu: cargo kullanıcısı retry exchange'ine yayınlar, kopya komut kuyruğuna düşer;
    /// reddedilen komut adlı DLX üzerinden DLQ'ya gider.
    /// </summary>
    [Fact]
    public async Task Retry_exchangei_komut_kuyruğuna_reddedilen_komut_adlı_DLX_ile_DLQya_gider()
    {
        await using var admin = await RabbitTestClient.ConnectAsync(containers);
        await admin.DrainAsync(CargoTopology.ShipmentRequestsQueue);
        await using var connection = await ConnectAsCargoAsync();
        await using var channel = await ConfirmChannelAsync(connection);
        var messageId = Guid.NewGuid().ToString();

        await channel.BasicPublishAsync(CargoTopology.RetryExchange, CargoTopology.ShipmentRequestsQueue, mandatory: true,
            new BasicProperties { MessageId = messageId, Persistent = true }, Encoding.UTF8.GetBytes("{}"), Ct);

        BasicGetResult? got = null;
        for (var i = 0; i < 50 && got is null; i++)
        {
            got = await channel.BasicGetAsync(CargoTopology.ShipmentRequestsQueue, autoAck: false, Ct);
            if (got is null)
            {
                await Task.Delay(100, Ct);
            }
        }
        Assert.NotNull(got);
        Assert.Equal(messageId, got.BasicProperties.MessageId);
        await channel.BasicRejectAsync(got.DeliveryTag, requeue: false, Ct);

        var dead = await admin.FindAsync(CargoTopology.ShipmentRequestsDeadLetterQueue, messageId);
        Assert.NotNull(dead);
        // x-first-death-exchange asıl yayının exchange'idir (cargo.retry), DLX değil; DLX'in cargo.dlx olduğu
        // kuyruk argümanlarından gelir (backend ile aynı argümanlar, CargoTopologyIntegrationTest).
        var headers = dead.BasicProperties.Headers!;
        Assert.Equal(CargoTopology.ShipmentRequestsQueue, Encoding.UTF8.GetString((byte[])headers["x-first-death-queue"]!));
        Assert.Equal("rejected", Encoding.UTF8.GetString((byte[])headers["x-first-death-reason"]!));
    }

    /// <summary>
    /// Tüketicinin "kopya kaybolmaz" güvencesinin dayanağı: retry exchange'inde yönlenemeyen mandatory yayın,
    /// onay izlemeli kanalda istisna verir (tüketici bu durumda aslını nack(requeue) eder, ack etmez).
    /// </summary>
    [Fact]
    public async Task Retry_exchangeinde_yönlenemeyen_mandatory_yayın_istisna_verir()
    {
        await using var admin = await RabbitTestClient.ConnectAsync(containers);
        await using var connection = await ConnectAsCargoAsync();
        await using var channel = await ConfirmChannelAsync(connection);

        await Assert.ThrowsAsync<PublishReturnException>(async () =>
            await channel.BasicPublishAsync(CargoTopology.RetryExchange, "bagi-olmayan-anahtar", mandatory: true,
                new BasicProperties(), Encoding.UTF8.GetBytes("{}"), Ct));
    }

    private async Task<IConnection> ConnectAsCargoAsync() =>
        await new ConnectionFactory { Uri = new Uri(containers.CargoRabbitMqUrl) }.CreateConnectionAsync(Ct);

    private static async Task<IChannel> ConfirmChannelAsync(IConnection connection) =>
        await connection.CreateChannelAsync(
            new CreateChannelOptions(publisherConfirmationsEnabled: true, publisherConfirmationTrackingEnabled: true), Ct);

    private static CancellationToken Ct => TestContext.Current.CancellationToken;
}
