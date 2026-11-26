using System.Text.Json;
using CargoService.Carriers.Fake;
using CargoService.Configuration;
using CargoService.Data;
using CargoService.Messaging.Contracts;
using CargoService.Shipping;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Npgsql;

namespace CargoService.Tests.Infrastructure;

/// <summary>
/// Gönderi akışı testlerinin ortak yardımcıları: komut üretme, intake, arka plan işlerini elle
/// çalıştırma ve gönderi/olay satırlarını okuma. Testler "Integration" koleksiyonunda sırayla çalışır;
/// önceki testlerden kalan gönderiler de işlenebileceği için her test yalnızca kendi siparişine bakar.
/// </summary>
public static class Shipments
{
    public const string Token = "test-internal-token-k5";

    // Alıcı bilgisi: log testlerinde bu değerlerin hiçbir satırda geçmediği aranır.
    public const string FirstName = "Mehmetgizliad";
    public const string LastName = "Kargogizlisoyad";
    public const string Phone = "05551112299";
    public const string Address = "Saklisokak No 41 Daire 3";

    public static CancellationToken Ct => TestContext.Current.CancellationToken;

    public static long NewOrderId() => Random.Shared.NextInt64(1_000_000, long.MaxValue / 2);

    /// <summary>
    /// Hızlı test ayarları: geri çekilme 0, SENDING kayıtları hemen "takılı" sayılır, takip sorgusu
    /// aralığı 0; sahte firma test uçları açık. <paramref name="extra"/> bunların üzerine yazar.
    /// </summary>
    public static Dictionary<string, string?> FastSettings(Dictionary<string, string?>? extra = null)
    {
        var settings = new Dictionary<string, string?>
        {
            ["Workers:RetryBaseDelay"] = "00:00:00",
            ["Workers:SendingStuckAfter"] = "00:00:00",
            ["Workers:PollInterval"] = "00:00:00",
            ["Carrier:Fake:TestEndpointsEnabled"] = "true",
            ["InternalApiToken"] = Token,
        };
        foreach (var (key, value) in extra ?? [])
        {
            settings[key] = value;
        }
        return settings;
    }

    public static Recipient Recipient() => new()
    {
        FirstName = FirstName,
        LastName = LastName,
        Phone = Phone,
        City = "İzmir",
        District = "Karşıyaka",
        Neighborhood = "Bostanlı",
        Address = Address,
    };

    public static ShipmentRequested Command(long orderId, int requestNo = 1, string paymentStatus = "PAID") => new()
    {
        MessageId = Guid.NewGuid().ToString(),
        Type = "ShipmentRequested",
        Version = 1,
        OccurredAt = DateTimeOffset.UtcNow,
        TraceId = "4bf92f3577b34da6a3ce929d0e0e4736",
        OrderId = orderId,
        RequestNo = requestNo,
        IdempotencyKey = $"order-{orderId}",
        PaymentStatus = paymentStatus,
        Recipient = Recipient(),
        Parcel = new Parcel { ItemCount = 3, DeclaredValue = 271.35m },
    };

    public static async Task<IntakeOutcome> IntakeAsync(TestApp app, ShipmentRequested command)
    {
        await using var scope = app.Services.CreateAsyncScope();
        return await scope.ServiceProvider.GetRequiredService<ShipmentIntake>().HandleAsync(command, Ct);
    }

    /// <summary>Yeni bir ödenmiş sipariş için REQUESTED gönderi açar; sipariş numarasını döner.</summary>
    public static async Task<long> RequestAsync(TestApp app)
    {
        var orderId = NewOrderId();
        Assert.Equal(IntakeOutcome.Requested, await IntakeAsync(app, Command(orderId)));
        return orderId;
    }

    public static Task<int> RunWorkerAsync(TestApp app) => app.Services.GetRequiredService<ShipmentWorker>().RunOnceAsync(Ct);

    public static Task<int> RunReconcilerAsync(TestApp app) => app.Services.GetRequiredService<ShipmentReconciler>().RunOnceAsync(Ct);

