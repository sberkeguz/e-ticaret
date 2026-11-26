using System.Net.Http.Json;
using System.Text.Json;
using CargoService.Configuration;
using CargoService.Messaging;
using CargoService.Messaging.Contracts;
using CargoService.Shipping;
using CargoService.Tests.Infrastructure;
using Microsoft.Extensions.DependencyInjection;

namespace CargoService.Tests;

[Collection("Integration")]
public sealed class ShipmentIntakeTests(ContainersFixture containers)
{
    private const string UnreachableDb =
        "Host=127.0.0.1;Port=1;Database=eticaret;Username=cargo_app;Password=x;Timeout=3";

    // Alıcı bilgisi: log testinde bu değerlerin hiçbir satırda geçmediği aranır.
    private const string FirstName = "Zeynepgizli";
    private const string LastName = "Kargoalıcısoyadı";
    private const string Phone = "05559871234";
    private const string Address = "Gizlisokak No 77 Daire 9";

    private static long NewOrderId() => Random.Shared.NextInt64(1_000_000, long.MaxValue / 2);

    private static ShipmentRequested Command(long orderId, int requestNo = 1, string paymentStatus = "PAID",
        string? messageId = null) => new()
    {
        MessageId = messageId ?? Guid.NewGuid().ToString(),
        Type = "ShipmentRequested",
        Version = 1,
        OccurredAt = DateTimeOffset.UtcNow,
        TraceId = "4bf92f3577b34da6a3ce929d0e0e4736",
        OrderId = orderId,
        RequestNo = requestNo,
        IdempotencyKey = $"order-{orderId}",
        PaymentStatus = paymentStatus,
        Recipient = new Recipient
        {
            FirstName = FirstName,
            LastName = LastName,
            Phone = Phone,
            City = "İstanbul",
            District = "Kadıköy",
            Neighborhood = "Moda",
            Address = Address,
        },
        Parcel = new Parcel { ItemCount = 2, DeclaredValue = 149.90m },
    };

    private static async Task<IntakeOutcome> HandleAsync(TestApp app, ShipmentRequested command)
    {
        await using var scope = app.Services.CreateAsyncScope();
        return await scope.ServiceProvider.GetRequiredService<ShipmentIntake>()
            .HandleAsync(command, TestContext.Current.CancellationToken);
    }

    private Task<long> ShipmentCount(long orderId) =>
        Db.ScalarAsync<long>(containers, "SELECT count(*) FROM cargo.shipments WHERE order_id = $1", orderId);

    private Task<string?> Status(long orderId) =>
        Db.ScalarAsync<string>(containers, "SELECT status FROM cargo.shipments WHERE order_id = $1", orderId);

    [Fact]
    public async Task Ödenmiş_sipariş_REQUESTED_olarak_kaydedilir_alıcı_saklanır()
    {
        await using var app = TestApp.For(containers);
        var orderId = NewOrderId();

        Assert.Equal(IntakeOutcome.Requested, await HandleAsync(app, Command(orderId)));

        Assert.Equal("REQUESTED", await Status(orderId));
        Assert.Equal(1, await Db.ScalarAsync<int>(containers, "SELECT request_no FROM cargo.shipments WHERE order_id = $1", orderId));
        Assert.Equal(Phone, await Db.ScalarAsync<string>(containers, "SELECT recipient->>'phone' FROM cargo.shipments WHERE order_id = $1", orderId));
        Assert.Equal($"order-{orderId}", await Db.ScalarAsync<string>(containers, "SELECT idempotency_key FROM cargo.shipments WHERE order_id = $1", orderId));
    }

    [Fact]
    public async Task Ödenmiş_siparişin_koli_bilgisi_firmaya_iletilmek_üzere_saklanır_ödenmemişte_saklanmaz()
    {
        await using var app = TestApp.For(containers);
        var paid = NewOrderId();
        var unpaid = NewOrderId();

        await HandleAsync(app, Command(paid));
        await HandleAsync(app, Command(unpaid, paymentStatus: "PAYMENT_REVIEW"));

        Assert.Equal(2, await Db.ScalarAsync<int>(containers, "SELECT (parcel->>'itemCount')::int FROM cargo.shipments WHERE order_id = $1", paid));
        Assert.Equal(149.90m, await Db.ScalarAsync<decimal>(containers, "SELECT (parcel->>'declaredValue')::numeric FROM cargo.shipments WHERE order_id = $1", paid));
        Assert.True(await Db.ScalarAsync<bool>(containers, "SELECT parcel IS NULL FROM cargo.shipments WHERE order_id = $1", unpaid));
    }

