using CargoService.Shipping;
using CargoService.Tests.Infrastructure;
using Microsoft.Extensions.DependencyInjection;
using static CargoService.Tests.Infrastructure.Shipments;

namespace CargoService.Tests;

[Collection("Integration")]
public sealed class CleanupTests(ContainersFixture containers)
{
    private Task<bool> ExistsAsync(string sql, object arg) =>
        Db.ScalarAsync<bool>(containers, $"SELECT EXISTS ({sql})", arg);

    private async Task<string> InsertProcessedAsync(TimeSpan age)
    {
        var id = Guid.NewGuid().ToString();
        await Db.ExecuteAsync(containers,
            "INSERT INTO cargo.processed_messages (message_id, processed_at) VALUES ($1, now() - $2::interval)", id, age);
        return id;
    }

    private async Task<Guid> InsertOutboxAsync(TimeSpan age, bool published)
    {
        var id = await Db.InsertOutboxAsync(containers, "shipment.created");
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.outbox_messages SET created_at = now() - $2::interval, " +
            "published_at = CASE WHEN $3 THEN now() - $2::interval END WHERE id = $1", id, age, published);
        return id;
    }

    private Task<bool> HasRecipientAsync(long orderId) =>
        Db.ScalarAsync<bool>(containers, "SELECT recipient IS NOT NULL FROM cargo.shipments WHERE order_id = $1", orderId);

    [Fact]
    public async Task Eski_inbox_ve_yayınlanmış_outbox_satırları_silinir_yenileri_ve_yayınlanmamışlar_kalır()
    {
        await using var app = TestApp.For(containers);
        var oldProcessed = await InsertProcessedAsync(TimeSpan.FromDays(15));
        var newProcessed = await InsertProcessedAsync(TimeSpan.FromDays(13));
        var oldPublished = await InsertOutboxAsync(TimeSpan.FromDays(8), published: true);
        var newPublished = await InsertOutboxAsync(TimeSpan.FromDays(6), published: true);
        var oldUnpublished = await InsertOutboxAsync(TimeSpan.FromDays(60), published: false);

        await app.Services.GetRequiredService<Cleanup>().RunOnceAsync(Ct);

        const string processed = "SELECT 1 FROM cargo.processed_messages WHERE message_id = $1";
        const string outbox = "SELECT 1 FROM cargo.outbox_messages WHERE id = $1";
        Assert.False(await ExistsAsync(processed, oldProcessed));
        Assert.True(await ExistsAsync(processed, newProcessed));
        Assert.False(await ExistsAsync(outbox, oldPublished));
        Assert.True(await ExistsAsync(outbox, newPublished));
        Assert.True(await ExistsAsync(outbox, oldUnpublished)); // yayınlanmamış olay asla silinmez
        await Db.ExecuteAsync(containers, "UPDATE cargo.outbox_messages SET published_at = now() WHERE id = $1", oldUnpublished);
    }

    [Fact]
    public async Task Teslimden_30_gün_sonra_alıcı_bilgisi_silinir_daha_yeni_ya_da_teslim_edilmemiş_gönderide_kalır()
    {
        await using var app = TestApp.For(containers);
        var oldDelivered = await InsertAsync(containers, "DELIVERED", trackingNumber: $"TKC{NewOrderId()}",
            deliveredAt: DateTimeOffset.UtcNow.AddDays(-31));
        var recentDelivered = await InsertAsync(containers, "DELIVERED", trackingNumber: $"TKC{NewOrderId()}",
            deliveredAt: DateTimeOffset.UtcNow.AddDays(-29));
        var oldInTransit = await InsertAsync(containers, "IN_TRANSIT", trackingNumber: $"TKC{NewOrderId()}");
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.shipments SET created_at = now() - interval '90 days' WHERE order_id = $1", oldInTransit);

        await app.Services.GetRequiredService<Cleanup>().RunOnceAsync(Ct);

        Assert.False(await HasRecipientAsync(oldDelivered));
        Assert.True(await HasRecipientAsync(recentDelivered));
        Assert.True(await HasRecipientAsync(oldInTransit));
        // Gönderinin kendisi silinmez; yalnızca kişisel veri.
        Assert.Equal("DELIVERED", await Db.ScalarAsync<string>(containers,
            "SELECT status FROM cargo.shipments WHERE order_id = $1", oldDelivered));
        // Takip sorgusu bu yapay gönderileri firmada aramasın.
        await Db.ExecuteAsync(containers, "UPDATE cargo.shipments SET status = 'DELIVERED' WHERE order_id = $1", oldInTransit);
    }
}
