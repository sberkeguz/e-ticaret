using System.Diagnostics.Metrics;
using CargoService.Carriers;
using CargoService.Configuration;
using CargoService.Data;
using CargoService.Messaging;
using CargoService.Shipping;
using CargoService.Tests.Infrastructure;
using Microsoft.Extensions.DependencyInjection;
using static CargoService.Tests.Infrastructure.Shipments;

namespace CargoService.Tests;

/// <summary>
/// Servis firma çağrısından önce ya da sonra ölmüş gibi SENDING satırı elle yazılır; reconciler firmada
/// tekrar anahtarıyla arar, yoksa aynı anahtarla açar. Her durumda firmada TEK gönderi olur.
/// </summary>
[Collection("Integration")]
public sealed class CrashRecoveryTests(ContainersFixture containers)
{
    private TestApp App(Dictionary<string, string?>? extra = null) => TestApp.For(containers, FastSettings(extra));

    [Fact]
    public async Task Firma_çağrısından_önce_ölünmüşse_reconciler_tek_gönderi_açar()
    {
        await using var app = App();
        await DrainAsync(app);
        var orderId = await InsertAsync(containers, "SENDING", sendingSince: DateTimeOffset.UtcNow.AddMinutes(-5));

        await RunReconcilerAsync(app);

        var shipment = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.Created, shipment.Status);
        Assert.Equal(2, shipment.Attempts);
        Assert.Equal(1, await FakeCarrierRowsAsync(containers, orderId));
        Assert.Equal(CargoTopology.ShipmentCreated, Assert.Single(await EventsAsync(containers, orderId)).RoutingKey);
    }

    [Fact]
    public async Task Firma_gönderiyi_açtıktan_sonra_ölünmüşse_reconciler_onu_bulur_yenisini_açmaz()
    {
        await using var app = App();
        await DrainAsync(app);
        var orderId = await InsertAsync(containers, "SENDING", sendingSince: DateTimeOffset.UtcNow.AddMinutes(-5));
        var atCarrier = Assert.IsType<CarrierCreateResult.Created>(await app.Services.GetRequiredService<ICarrierGateway>()
            .CreateAsync($"order-{orderId}", Recipient(), new() { ItemCount = 1, DeclaredValue = 1m }, Ct));

        await RunReconcilerAsync(app);

        var shipment = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.Created, shipment.Status);
        Assert.Equal(atCarrier.TrackingNumber, shipment.TrackingNumber);
        Assert.Equal(1, await FakeCarrierRowsAsync(containers, orderId));
        Assert.Single(await EventsAsync(containers, orderId));
    }

    [Fact]
    public async Task Yeni_SENDING_kaydına_iki_dakika_dolmadan_dokunulmaz()
    {
        await using var app = App(new() { ["Workers:SendingStuckAfter"] = "00:02:00" });
        await DrainAsync(app);
        var orderId = await InsertAsync(containers, "SENDING", sendingSince: DateTimeOffset.UtcNow.AddSeconds(-30));

        await RunReconcilerAsync(app);

        var shipment = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.Sending, shipment.Status);
        Assert.Equal(1, shipment.Attempts);
        Assert.Equal(0, await FakeCarrierRowsAsync(containers, orderId));
        await Db.ExecuteAsync(containers, "UPDATE cargo.shipments SET status = 'FAILED', sending_since = NULL WHERE order_id = $1", orderId);
    }

    [Fact]
    public async Task İki_reconciler_aynı_anda_çalışsa_da_takılı_kayıt_bir_kez_işlenir()
    {
        var slow = new Dictionary<string, string?> { ["Carrier:Fake:DelayMs"] = "150" };
        await using var first = App(slow);
        await using var second = App(slow);
        await DrainAsync(first);
        await DrainAsync(second);
        var orderId = await InsertAsync(containers, "SENDING", sendingSince: DateTimeOffset.UtcNow.AddMinutes(-5));

        await Task.WhenAll(RunReconcilerAsync(first), RunReconcilerAsync(second));

        var shipment = await GetAsync(first, orderId);
        Assert.Equal(ShipmentStatus.Created, shipment.Status);
        Assert.Equal(2, shipment.Attempts);
        Assert.Equal(1, await FakeCarrierRowsAsync(containers, orderId));
        Assert.Single(await EventsAsync(containers, orderId));
    }

    [Fact]
    public async Task Otuz_dakikayı_aşan_belirsiz_kayıtlar_metrik_olarak_yayınlanır()
    {
        await using var app = App();
        await DrainAsync(app);
        var orderId = await InsertAsync(containers, "SENDING", sendingSince: DateTimeOffset.UtcNow.AddMinutes(-31));
        Fault(app, FakeFailMode.TimeoutBeforeCreate);

        await RunReconcilerAsync(app);

        Assert.Equal(ShipmentStatus.Sending, (await GetAsync(app, orderId)).Status); // belirsiz: FAILED değil
        Assert.Equal(1, ReadUncertainGauge(app));

        await RunReconcilerAsync(app); // hata planı bitti: açılır

        Assert.Equal(ShipmentStatus.Created, (await GetAsync(app, orderId)).Status);
        Assert.Equal(0, ReadUncertainGauge(app));
    }

    /// <summary>Bu uygulamanın (IMeterFactory'sinin) cargo_shipments_uncertain göstergesini okur.</summary>
    private static long ReadUncertainGauge(TestApp app)
    {
        var factory = app.Services.GetRequiredService<IMeterFactory>();
        long? value = null;
        using var listener = new MeterListener();
        listener.InstrumentPublished = (instrument, l) =>
        {
            if (instrument.Meter.Name == ShipmentReconciler.MeterName
                && instrument.Name == ShipmentReconciler.UncertainMetric
                && ReferenceEquals(instrument.Meter.Scope, factory))
            {
                l.EnableMeasurementEvents(instrument);
            }
        };
        listener.SetMeasurementEventCallback<long>((_, measurement, _, _) => value = measurement);
        listener.Start();
        listener.RecordObservableInstruments();
        return value ?? -1;
    }
}