    [Fact]
    public async Task Aynı_messageId_iki_kez_gelirse_tek_gönderi_oluşur()
    {
        await using var app = TestApp.For(containers);
        var orderId = NewOrderId();
        var command = Command(orderId);

        Assert.Equal(IntakeOutcome.Requested, await HandleAsync(app, command));
        Assert.Equal(IntakeOutcome.Duplicate, await HandleAsync(app, command));

        Assert.Equal(1, await ShipmentCount(orderId));
        Assert.Equal(1, await Db.ScalarAsync<long>(containers,
            "SELECT count(*) FROM cargo.processed_messages WHERE message_id = $1", command.MessageId));
    }

    [Fact]
    public async Task Aynı_sipariş_için_farklı_messageIdli_iki_komut_paralel_gelirse_tek_satır_oluşur()
    {
        await using var app = TestApp.For(containers);
        var orders = Enumerable.Range(0, 10).Select(_ => NewOrderId()).ToList();

        var results = await Task.WhenAll(orders.SelectMany(orderId => new[]
        {
            HandleAsync(app, Command(orderId)), HandleAsync(app, Command(orderId)),
        }));

        foreach (var orderId in orders)
        {
            Assert.Equal(1, await ShipmentCount(orderId));
        }
        Assert.Equal(orders.Count, results.Count(r => r == IntakeOutcome.Requested));
        Assert.Equal(orders.Count, results.Count(r => r == IntakeOutcome.Ignored));
    }

    [Fact]
    public async Task Ödemesi_onaylanmamış_sipariş_REJECTED_olur_shipment_failed_olayı_yazılır_firma_çağrılmaz()
    {
        await using var app = TestApp.For(containers);
        var orderId = NewOrderId();

        Assert.Equal(IntakeOutcome.Rejected, await HandleAsync(app, Command(orderId, paymentStatus: "PAYMENT_REVIEW")));

        Assert.Equal("REJECTED", await Status(orderId));
        Assert.Null(await Db.ScalarAsync<string>(containers, "SELECT recipient::text FROM cargo.shipments WHERE order_id = $1", orderId));
        Assert.Equal(0, await Db.ScalarAsync<long>(containers,
            "SELECT count(*) FROM cargo.fake_carrier_shipments WHERE idempotency_key = $1", $"order-{orderId}"));

        var payload = await Db.ScalarAsync<string>(containers,
            "SELECT payload::text FROM cargo.outbox_messages WHERE routing_key = 'shipment.failed' AND (payload->'data'->>'orderId')::bigint = $1",
            orderId);
        Assert.NotNull(payload);
        using var doc = JsonDocument.Parse(payload);
        var root = doc.RootElement;
        Assert.Equal("ShipmentFailed", root.GetProperty("type").GetString());
        Assert.Equal(1, root.GetProperty("version").GetInt32());
        Assert.Equal(1, root.GetProperty("sequence").GetInt32());
        Assert.Equal("4bf92f3577b34da6a3ce929d0e0e4736", root.GetProperty("traceId").GetString());
        var data = root.GetProperty("data");
        Assert.Equal("REJECTED", data.GetProperty("status").GetString());
        Assert.Equal("Ödemesi onaylanmamış sipariş kargoya verilemez.", data.GetProperty("failureReason").GetString());
        Assert.Equal(1, data.GetProperty("requestNo").GetInt32());
        var outboxId = await Db.ScalarAsync<Guid>(containers,
            "SELECT id FROM cargo.outbox_messages WHERE routing_key = 'shipment.failed' AND (payload->'data'->>'orderId')::bigint = $1",
            orderId);
        Assert.Equal(outboxId.ToString(), root.GetProperty("messageId").GetString());
        // Olay kişisel veri taşımaz.
        Assert.DoesNotContain(FirstName, payload);
        Assert.DoesNotContain(Phone, payload);
        Assert.DoesNotContain(Address, payload);
    }

    [Fact]
    public async Task Eski_requestNo_FAILED_satırı_yeniden_açmaz_yeni_requestNo_açar()
    {
        await using var app = TestApp.For(containers);
        var orderId = NewOrderId();
        await HandleAsync(app, Command(orderId, requestNo: 1));
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.shipments SET status = 'FAILED', failure_reason = 'test', recipient = NULL WHERE order_id = $1", orderId);

        Assert.Equal(IntakeOutcome.Ignored, await HandleAsync(app, Command(orderId, requestNo: 1)));
        Assert.Equal("FAILED", await Status(orderId));

        Assert.Equal(IntakeOutcome.Reopened, await HandleAsync(app, Command(orderId, requestNo: 2)));
        Assert.Equal("REQUESTED", await Status(orderId));
        Assert.Equal(2, await Db.ScalarAsync<int>(containers, "SELECT request_no FROM cargo.shipments WHERE order_id = $1", orderId));
        Assert.Null(await Db.ScalarAsync<string>(containers, "SELECT failure_reason FROM cargo.shipments WHERE order_id = $1", orderId));
        Assert.Equal(Phone, await Db.ScalarAsync<string>(containers, "SELECT recipient->>'phone' FROM cargo.shipments WHERE order_id = $1", orderId));
    }

