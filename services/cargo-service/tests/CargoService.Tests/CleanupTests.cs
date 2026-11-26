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

    /// <summary>Gönderiyi kapalı bir duruma çeker: sonraki testlerin işleri bu yapay satırı ele almasın.</summary>
    private Task CloseAsync(long orderId) =>
        Db.ExecuteAsync(containers,
            "UPDATE cargo.shipments SET status = 'FAILED', tracking_number = NULL, recipient = NULL WHERE order_id = $1", orderId);

    /// <summary>
    /// Takılı kalan (açık durumdaki) gönderinin son değişikliğini <paramref name="age"/> kadar geriye alır.
    /// SENDING'de updated_at yeni (uzlaştırıcı her denemede yeniler), sending_since eski bırakılır.
    /// </summary>
    private async Task<long> InsertStaleAsync(TestApp app, string status, TimeSpan age)
    {
        _ = app.Services; // host açılır, migration'lar uygulanır (tek başına koşulan testte tablo olsun)
        var orderId = await InsertAsync(containers, status,
            trackingNumber: status is "CREATED" or "IN_TRANSIT" ? $"TKS{NewOrderId()}" : null);
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.shipments SET created_at = now() - $2::interval, " +
            "updated_at = CASE WHEN status = 'SENDING' THEN now() ELSE now() - $2::interval END, " +
            "sending_since = CASE WHEN status = 'SENDING' THEN now() - $2::interval END, " +
            "next_attempt_at = CASE WHEN status = 'SENDING' THEN now() + interval '1 hour' END " +
            "WHERE order_id = $1", orderId, age);
        return orderId;
    }

    [Theory]
    [InlineData("REQUESTED")]
    [InlineData("SENDING")]
    [InlineData("CREATED")]
    [InlineData("IN_TRANSIT")]
    public async Task Takılı_kalan_gönderinin_alıcı_verisi_180_gün_sonra_silinir_daha_yenisinde_kalır(string status)
    {
        await using var app = TestApp.For(containers);
        var stale = await InsertStaleAsync(app, status, TimeSpan.FromDays(181));
        var recent = await InsertStaleAsync(app, status, TimeSpan.FromDays(179));

        await app.Services.GetRequiredService<Cleanup>().RunOnceAsync(Ct);

        var staleHas = await HasRecipientAsync(stale);
        var recentHas = await HasRecipientAsync(recent);
        var staleStatus = await Db.ScalarAsync<string>(containers, "SELECT status FROM cargo.shipments WHERE order_id = $1", stale);
        await CloseAsync(stale);
        await CloseAsync(recent);
        Assert.False(staleHas, $"{status} gönderinin alıcı bilgisi 180 gün sonra hâlâ duruyor.");
        Assert.True(recentHas);
        Assert.Equal(status, staleStatus); // durum temizlikte değişmez; satır silinmez
    }

    [Fact]
    public async Task Takılı_gönderi_saklama_süresi_ayarla_değiştirilebilir()
    {
        await using var app = TestApp.For(containers,
            new Dictionary<string, string?> { ["Workers:StaleRecipientRetention"] = "10.00:00:00" });
        var orderId = await InsertStaleAsync(app, "IN_TRANSIT", TimeSpan.FromDays(11));

        await app.Services.GetRequiredService<Cleanup>().RunOnceAsync(Ct);

        var has = await HasRecipientAsync(orderId);
        await CloseAsync(orderId);
        Assert.False(has);
    }

    [Fact]
    public async Task Alıcısı_silinmiş_REQUESTED_gönderi_firmaya_gitmeden_FAILED_olur_ve_shipment_failed_yazılır()
    {
        await using var app = TestApp.For(containers, FastSettings());
        await DrainAsync(app);
        var orderId = await InsertStaleAsync(app, "REQUESTED", TimeSpan.FromDays(181));
        await app.Services.GetRequiredService<Cleanup>().RunOnceAsync(Ct);

        await RunWorkerAsync(app);

        var shipment = await GetAsync(app, orderId);
        Assert.Equal(CargoService.Data.ShipmentStatus.Failed, shipment.Status);
        Assert.Equal(ShipmentOutcomes.MissingDataReason, shipment.FailureReason);
        Assert.Equal(0L, await FakeCarrierRowsAsync(containers, orderId));
        Assert.Contains(await EventsAsync(containers, orderId), e => e.RoutingKey == "shipment.failed");
    }

    [Fact]
    public async Task Alıcısı_silinmiş_SENDING_gönderi_firmada_yoksa_FAILED_olur_ve_shipment_failed_yazılır()
    {
        await using var app = TestApp.For(containers, FastSettings());
        await DrainAsync(app);
        var orderId = await InsertStaleAsync(app, "SENDING", TimeSpan.FromDays(181));
        await app.Services.GetRequiredService<Cleanup>().RunOnceAsync(Ct);
        await Db.ExecuteAsync(containers, "UPDATE cargo.shipments SET next_attempt_at = NULL WHERE order_id = $1", orderId);

        await RunReconcilerAsync(app);

        var shipment = await GetAsync(app, orderId);
        Assert.Equal(CargoService.Data.ShipmentStatus.Failed, shipment.Status);
        Assert.Equal(ShipmentOutcomes.MissingDataReason, shipment.FailureReason);
        Assert.Equal(0L, await FakeCarrierRowsAsync(containers, orderId));
        Assert.Contains(await EventsAsync(containers, orderId), e => e.RoutingKey == "shipment.failed");
    }
}
