using CargoService.Carriers.Fake;
using CargoService.Configuration;
using CargoService.Data;
using CargoService.Messaging;
using CargoService.Shipping;
using CargoService.Tests.Infrastructure;
using static CargoService.Tests.Infrastructure.Shipments;

namespace CargoService.Tests;

[Collection("Integration")]
public sealed class ShipmentWorkerTests(ContainersFixture containers)
{
    private TestApp App(Dictionary<string, string?>? extra = null) => TestApp.For(containers, FastSettings(extra));

    [Fact]
    public async Task REQUESTED_gönderi_firmada_açılır_CREATED_olur_ve_kişisel_veri_içermeyen_olay_yazılır()
    {
        await using var app = App();
        await DrainAsync(app);
        var orderId = await RequestAsync(app);

        Assert.True(await RunWorkerAsync(app) >= 1);

        var shipment = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.Created, shipment.Status);
        Assert.Matches("^TK[0-9]{10}$", shipment.TrackingNumber);
        Assert.Equal("FAKE", shipment.CarrierCode);
        Assert.Equal("Test Kargo", shipment.CarrierName);
        Assert.NotNull(shipment.CreatedAtCarrier);
        Assert.Null(shipment.SendingSince);
        Assert.Null(shipment.NextAttemptAt);
        Assert.Equal(1, shipment.Attempts);
        Assert.Equal(1, shipment.EventSequence);
        Assert.Equal(1, await FakeCarrierRowsAsync(containers, orderId));