    [Fact]
    public async Task Yeni_requestNo_REQUESTED_ya_da_CREATED_satırı_değiştirmez()
    {
        await using var app = TestApp.For(containers);
        var orderId = NewOrderId();
        await HandleAsync(app, Command(orderId, requestNo: 1));

        Assert.Equal(IntakeOutcome.Ignored, await HandleAsync(app, Command(orderId, requestNo: 2)));
        Assert.Equal(1, await Db.ScalarAsync<int>(containers, "SELECT request_no FROM cargo.shipments WHERE order_id = $1", orderId));
    }

    [Fact]
    public async Task Bozuk_ya_da_eksik_alanlı_mesaj_DLQya_düşer()
    {
        await using var app = TestApp.For(containers);
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        var notJson = Guid.NewGuid().ToString();
        var missingFields = Guid.NewGuid().ToString();
        var missing = JsonSerializer.Serialize(Command(NewOrderId()) with { Recipient = null }, JsonSerializerOptions.Web);
        await rabbit.PublishCommandAsync("{bu json değil", notJson);
        await rabbit.PublishCommandAsync(missing, missingFields);

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(TestContext.Current.CancellationToken);
        var consuming = Consumer(app).ConsumeAsync(cts.Token);
        try
        {
            Assert.NotNull(await rabbit.FindAsync(CargoTopology.ShipmentRequestsDeadLetterQueue, notJson, TimeSpan.FromSeconds(15)));
            Assert.NotNull(await rabbit.FindAsync(CargoTopology.ShipmentRequestsDeadLetterQueue, missingFields, TimeSpan.FromSeconds(15)));
        }
        finally
        {
            await cts.CancelAsync();
            await consuming;
        }
    }

    [Fact]
    public async Task Geçerli_komut_kuyruktan_işlenir_ve_onaylanır()
    {
        await using var app = TestApp.For(containers);
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        var orderId = NewOrderId();
        var command = Command(orderId);
        await rabbit.PublishCommandAsync(JsonSerializer.Serialize(command, JsonSerializerOptions.Web), command.MessageId!);

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(TestContext.Current.CancellationToken);
        var consuming = Consumer(app).ConsumeAsync(cts.Token);
        try
        {
            for (var i = 0; i < 100 && await ShipmentCount(orderId) == 0; i++)
            {
                await Task.Delay(100, TestContext.Current.CancellationToken);
            }
        }
        finally
        {
            await cts.CancelAsync();
            await consuming;
        }

        Assert.Equal("REQUESTED", await Status(orderId));
        Assert.Null(await rabbit.FindAsync(CargoTopology.ShipmentRequestsQueue, command.MessageId!, TimeSpan.FromSeconds(1)));
    }

    [Fact]
    public async Task Veritabanına_ulaşılamazken_mesaj_kuyrukta_kalır_DLQya_düşmez()
    {
        await using var app = TestApp.For(containers, new Dictionary<string, string?>
        {
            ["ConnectionStrings:Cargo"] = UnreachableDb,
            ["Database:MigrateOnStartup"] = "false",
        });
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        var command = Command(NewOrderId());
        await rabbit.PublishCommandAsync(JsonSerializer.Serialize(command, JsonSerializerOptions.Web), command.MessageId!);

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(TestContext.Current.CancellationToken);
        var consuming = Consumer(app).ConsumeAsync(cts.Token);
        // Bir işleme denemesi + 5 sn bekleme + nack(requeue) için yeterli süre.
        await Task.Delay(TimeSpan.FromSeconds(7), TestContext.Current.CancellationToken);
        await cts.CancelAsync();
        await consuming;

        var message = await rabbit.FindAsync(CargoTopology.ShipmentRequestsQueue, command.MessageId!, TimeSpan.FromSeconds(5));
        Assert.NotNull(message);
        Assert.True(message.Redelivered); // tüketici aldı, işleyemedi, kuyruğa geri koydu
        Assert.Null(await rabbit.FindAsync(CargoTopology.ShipmentRequestsDeadLetterQueue, command.MessageId!, TimeSpan.FromSeconds(1)));
    }