    public static Task<int> RunPollerAsync(TestApp app) => app.Services.GetRequiredService<TrackingPoller>().RunOnceAsync(Ct);

    /// <summary>
    /// Önceki testlerden kalan REQUESTED/SENDING gönderileri hata planı yokken bitirir; böylece testin
    /// kurduğu sahte firma hatası başka bir gönderiye değil, testin kendi gönderisine uygulanır.
    /// </summary>
    public static async Task DrainAsync(TestApp app)
    {
        app.Services.GetRequiredService<FakeCarrierFaults>().Set(FakeFailMode.None, 0);
        await RunWorkerAsync(app);
        await RunReconcilerAsync(app);
    }

    public static void Fault(TestApp app, FakeFailMode mode, int times = 1) =>
        app.Services.GetRequiredService<FakeCarrierFaults>().Set(mode, times);

    public static async Task<Shipment> GetAsync(TestApp app, long orderId)
    {
        await using var scope = app.Services.CreateAsyncScope();
        return await scope.ServiceProvider.GetRequiredService<CargoDbContext>().Shipments.AsNoTracking()
            .SingleAsync(s => s.OrderId == orderId, Ct);
    }

    /// <summary>Siparişin outbox olayları, sequence sırasıyla: (routing key, payload JSON).</summary>
    public static async Task<List<(string RoutingKey, JsonElement Payload)>> EventsAsync(ContainersFixture containers, long orderId)
    {
        await using var conn = new NpgsqlConnection(containers.CargoConnectionString);
        await conn.OpenAsync(Ct);
        await using var cmd = new NpgsqlCommand(
            "SELECT routing_key, payload::text FROM cargo.outbox_messages " +
            "WHERE payload->'data'->>'orderId' = $1 ORDER BY (payload->>'sequence')::int", conn);
        cmd.Parameters.Add(new NpgsqlParameter { Value = orderId.ToString() });
        var events = new List<(string, JsonElement)>();
        await using var reader = await cmd.ExecuteReaderAsync(Ct);
        while (await reader.ReadAsync(Ct))
        {
            events.Add((reader.GetString(0), JsonDocument.Parse(reader.GetString(1)).RootElement.Clone()));
        }
        return events;
    }

    public static Task<long> FakeCarrierRowsAsync(ContainersFixture containers, long orderId) =>
        Db.ScalarAsync<long>(containers, "SELECT count(*) FROM cargo.fake_carrier_shipments WHERE idempotency_key = $1", $"order-{orderId}");

    /// <summary>
    /// Gönderi satırını doğrudan yazar (servis bir adımın ortasında ölmüş gibi). Alıcı ve koli dolu,
    /// <paramref name="sendingSince"/> ve <paramref name="deliveredAt"/> verilirse yazılır.
    /// </summary>
    public static async Task<long> InsertAsync(
        ContainersFixture containers, string status, DateTimeOffset? sendingSince = null, int attempts = 1,
        string? trackingNumber = null, DateTimeOffset? deliveredAt = null, int eventSequence = 0)
    {
        var orderId = NewOrderId();
        var recipient = JsonSerializer.Serialize(Recipient(), JsonSerializerOptions.Web);
        var parcel = JsonSerializer.Serialize(new Parcel { ItemCount = 1, DeclaredValue = 50m }, JsonSerializerOptions.Web);
        await Db.ExecuteAsync(containers,
            "INSERT INTO cargo.shipments (id, order_id, idempotency_key, request_no, status, recipient, parcel, " +
            "event_sequence, attempts, sending_since, tracking_number, delivered_at, created_at, updated_at) " +
            "VALUES ($1, $2, $3, 1, $4, $5::jsonb, $6::jsonb, $7, $8, $9, $10, $11, now(), now())",
            Guid.CreateVersion7(), orderId, $"order-{orderId}", status, recipient, parcel, eventSequence, attempts,
            sendingSince, trackingNumber, deliveredAt);
        return orderId;
    }

    public static HttpClient Client(TestApp app)
    {
        var client = app.CreateClient();
        client.DefaultRequestHeaders.Add(FakeCarrierEndpoints.TokenHeader, Token);
        return client;
    }
}
