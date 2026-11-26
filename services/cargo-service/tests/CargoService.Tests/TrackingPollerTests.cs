using System.Net;
using System.Net.Http.Json;
using CargoService.Carriers.Fake;
using CargoService.Data;
using CargoService.Messaging;
using CargoService.Tests.Infrastructure;
using Microsoft.Extensions.DependencyInjection;
using static CargoService.Tests.Infrastructure.Shipments;

namespace CargoService.Tests;

[Collection("Integration")]
public sealed class TrackingPollerTests(ContainersFixture containers)
{
    private TestApp App(LogCapture? logs = null) => TestApp.For(containers, FastSettings(), logs);

    /// <summary>Yeni bir gönderiyi firmada açtırır (CREATED); sipariş no ve takip no döner.</summary>
    private static async Task<(long OrderId, string TrackingNumber)> CreatedAsync(TestApp app)
    {
        await DrainAsync(app);
        var orderId = await RequestAsync(app);
        await RunWorkerAsync(app);
        var shipment = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.Created, shipment.Status);
        return (orderId, shipment.TrackingNumber!);
    }

    private static async Task SetStatusViaEndpointAsync(HttpClient client, string trackingNumber, string status)
    {
        var response = await client.PostAsJsonAsync($"/test/fake-carrier/shipments/{trackingNumber}/status", new { status }, Ct);
        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
    }

    [Fact]
    public async Task Test_ucuyla_değişen_durum_hemen_sorgulanır_olaylar_sırayla_ve_artan_sequence_ile_çıkar()
    {
        await using var app = App();
        var (orderId, trackingNumber) = await CreatedAsync(app);
        using var client = Client(app);

        await SetStatusViaEndpointAsync(client, trackingNumber, "IN_TRANSIT");

        var inTransit = await GetAsync(app, orderId); // periyodik tur beklenmeden güncellendi
        Assert.Equal(ShipmentStatus.InTransit, inTransit.Status);
        Assert.NotNull(inTransit.InTransitAt);
        Assert.NotNull(inTransit.LastPolledAt);

        await SetStatusViaEndpointAsync(client, trackingNumber, "DELIVERED");

        var delivered = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.Delivered, delivered.Status);
        Assert.NotNull(delivered.DeliveredAt);
        Assert.Equal(3, delivered.EventSequence);
        var events = await EventsAsync(containers, orderId);
        Assert.Equal(
            [CargoTopology.ShipmentCreated, CargoTopology.ShipmentInTransit, CargoTopology.ShipmentDelivered],
            events.Select(e => e.RoutingKey));
        Assert.Equal([1, 2, 3], events.Select(e => e.Payload.GetProperty("sequence").GetInt32()));
        Assert.Equal(["CREATED", "IN_TRANSIT", "DELIVERED"],
            events.Select(e => e.Payload.GetProperty("data").GetProperty("status").GetString()));
        Assert.All(events, e => Assert.Equal(trackingNumber, e.Payload.GetProperty("data").GetProperty("trackingNumber").GetString()));
    }

    [Fact]
    public async Task Periyodik_tur_firmadaki_değişikliği_alır_ve_last_polled_at_günceller()
    {
        await using var app = App();
        var (orderId, trackingNumber) = await CreatedAsync(app);
        // Test ucunu değil firmayı doğrudan değiştirir: yalnızca periyodik tur görebilir.
        Assert.True(await app.Services.GetRequiredService<FakeCarrierGateway>()
            .SetStatusAsync(trackingNumber, ShipmentStatus.DeliveryFailed, Ct));
        Assert.Equal(ShipmentStatus.Created, (await GetAsync(app, orderId)).Status);

        Assert.True(await RunPollerAsync(app) >= 1);

        var shipment = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.DeliveryFailed, shipment.Status);
        Assert.NotNull(shipment.DeliveryFailedAt);
        Assert.NotNull(shipment.LastPolledAt);
        Assert.Equal(CargoTopology.ShipmentDeliveryFailed, (await EventsAsync(containers, orderId))[^1].RoutingKey);

        // DELIVERY_FAILED → IN_TRANSIT izinlidir (yeniden dağıtıma çıktı).
        using var client = Client(app);
        await SetStatusViaEndpointAsync(client, trackingNumber, "IN_TRANSIT");
        Assert.Equal(ShipmentStatus.InTransit, (await GetAsync(app, orderId)).Status);
        Assert.Equal(3, (await GetAsync(app, orderId)).EventSequence);
    }

    [Fact]
    public async Task DELIVERED_son_durumdur_firma_geri_bir_durum_söylese_de_dönülmez()
    {
        var logs = new LogCapture();
        await using var app = App(logs);
        var (orderId, trackingNumber) = await CreatedAsync(app);
        using var client = Client(app);
        await SetStatusViaEndpointAsync(client, trackingNumber, "DELIVERED");
        Assert.Equal(ShipmentStatus.Delivered, (await GetAsync(app, orderId)).Status);

        await SetStatusViaEndpointAsync(client, trackingNumber, "IN_TRANSIT");
        await RunPollerAsync(app);

        var shipment = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.Delivered, shipment.Status);
        Assert.Equal(2, shipment.EventSequence);
        Assert.Equal(2, (await EventsAsync(containers, orderId)).Count);
        Assert.Contains(logs.Lines, l => l.StartsWith("Warning") && l.Contains(trackingNumber)
            && l.Contains("DELIVERED") && l.Contains("IN_TRANSIT") && l.Contains("izin verilmeyen"));
    }

    [Fact]
    public async Task İzin_verilmeyen_geçiş_yok_sayılır_ve_loglanır()
    {
        var logs = new LogCapture();
        await using var app = App(logs);
        var (orderId, trackingNumber) = await CreatedAsync(app);
        using var client = Client(app);
        await SetStatusViaEndpointAsync(client, trackingNumber, "IN_TRANSIT");
        // Firma IN_TRANSIT'ten CREATED'a döndüğünü söylüyor: izinli değil.
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.fake_carrier_shipments SET status = 'CREATED' WHERE tracking_number = $1", trackingNumber);

        await RunPollerAsync(app);

        var shipment = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.InTransit, shipment.Status);
        Assert.Equal(2, shipment.EventSequence);
        Assert.Contains(logs.Lines, l => l.StartsWith("Warning") && l.Contains(trackingNumber)
            && l.Contains("IN_TRANSIT") && l.Contains("CREATED") && l.Contains("izin verilmeyen"));
    }
}