    [Fact]
    public async Task Alıcı_bilgisi_ve_firmaya_giden_gövde_intake_worker_takip_akışında_hiçbir_log_satırına_düşmez()
    {
        var logs = new LogCapture();
        await using var app = TestApp.For(containers, Shipments.FastSettings(), logs);
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        var ok = Command(NewOrderId());
        var rejected = Command(NewOrderId(), paymentStatus: "PAYMENT_REVIEW");
        var broken = Command(NewOrderId()) with { Parcel = null };
        foreach (var command in new[] { ok, rejected, broken })
        {
            await rabbit.PublishCommandAsync(JsonSerializer.Serialize(command, JsonSerializerOptions.Web), command.MessageId!);
        }
        // Kayıt ToString'leri de alıcıyı yazmamalı (biri yanlışlıkla loglarsa).
        Assert.Equal("[gizli]", ok.Recipient!.ToString());
        Assert.DoesNotContain(Phone, ok.ToString());

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(TestContext.Current.CancellationToken);
        var consuming = Consumer(app).ConsumeAsync(cts.Token);
        try
        {
            Assert.NotNull(await rabbit.FindAsync(CargoTopology.ShipmentRequestsDeadLetterQueue, broken.MessageId!, TimeSpan.FromSeconds(15)));
            for (var i = 0; i < 100 && (await ShipmentCount(ok.OrderId) == 0 || await ShipmentCount(rejected.OrderId) == 0); i++)
            {
                await Task.Delay(100, TestContext.Current.CancellationToken);
            }
        }
        finally
        {
            await cts.CancelAsync();
            await consuming;
        }
        await app.Services.GetRequiredService<OutboxRelay>().RunOnceAsync(TestContext.Current.CancellationToken);

        // Worker gönderiyi firmada açar (önce bir belirsiz yanıt: reconciler de çalışsın), sonra takip
        // sorgusu test ucuyla IN_TRANSIT ve DELIVERED'ı alır.
        Shipments.Fault(app, FakeFailMode.TimeoutAfterCreate, times: 1);
        await Shipments.RunWorkerAsync(app);
        await Shipments.RunReconcilerAsync(app);
        var trackingNumber = await Db.ScalarAsync<string>(containers,
            "SELECT tracking_number FROM cargo.shipments WHERE order_id = $1", ok.OrderId);
        Assert.NotNull(trackingNumber);
        using var client = Shipments.Client(app);
        foreach (var status in new[] { "IN_TRANSIT", "DELIVERED" })
        {
            var response = await client.PostAsJsonAsync($"/test/fake-carrier/shipments/{trackingNumber}/status",
                new { status }, TestContext.Current.CancellationToken);
            Assert.Equal(System.Net.HttpStatusCode.NoContent, response.StatusCode);
        }
        await Shipments.RunPollerAsync(app);
        await app.Services.GetRequiredService<OutboxRelay>().RunOnceAsync(TestContext.Current.CancellationToken);
        Assert.Equal("DELIVERED", await Status(ok.OrderId));

        Assert.Equal(1, await ShipmentCount(ok.OrderId));
        Assert.NotEmpty(logs.Lines);
        Assert.Contains(logs.Lines, l => l.Contains("Microsoft.EntityFrameworkCore.Database.Command")); // SQL logları da yakalandı
        Assert.Contains(logs.Lines, l => l.Contains("CargoService.Shipping.ShipmentWorker"));
        Assert.Contains(logs.Lines, l => l.Contains("CargoService.Shipping.ShipmentReconciler"));
        Assert.Contains(logs.Lines, l => l.Contains("CargoService.Shipping.TrackingPoller"));
        var secrets = new[]
        {
            FirstName, LastName, Phone, Address,
            Shipments.FirstName, Shipments.LastName, Shipments.Phone, Shipments.Address,
            // Firmaya giden gövde (alıcı + koli JSON'u) da loglanmaz. Bu anahtarlar yalnızca gövdede geçer;
            // "recipient"/"parcel" sütun adı olarak EF'in Debug sorgu loglarında zaten bulunduğu için aranmaz.
            "149.9", "itemCount", "declaredValue", "firstName", "neighborhood",
        };
        foreach (var secret in secrets)
        {
            var leaked = logs.Lines.FirstOrDefault(l => l.Contains(secret, StringComparison.OrdinalIgnoreCase));
            Assert.True(leaked is null, $"'{secret}' log satırında geçiyor: {leaked}");
        }
    }

    private static ShipmentRequestsConsumer Consumer(TestApp app) =>
        app.Services.GetRequiredService<ShipmentRequestsConsumer>();
}