        var (routingKey, payload) = Assert.Single(await EventsAsync(containers, orderId));
        Assert.Equal(CargoTopology.ShipmentCreated, routingKey);
        Assert.Equal(1, payload.GetProperty("sequence").GetInt32());
        Assert.Equal("CREATED", payload.GetProperty("data").GetProperty("status").GetString());
        Assert.Equal(shipment.TrackingNumber, payload.GetProperty("data").GetProperty("trackingNumber").GetString());
        var text = payload.GetRawText();
        foreach (var secret in new[] { FirstName, LastName, Phone, Address, "recipient" })
        {
            Assert.DoesNotContain(secret, text, StringComparison.OrdinalIgnoreCase);
        }
    }

    [Fact]
    public async Task Firma_kesin_reddederse_FAILED_olur_ve_shipment_failed_yazılır()
    {
        await using var app = App();
        await DrainAsync(app);
        var orderId = await RequestAsync(app);
        Fault(app, FakeFailMode.Permanent);

        await RunWorkerAsync(app);

        var shipment = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.Failed, shipment.Status);
        Assert.Equal(FakeCarrierGateway.PermanentFailureReason, shipment.FailureReason);
        Assert.Null(shipment.TrackingNumber);
        Assert.Equal(0, await FakeCarrierRowsAsync(containers, orderId));
        var (routingKey, payload) = Assert.Single(await EventsAsync(containers, orderId));
        Assert.Equal(CargoTopology.ShipmentFailed, routingKey);
        Assert.Equal("FAILED", payload.GetProperty("data").GetProperty("status").GetString());
        Assert.Equal(FakeCarrierGateway.PermanentFailureReason, payload.GetProperty("data").GetProperty("failureReason").GetString());
    }

    [Fact]
    public async Task Açıldıktan_sonra_zaman_aşımı_SENDING_bırakır_reconciler_tek_gönderiyle_CREATED_yapar()
    {
        await using var app = App();
        await DrainAsync(app);
        var orderId = await RequestAsync(app);
        Fault(app, FakeFailMode.TimeoutAfterCreate);

        await RunWorkerAsync(app);

        var sending = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.Sending, sending.Status);
        Assert.Null(sending.TrackingNumber);
        Assert.NotNull(sending.LastError);
        Assert.NotNull(sending.NextAttemptAt);
        Assert.Empty(await EventsAsync(containers, orderId));
        Assert.Equal(1, await FakeCarrierRowsAsync(containers, orderId)); // firmada açıldı, yanıt gelmedi

        await RunReconcilerAsync(app);

        var created = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.Created, created.Status);
        Assert.Equal(1, await FakeCarrierRowsAsync(containers, orderId));
        Assert.Equal(await Db.ScalarAsync<string>(containers,
                "SELECT tracking_number FROM cargo.fake_carrier_shipments WHERE idempotency_key = $1", $"order-{orderId}"),
            created.TrackingNumber);
        Assert.Equal(CargoTopology.ShipmentCreated, Assert.Single(await EventsAsync(containers, orderId)).RoutingKey);
    }

    [Fact]
    public async Task Açılmadan_önce_zaman_aşımı_sonrası_reconciler_aynı_anahtarla_tek_gönderi_açar()
    {
        await using var app = App();
        await DrainAsync(app);
        var orderId = await RequestAsync(app);
        Fault(app, FakeFailMode.TimeoutBeforeCreate);

        await RunWorkerAsync(app);

        Assert.Equal(ShipmentStatus.Sending, (await GetAsync(app, orderId)).Status);
        Assert.Equal(0, await FakeCarrierRowsAsync(containers, orderId));

        await RunReconcilerAsync(app);

        var created = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.Created, created.Status);
        Assert.Equal(1, await FakeCarrierRowsAsync(containers, orderId));
        Assert.Equal(CargoTopology.ShipmentCreated, Assert.Single(await EventsAsync(containers, orderId)).RoutingKey);
    }

    [Fact]
    public async Task Sonuç_belirsiz_kaldıkça_gönderi_FAILED_yapılmaz_bekler_ve_sonunda_açılır()
    {
        await using var app = App();
        await DrainAsync(app);
        var orderId = await RequestAsync(app);
        Fault(app, FakeFailMode.TimeoutBeforeCreate, times: 4);

        await RunWorkerAsync(app);
        for (var i = 0; i < 3; i++)
        {
            await RunReconcilerAsync(app);
            var shipment = await GetAsync(app, orderId);
            Assert.Equal(ShipmentStatus.Sending, shipment.Status);
            Assert.Equal(i + 2, shipment.Attempts);
        }
        Assert.Empty(await EventsAsync(containers, orderId));

        await RunReconcilerAsync(app); // hata planı bitti

        Assert.Equal(ShipmentStatus.Created, (await GetAsync(app, orderId)).Status);
        Assert.Equal(1, await FakeCarrierRowsAsync(containers, orderId));
    }

    [Fact]
    public async Task Belirsiz_sonuçta_bir_sonraki_deneme_artan_geri_çekilmeyle_planlanır()
    {
        await using var app = App(new() { ["Workers:RetryBaseDelay"] = "00:00:10" });
        await DrainAsync(app);
        var orderId = await RequestAsync(app);
        Fault(app, FakeFailMode.TimeoutBeforeCreate);

        var before = DateTimeOffset.UtcNow;
        await RunWorkerAsync(app);

        var next = (await GetAsync(app, orderId)).NextAttemptAt;
        Assert.NotNull(next);
        Assert.InRange(next.Value, before.AddSeconds(9), DateTimeOffset.UtcNow.AddSeconds(11));

        var baseDelay = TimeSpan.FromSeconds(10);
        Assert.Equal(TimeSpan.FromSeconds(10), ShipmentOutcomes.RetryDelay(baseDelay, attempts: 1));
        Assert.Equal(TimeSpan.FromSeconds(20), ShipmentOutcomes.RetryDelay(baseDelay, attempts: 2));
        Assert.Equal(TimeSpan.FromSeconds(40), ShipmentOutcomes.RetryDelay(baseDelay, attempts: 3));
        Assert.Equal(TimeSpan.FromHours(1), ShipmentOutcomes.RetryDelay(baseDelay, attempts: 50)); // üst sınır
    }

    [Fact]
    public async Task İki_worker_örneği_aynı_anda_çalışsa_da_her_gönderi_bir_kez_sahiplenilir()
    {
        var slow = new Dictionary<string, string?> { ["Carrier:Fake:DelayMs"] = "150" };
        await using var first = App(slow);
        await using var second = App(slow);
        await DrainAsync(first);
        await DrainAsync(second); // ikinci örnek de ısınsın (EF modeli/sorgu derlemesi yarışı bozmasın)
        var orders = new List<long>();
        for (var i = 0; i < 12; i++)
        {
            orders.Add(await RequestAsync(first));
        }

        var claimed = await Task.WhenAll(RunWorkerAsync(first), RunWorkerAsync(second));

        // Toplam tam sipariş sayısı kadar: hiçbir satır iki örnek tarafından birden alınmadı. (Hangi örneğin
        // kaç satır aldığı zamanlamaya bağlıdır: PostgreSQL kilidi LIMIT'ten sonra uyguladığından eşzamanlı
        // ikinci örnek kilitli ilk partiyi atlar ve o turda hiç satır almayabilir.)
        Assert.Equal(orders.Count, claimed.Sum());
        foreach (var orderId in orders)
        {
            var shipment = await GetAsync(first, orderId);
            Assert.Equal(ShipmentStatus.Created, shipment.Status);
            Assert.Equal(1, shipment.Attempts);
            Assert.Single(await EventsAsync(containers, orderId));
        }
    }

    [Fact]
    public async Task Workers_açıkken_intake_sinyali_workerı_yedek_turu_beklemeden_uyandırır()
    {
        await using var app = App(new()
        {
            ["Workers:Enabled"] = "true",
            ["Workers:SendInterval"] = "01:00:00",
            ["Workers:ReconcileInterval"] = "01:00:00",
            ["Workers:PollInterval"] = "01:00:00",
            ["Workers:CleanupInterval"] = "01:00:00",
        });
        _ = app.Services; // host'u başlatır; worker açılışta bir tur atar, sonra sinyal bekler
        for (var i = 0; i < 100 && await Db.ScalarAsync<long>(containers,
                 "SELECT count(*) FROM cargo.shipments WHERE status IN ('REQUESTED', 'SENDING')") > 0; i++)
        {
            await Task.Delay(100, Ct);
        }
        await Task.Delay(300, Ct);

        var orderId = await RequestAsync(app);

        for (var i = 0; i < 100 && (await GetAsync(app, orderId)).Status != ShipmentStatus.Created; i++)
        {
            await Task.Delay(100, Ct);
        }
        Assert.Equal(ShipmentStatus.Created, (await GetAsync(app, orderId)).Status);
    }
}
