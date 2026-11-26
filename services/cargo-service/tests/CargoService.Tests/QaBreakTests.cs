using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using CargoService.Carriers;
using CargoService.Carriers.Fake;
using CargoService.Configuration;
using CargoService.Data;
using CargoService.Messaging;
using CargoService.Shipping;
using CargoService.Tests.Infrastructure;
using Microsoft.Extensions.DependencyInjection;
using static CargoService.Tests.Infrastructure.Shipments;

namespace CargoService.Tests;

/// <summary>
/// QA (Tester) kırma testleri: zehirli mesajlar, eşzamanlılık, "Tekrar dene", yapılandırma, kişisel veri
/// saklama ve test uçları. Kırmızı olanlar hata raporundaki bulguları gösterir.
/// </summary>
[Collection("Integration")]
public sealed class QaPoisonMessageTests(ContainersFixture containers)
{
    private static string Json(object command) => JsonSerializer.Serialize(command, JsonSerializerOptions.Web);

    /// <summary>Test tetikleyicisinin veritabanı hatası ürettiği satırları işaretleyen metin.</summary>
    private const string DbPoisonMarker = "QA-DB-POISON";

    /// <summary>
    /// Geçerli görünen ama veritabanının her seferinde aynı şekilde reddettiği komut: jsonb "\u0000"
    /// kaçışını ve eşi olmayan vekil (surrogate) karakteri kabul etmez. Bu geçici bir hata değildir;
    /// komut DLQ'ya gitmeli, kuyrukta sonsuza kadar dönmemelidir.
    /// </summary>
    public static TheoryData<string> DeterministicDbFailures => new()
    {
        "Saklisokak\u0000 No 41",
        "Saklisokak \uD800 No 41",
    };

    [Theory]
    [MemberData(nameof(DeterministicDbFailures))]
    public async Task Veritabanının_hep_reddettiği_komut_DLQya_gider_kuyrukta_sonsuza_dek_dönmez(string address)
    {
        await using var app = TestApp.For(containers);
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        var command = Command(NewOrderId()) with { Recipient = Recipient() with { Address = address } };
        await rabbit.PublishCommandAsync(Json(command), command.MessageId!);

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(Ct);
        var consuming = app.Services.GetRequiredService<ShipmentRequestsConsumer>().ConsumeAsync(cts.Token);
        try
        {
            // 3 deneme (her biri 5 sn bekler) süresi: bir zehirli mesaj DLQ'ya düşmeli.
            Assert.NotNull(await rabbit.FindAsync(CargoTopology.ShipmentRequestsDeadLetterQueue, command.MessageId!,
                TimeSpan.FromSeconds(18)));
        }
        finally
        {
            await cts.CancelAsync();
            await consuming;
            await rabbit.DrainAsync(CargoTopology.ShipmentRequestsQueue); // döngüdeki mesaj diğer testleri bozmasın
        }
    }

    [Fact]
    public async Task Dört_zehirli_komut_arkasındaki_geçerli_komutu_sonsuza_dek_bekletmez()
    {
        await using var app = TestApp.For(containers);
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        for (var i = 0; i < 4; i++) // prefetch = 4
        {
            var poison = Command(NewOrderId()) with { Recipient = Recipient() with { Address = "x\u0000y" } };
            await rabbit.PublishCommandAsync(Json(poison), poison.MessageId!);
        }
        var valid = Command(NewOrderId());
        await rabbit.PublishCommandAsync(Json(valid), valid.MessageId!);

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(Ct);
        var consuming = app.Services.GetRequiredService<ShipmentRequestsConsumer>().ConsumeAsync(cts.Token);
        long count = 0;
        try
        {
            for (var i = 0; i < 200 && count == 0; i++)
            {
                await Task.Delay(100, Ct);
                count = await Db.ScalarAsync<long>(containers,
                    "SELECT count(*) FROM cargo.shipments WHERE order_id = $1", valid.OrderId);
            }
        }
        finally
        {
            await cts.CancelAsync();
            await consuming;
            await rabbit.DrainAsync(CargoTopology.ShipmentRequestsQueue);
        }
        Assert.True(count == 1, "Geçerli komut 20 sn içinde işlenmedi: zehirli mesajlar kuyruğu tıkadı.");
    }

    [Fact]
    public async Task Veritabanı_hatası_ve_bozuk_JSON_alıcı_verisini_loga_sızdırmaz()
    {
        var logs = new LogCapture();
        await using var app = TestApp.For(containers, logs: logs);
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        // 1) Veritabanı reddeder. "\u0000" artık doğrulamada reddedildiği için veritabanına ulaşmaz; aynı
        //    hatayı (jsonb 22P05, Where alanında alıcı JSON'u) işaretli satırlarda bir tetikleyici üretir.
        //    Npgsql/EF istisnası yine fırlar ve loglanmaya çalışılır.
        _ = app.Services; // uygulama açılsın: migration tetikleyiciden önce şemayı kursun
        await using (var admin = new Npgsql.NpgsqlConnection(containers.AdminConnectionString))
        {
            await admin.OpenAsync(Ct);
            await using var ddl = new Npgsql.NpgsqlCommand($$"""
                CREATE OR REPLACE FUNCTION cargo.qa_jsonb_poison() RETURNS trigger LANGUAGE plpgsql AS $f$
                BEGIN
                  IF NEW.recipient::text LIKE '%{{DbPoisonMarker}}%' THEN
                    PERFORM replace(NEW.recipient::text, '{{DbPoisonMarker}}', '\u0000')::jsonb;
                  END IF;
                  RETURN NEW;
                END $f$;
                DROP TRIGGER IF EXISTS qa_jsonb_poison ON cargo.shipments;
                CREATE TRIGGER qa_jsonb_poison BEFORE INSERT OR UPDATE ON cargo.shipments
                  FOR EACH ROW EXECUTE FUNCTION cargo.qa_jsonb_poison();
                """, admin);
            await ddl.ExecuteNonQueryAsync(Ct);
        }
        var dbPoison = Command(NewOrderId()) with { Recipient = Recipient() with { Address = Address + " " + DbPoisonMarker } };
        await rabbit.PublishCommandAsync(Json(dbPoison), dbPoison.MessageId!);
        // 2) Bozuk JSON: telefon numarası geçersiz sayı olarak (baştaki 0) gövdede.
        var brokenId = Guid.NewGuid().ToString();
        var broken = Json(Command(NewOrderId())).Replace($"\"phone\":\"{Phone}\"", $"\"phone\":{Phone}");
        Assert.Contains($"\"phone\":{Phone}", broken);
        await rabbit.PublishCommandAsync(broken, brokenId);

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(Ct);
        var consuming = app.Services.GetRequiredService<ShipmentRequestsConsumer>().ConsumeAsync(cts.Token);
        try
        {
            // İkisi de DLQ'ya düşmeli; kalıcı veri hatası (sınıf 22) DLQ'ya gittiğinde hata yolu loglanmıştır.
            // FindAsync eşleşmeyeni sildiği için ikisi birlikte toplanır.
            var expected = new[] { brokenId, dbPoison.MessageId! };
            var seen = new HashSet<string>();
            for (var deadline = DateTime.UtcNow.AddSeconds(15); DateTime.UtcNow < deadline && !expected.All(seen.Contains);)
            {
                foreach (var message in await rabbit.DrainAsync(CargoTopology.ShipmentRequestsDeadLetterQueue))
                {
                    seen.Add(message.BasicProperties.MessageId ?? "");
                }
                await Task.Delay(200, Ct);
            }
            Assert.Contains(brokenId, seen);
            Assert.Contains(dbPoison.MessageId!, seen);
        }
        finally
        {
            await cts.CancelAsync();
            await consuming;
            await rabbit.DrainAsync(CargoTopology.ShipmentRequestsQueue);
            await using var admin = new Npgsql.NpgsqlConnection(containers.AdminConnectionString);
            await admin.OpenAsync(CancellationToken.None);
            await using var drop = new Npgsql.NpgsqlCommand(
                "DROP TRIGGER IF EXISTS qa_jsonb_poison ON cargo.shipments; DROP FUNCTION IF EXISTS cargo.qa_jsonb_poison();", admin);
            await drop.ExecuteNonQueryAsync(CancellationToken.None);
        }

        Assert.Contains(logs.Lines, l => l.Contains("işlenemedi")); // veritabanı hatası yolu gerçekten çalıştı
        foreach (var secret in new[] { FirstName, LastName, Phone, Address, "Saklisokak" })
        {
            var leaked = logs.Lines.FirstOrDefault(l => l.Contains(secret, StringComparison.OrdinalIgnoreCase));
            Assert.True(leaked is null, $"'{secret}' log satırında geçiyor: {leaked}");
        }
    }

    [Fact]
    public async Task Sözleşme_dışı_komutlar_DLQya_gider_version_2_ve_çok_büyük_gövde_servisi_tıkamaz()
    {
        await using var app = TestApp.For(containers);
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);

        var dlqExpected = new Dictionary<string, string>();
        void Bad(string name, string body) => dlqExpected[Guid.NewGuid().ToString()] = body;

        var order = NewOrderId();
        Bad("orderId0", Json(Command(order) with { OrderId = 0, IdempotencyKey = "order-0" }));
        Bad("orderIdNeg", Json(Command(order) with { OrderId = -5, IdempotencyKey = "order--5" }));
        Bad("keyMismatch", Json(Command(order) with { IdempotencyKey = $"order-{order + 1}" }));
        Bad("noPhone", Json(Command(order) with { Recipient = Recipient() with { Phone = " " } }));
        Bad("noCity", Json(Command(order) with { Recipient = Recipient() with { City = null } }));
        Bad("requestNo0", Json(Command(order) with { RequestNo = 0 }));
        Bad("negativeValue", Json(Command(order) with { Parcel = new() { ItemCount = 1, DeclaredValue = -1m } }));
        var node = JsonNode.Parse(Json(Command(order)))!;
        node["parcel"]!["declaredValue"] = "abc";
        Bad("valueNotNumber", node.ToJsonString());
        node["parcel"]!["declaredValue"] = JsonValue.Create(1e300);
        Bad("valueOverflow", node.ToJsonString());
        Bad("arrayRoot", "[1,2,3]");
        Bad("nullRoot", "null");
        Bad("empty", "");
        Bad("deep", new string('[', 5000) + new string(']', 5000));

        foreach (var (id, body) in dlqExpected)
        {
            await rabbit.PublishCommandAsync(body, id);
        }
        var v2 = Command(NewOrderId()) with { Version = 2 };
        await rabbit.PublishCommandAsync(Json(v2), v2.MessageId!);
        var huge = Command(NewOrderId()) with { Recipient = Recipient() with { Address = new string('a', 3_000_000) } };
        await rabbit.PublishCommandAsync(Json(huge), huge.MessageId!);
        var after = Command(NewOrderId());
        await rabbit.PublishCommandAsync(Json(after), after.MessageId!);

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(Ct);
        var consuming = app.Services.GetRequiredService<ShipmentRequestsConsumer>().ConsumeAsync(cts.Token);
        var seen = new HashSet<string>();
        try
        {
            var deadline = DateTime.UtcNow.AddSeconds(20);
            while (DateTime.UtcNow < deadline && !dlqExpected.Keys.All(seen.Contains))
            {
                foreach (var message in await rabbit.DrainAsync(CargoTopology.ShipmentRequestsDeadLetterQueue))
                {
                    seen.Add(message.BasicProperties.MessageId ?? "");
                }
                await Task.Delay(200, Ct);
            }
            for (var i = 0; i < 100 && await Db.ScalarAsync<long>(containers,
                     "SELECT count(*) FROM cargo.shipments WHERE order_id = $1", after.OrderId) == 0; i++)
            {
                await Task.Delay(100, Ct);
            }
        }
        finally
        {
            await cts.CancelAsync();
            await consuming;
            await rabbit.DrainAsync(CargoTopology.ShipmentRequestsQueue);
        }

        var missing = dlqExpected.Keys.Where(id => !seen.Contains(id)).ToList();
        Assert.True(missing.Count == 0, $"DLQ'ya düşmeyen {missing.Count} geçersiz komut var");
        // Sürüm kuralı: alanlar yalnızca eklenir, version >= 1 kabul edilir.
        Assert.Equal("REQUESTED", await Db.ScalarAsync<string>(containers,
            "SELECT status FROM cargo.shipments WHERE order_id = $1", v2.OrderId));
        Assert.Equal("REQUESTED", await Db.ScalarAsync<string>(containers,
            "SELECT status FROM cargo.shipments WHERE order_id = $1", after.OrderId));
        // Büyük gövdeli gönderi işlendi ya da DLQ'ya gitti; ama arkasındakini tıkamadı. Sonraki testlerin
        // worker turlarında firmaya 3 MB'lık adres gitmesin.
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.shipments SET status = 'FAILED', recipient = NULL WHERE order_id = ANY($1)",
            new[] { v2.OrderId, huge.OrderId, after.OrderId });
    }
}

[Collection("Integration")]
public sealed class QaConcurrencyTests(ContainersFixture containers)
{
    private TestApp App(Dictionary<string, string?>? extra = null) => TestApp.For(containers, FastSettings(extra));

    [Fact]
    public async Task Aynı_sipariş_iki_komut_iki_worker_iki_reconciler_aynı_anda_tek_firma_kaydı_tek_created_olayı()
    {
        var slow = new Dictionary<string, string?> { ["Carrier:Fake:DelayMs"] = "80" };
        await using var a = App(slow);
        await using var b = App(slow);
        await DrainAsync(a);
        await DrainAsync(b);

        var orders = Enumerable.Range(0, 6).Select(_ => NewOrderId()).ToList();
        // Her sipariş için farklı messageId'li iki komut iki kopyaya aynı anda gelir.
        var intakes = await Task.WhenAll(orders.SelectMany(o => new[]
        {
            IntakeAsync(a, Command(o)),
            IntakeAsync(b, Command(o)),
        }));
        Assert.Equal(orders.Count, intakes.Count(r => r == IntakeOutcome.Requested));

        // Belirsiz sonuçlar da olsun: biri açtıktan sonra, biri açmadan önce zaman aşımı.
        Fault(a, FakeFailMode.TimeoutAfterCreate, times: 3);
        Fault(b, FakeFailMode.TimeoutBeforeCreate, times: 3);
        for (var round = 0; round < 4; round++)
        {
            await Task.WhenAll(
                RunWorkerAsync(a), RunWorkerAsync(b),
                RunReconcilerAsync(a), RunReconcilerAsync(b),
                RunReconcilerAsync(a), RunWorkerAsync(b));
        }
        Fault(a, FakeFailMode.None, 0);
        Fault(b, FakeFailMode.None, 0);
        for (var i = 0; i < 5; i++)
        {
            await Task.WhenAll(RunReconcilerAsync(a), RunReconcilerAsync(b));
        }

        foreach (var orderId in orders)
        {
            var shipment = await GetAsync(a, orderId);
            Assert.Equal(ShipmentStatus.Created, shipment.Status);
            Assert.Equal(1, await FakeCarrierRowsAsync(containers, orderId));
            var events = await EventsAsync(containers, orderId);
            Assert.Single(events, e => e.RoutingKey == CargoTopology.ShipmentCreated);
            Assert.Equal(events.Count, events.Select(e => e.Payload.GetProperty("sequence").GetInt32()).Distinct().Count());
        }
    }

    /// <summary>
    /// ABA: worker A sahiplenir (attempts=1) ama sonucu gecikir; reconciler devralır, firma kesin reddeder
    /// (FAILED); "Tekrar dene" (requestNo 2) attempts'ı 0'a çeker; worker B sahiplenir (attempts=1). A'nın
    /// geç gelen eski sonucu attempts eşit olduğu için uygulanır. Satırın xmin'i farklıdır.
    /// </summary>
    [Fact]
    public async Task Yeniden_açılan_gönderide_eski_sahiplenmenin_geç_gelen_sonucu_uygulanmaz()
    {
        await using var app = App();
        var orderId = await RequestAsync(app);

        // Worker A'nın sahiplenmesi (T2 ile aynı UPDATE).
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.shipments SET status = 'SENDING', attempts = attempts + 1, sending_since = now() WHERE order_id = $1", orderId);
        var staleClaim = await GetAsync(app, orderId);
        Assert.Equal(1, staleClaim.Attempts);

        // Reconciler devraldı, firma kesin reddetti → FAILED.
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.shipments SET status = 'FAILED', attempts = attempts + 1, failure_reason = 'ret', sending_since = NULL WHERE order_id = $1", orderId);
        // "Tekrar dene".
        Assert.Equal(IntakeOutcome.Reopened, await IntakeAsync(app, Command(orderId, requestNo: 2)));
        // Worker B sahiplendi (firma çağrısı sürüyor).
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.shipments SET status = 'SENDING', attempts = attempts + 1, sending_since = now() WHERE order_id = $1", orderId);
        Assert.Equal(1, (await GetAsync(app, orderId)).Attempts);

        // A'nın eski (geç gelen) sonucu.
        var outcomes = app.Services.GetRequiredService<ShipmentOutcomes>();
        var gateway = app.Services.GetRequiredService<ICarrierGateway>();
        var applied = await outcomes.ApplyCreateResultAsync(
            staleClaim, new CarrierCreateResult.PermanentFailure("eski sahiplenmenin sonucu"), gateway, Ct);

        try
        {
            Assert.Null(applied);
            Assert.Equal(ShipmentStatus.Sending, (await GetAsync(app, orderId)).Status);
        }
        finally
        {
            await Db.ExecuteAsync(containers,
                "UPDATE cargo.shipments SET status = 'FAILED', recipient = NULL WHERE order_id = $1", orderId);
        }
    }
}

[Collection("Integration")]
public sealed class QaRetryFlowTests(ContainersFixture containers)
{
    private TestApp App() => TestApp.For(containers, FastSettings());

    [Fact]
    public async Task REJECTED_sonra_yeni_requestNo_PAID_gönderi_açılır_eski_requestNo_geç_gelirse_değişmez()
    {
        await using var app = App();
        await DrainAsync(app);
        var orderId = NewOrderId();
        Assert.Equal(IntakeOutcome.Rejected, await IntakeAsync(app, Command(orderId, 1, "PAYMENT_REVIEW")));
        Assert.Equal(IntakeOutcome.Reopened, await IntakeAsync(app, Command(orderId, 2)));
        await RunWorkerAsync(app);
        var shipment = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.Created, shipment.Status);
        Assert.Equal(2, shipment.RequestNo);

        // Eski requestNo'nun geç gelen kopyaları (yeni messageId'lerle) hiçbir şeyi değiştirmez.
        Assert.Equal(IntakeOutcome.Ignored, await IntakeAsync(app, Command(orderId, 1, "PAYMENT_REVIEW")));
        Assert.Equal(IntakeOutcome.Ignored, await IntakeAsync(app, Command(orderId, 1)));
        Assert.Equal(IntakeOutcome.Ignored, await IntakeAsync(app, Command(orderId, 2)));

        var events = await EventsAsync(containers, orderId);
        Assert.Equal([CargoTopology.ShipmentFailed, CargoTopology.ShipmentCreated], events.Select(e => e.RoutingKey));
        Assert.Equal([1, 2], events.Select(e => e.Payload.GetProperty("sequence").GetInt32()));
        Assert.Equal([1, 2], events.Select(e => e.Payload.GetProperty("data").GetProperty("requestNo").GetInt32()));
        Assert.Equal(1, await FakeCarrierRowsAsync(containers, orderId));
    }

    [Fact]
    public async Task REJECTED_sonra_yeni_requestNo_hâlâ_ödenmemiş_REJECTED_kalır_alıcı_saklanmaz_firma_çağrılmaz()
    {
        await using var app = App();
        var orderId = NewOrderId();
        Assert.Equal(IntakeOutcome.Rejected, await IntakeAsync(app, Command(orderId, 1, "PAYMENT_REVIEW")));
        Assert.Equal(IntakeOutcome.Rejected, await IntakeAsync(app, Command(orderId, 2, "PENDING")));
        Assert.Equal(IntakeOutcome.Ignored, await IntakeAsync(app, Command(orderId, 1))); // geç gelen eski PAID

        await RunWorkerAsync(app);
        var shipment = await GetAsync(app, orderId);
        Assert.Equal(ShipmentStatus.Rejected, shipment.Status);
        Assert.Equal(2, shipment.RequestNo);
        Assert.Null(shipment.Recipient);
        Assert.Equal(0, await FakeCarrierRowsAsync(containers, orderId));
        var events = await EventsAsync(containers, orderId);
        Assert.Equal([1, 2], events.Select(e => e.Payload.GetProperty("sequence").GetInt32()));
        Assert.All(events, e => Assert.Equal("REJECTED", e.Payload.GetProperty("data").GetProperty("status").GetString()));
    }

    [Fact]
    public async Task FAILED_sonra_Tekrar_dene_firmada_ikinci_gönderi_açmaz_aynı_takip_no()
    {
        await using var app = App();
        await DrainAsync(app);
        var orderId = await RequestAsync(app);
        // Firma açtı, yanıt gelmedi; reconciler bulamadan (ör. el ile) FAILED işaretlendi gibi ağır bir durum:
        Fault(app, FakeFailMode.TimeoutAfterCreate, times: 1);
        await RunWorkerAsync(app);
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.shipments SET status = 'FAILED', failure_reason = 'el ile' WHERE order_id = $1", orderId);

        Assert.Equal(IntakeOutcome.Reopened, await IntakeAsync(app, Command(orderId, 2)));
        await RunWorkerAsync(app);

        Assert.Equal(ShipmentStatus.Created, (await GetAsync(app, orderId)).Status);
        Assert.Equal(1, await FakeCarrierRowsAsync(containers, orderId));
    }
}

[Collection("Integration")]
public sealed class QaConfigTests(ContainersFixture containers)
{
    [Theory]
    [InlineData("Baska")]
    [InlineData("Fakee")]
    [InlineData(" Fake")]
    public async Task Tanınmayan_Carrier_Provider_ile_servis_açılmaz(string provider)
    {
        await using var app = TestApp.For(containers, new Dictionary<string, string?> { ["Carrier:Provider"] = provider });

        var ex = Record.Exception(() => app.CreateClient());

        Assert.True(ex is not null,
            $"Carrier:Provider='{provider}' ile servis açıldı; /health/ready 200 dönüyor ama hiçbir gönderi firmaya gitmeyecek.");
        Assert.Contains("Carrier:Provider", ex.ToString());
    }

    [Fact]
    public async Task Tanınmayan_Provider_ile_servis_açılmadığından_bekleyen_gönderi_REQUESTED_kalır()
    {
        // Eskiden servis açılıyor, hata her turda fırlıyordu; artık açılış doğrulaması servisi durdurur.
        // Bekleyen gönderiye hiçbir iş dokunmaz.
        var orderId = await InsertAsync(containers, "REQUESTED");
        await using var app = TestApp.For(containers, new Dictionary<string, string?>
        {
            ["Carrier:Provider"] = "Baska",
            ["Workers:Enabled"] = "true",
        });

        Assert.NotNull(Record.Exception(() => app.CreateClient()));

        Assert.Equal("REQUESTED", await Db.ScalarAsync<string>(containers,
            "SELECT status FROM cargo.shipments WHERE order_id = $1", orderId));
        await Db.ExecuteAsync(containers, "UPDATE cargo.shipments SET status = 'FAILED', recipient = NULL WHERE order_id = $1", orderId);
    }
}

[Collection("Integration")]
public sealed class QaRetentionTests(ContainersFixture containers)
{
    /// <summary>
    /// Teslim edilemeyen (DELIVERY_FAILED) ya da firmanın reddettiği (FAILED) ve uzun süredir değişmeyen
    /// gönderinin alıcı bilgisi hiçbir zaman silinmiyor.
    /// </summary>
    [Theory]
    [InlineData("DELIVERY_FAILED")]
    [InlineData("FAILED")]
    public async Task Teslim_edilmeyip_90_gündür_kapanmış_gönderinin_alıcı_verisi_süresiz_saklanmaz(string status)
    {
        await using var app = TestApp.For(containers);
        var orderId = await InsertAsync(containers, status, trackingNumber: status == "FAILED" ? null : $"TKQ{NewOrderId()}");
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.shipments SET created_at = now() - interval '120 days', updated_at = now() - interval '90 days', " +
            "delivery_failed_at = CASE WHEN status = 'DELIVERY_FAILED' THEN now() - interval '90 days' END WHERE order_id = $1", orderId);

        await app.Services.GetRequiredService<Cleanup>().RunOnceAsync(Ct);

        var hasRecipient = await Db.ScalarAsync<bool>(containers,
            "SELECT recipient IS NOT NULL FROM cargo.shipments WHERE order_id = $1", orderId);
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.shipments SET status = 'FAILED', tracking_number = NULL, recipient = NULL WHERE order_id = $1", orderId);
        Assert.False(hasRecipient, $"{status} gönderinin alıcı bilgisi 90 gün sonra hâlâ duruyor.");
    }

    [Fact]
    public async Task Çok_eski_yayınlanmamış_outbox_satırı_temizlikte_silinmez()
    {
        await using var app = TestApp.For(containers, new Dictionary<string, string?> { ["RabbitMq:Url"] = null });
        var id = await Db.InsertOutboxAsync(containers, "qa.never.published");
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.outbox_messages SET created_at = now() - interval '400 days', attempts = 999 WHERE id = $1", id);

        await app.Services.GetRequiredService<Cleanup>().RunOnceAsync(Ct);

        Assert.Equal(1L, await Db.ScalarAsync<long>(containers, "SELECT count(*) FROM cargo.outbox_messages WHERE id = $1", id));
        await Db.ExecuteAsync(containers, "UPDATE cargo.outbox_messages SET published_at = now() WHERE id = $1", id);
    }
}

[Collection("Integration")]
public sealed class QaTestEndpointTests(ContainersFixture containers)
{
    private TestApp App() => TestApp.For(containers, FastSettings());

    public static TheoryData<string> PathVariants => new()
    {
        "/test/fake-carrier/faults",
        "/TEST/FAKE-CARRIER/faults",
        "/test/fake-carrier/faults/",
        "/test/fake-carrier/./faults",
        "/test/fake-carrier/x/../faults",
        "/Test/Fake-Carrier/Faults?x=1",
    };

    [Theory]
    [MemberData(nameof(PathVariants))]
    public async Task Yol_varyasyonları_tokensız_ucu_açmaz(string path)
    {
        await using var app = App();
        using var client = app.CreateClient();

        var response = await client.PostAsJsonAsync(path, new { mode = "Permanent", times = 5 }, Ct);

        Assert.NotEqual(HttpStatusCode.NoContent, response.StatusCode);
        Assert.Equal(FakeFailMode.None, app.Services.GetRequiredService<FakeCarrierFaults>().TryConsume());
    }

    [Fact]
    public async Task Çok_uzun_token_boşluklu_token_ve_çoklu_başlık_401()
    {
        await using var app = App();
        foreach (var token in new[] { new string('a', 200_000), Token + "\0", " " + Token, Token.ToUpperInvariant() })
        {
            using var client = app.CreateClient();
            client.DefaultRequestHeaders.TryAddWithoutValidation(FakeCarrierEndpoints.TokenHeader, token);
            var response = await client.PostAsJsonAsync("/test/fake-carrier/faults", new { mode = "Permanent", times = 5 }, Ct);
            Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        }
        using (var client = app.CreateClient())
        {
            client.DefaultRequestHeaders.TryAddWithoutValidation(FakeCarrierEndpoints.TokenHeader, [Token, Token]);
            var response = await client.PostAsJsonAsync("/test/fake-carrier/faults", new { mode = "Permanent", times = 5 }, Ct);
            Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        }
        Assert.Equal(FakeFailMode.None, app.Services.GetRequiredService<FakeCarrierFaults>().TryConsume());
    }

    [Fact]
    public async Task Yanlış_Content_Type_bozuk_gövde_ve_olmayan_takip_no_durumu_değiştirmez()
    {
        await using var app = App();
        await DrainAsync(app);
        var orderId = await RequestAsync(app);
        await RunWorkerAsync(app);
        var tracking = (await GetAsync(app, orderId)).TrackingNumber!;
        using var client = Client(app);
        var url = $"/test/fake-carrier/shipments/{tracking}/status";

        var textPlain = await client.PostAsync(url, new StringContent("{\"status\":\"DELIVERED\"}", Encoding.UTF8, "text/plain"), Ct);
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, textPlain.StatusCode);
        var broken = await client.PostAsync(url, new StringContent("{\"status\":", Encoding.UTF8, "application/json"), Ct);
        Assert.Equal(HttpStatusCode.BadRequest, broken.StatusCode);
        var lower = await client.PostAsJsonAsync(url, new { status = "delivered" }, Ct);
        Assert.Equal(HttpStatusCode.BadRequest, lower.StatusCode);
        var unknown = await client.PostAsJsonAsync($"/test/fake-carrier/shipments/{tracking}X/status", new { status = "DELIVERED" }, Ct);
        Assert.Equal(HttpStatusCode.NotFound, unknown.StatusCode);
        var sqlish = await client.PostAsJsonAsync("/test/fake-carrier/shipments/' OR '1'='1/status", new { status = "DELIVERED" }, Ct);
        Assert.Equal(HttpStatusCode.NotFound, sqlish.StatusCode);

        Assert.Equal(ShipmentStatus.Created, (await GetAsync(app, orderId)).Status);
        Assert.Single(await EventsAsync(containers, orderId));
    }

    [Fact]
    public async Task DELIVEREDdan_geri_dönüş_isteği_204_ama_durum_ve_olaylar_değişmez()
    {
        await using var app = App();
        await DrainAsync(app);
        var orderId = await RequestAsync(app);
        await RunWorkerAsync(app);
        var tracking = (await GetAsync(app, orderId)).TrackingNumber!;
        using var client = Client(app);
        var url = $"/test/fake-carrier/shipments/{tracking}/status";
        Assert.Equal(HttpStatusCode.NoContent, (await client.PostAsJsonAsync(url, new { status = "DELIVERED" }, Ct)).StatusCode);
        var before = (await EventsAsync(containers, orderId)).Count;

        foreach (var status in new[] { "IN_TRANSIT", "DELIVERY_FAILED", "DELIVERED" })
        {
            Assert.Equal(HttpStatusCode.NoContent, (await client.PostAsJsonAsync(url, new { status }, Ct)).StatusCode);
        }
        await RunPollerAsync(app);

        Assert.Equal(ShipmentStatus.Delivered, (await GetAsync(app, orderId)).Status);
        Assert.Equal(before, (await EventsAsync(containers, orderId)).Count);
    }
}

/// <summary>
/// CarrierErrorsTests'te mutasyonla sağ kalan iki değişikliği öldürür: IOException'ın belirsiz
/// sayılmaması ve 404/401/403/409 gibi 4xx yanıtların belirsiz sayılması.
/// </summary>
public sealed class QaCarrierErrorsMutationTests
{
    [Fact]
    public void IOException_belirsizdir_SocketExceptiondan_ayrı_olarak()
    {
        Assert.False(typeof(System.Net.Sockets.SocketException).IsAssignableTo(typeof(IOException)));
        Assert.True(CarrierErrors.IsUncertain(new IOException("bağlantı sıfırlandı"), CancellationToken.None));
        Assert.True(CarrierErrors.IsUncertain(new HttpIOExceptionStub(), CancellationToken.None));
    }

    [Theory]
    [InlineData(HttpStatusCode.Unauthorized)]
    [InlineData(HttpStatusCode.Forbidden)]
    [InlineData(HttpStatusCode.NotFound)]
    [InlineData(HttpStatusCode.Conflict)]
    [InlineData((HttpStatusCode)499)]
    public void Diğer_4xx_yanıtlar_kalıcıdır(HttpStatusCode status)
    {
        Assert.False(CarrierErrors.IsUncertain(status));
        Assert.False(CarrierErrors.IsUncertain(new HttpRequestException("4xx", null, status), CancellationToken.None));
    }

    [Theory]
    [InlineData(HttpStatusCode.RequestTimeout)]
    [InlineData(HttpStatusCode.TooManyRequests)]
    [InlineData(HttpStatusCode.GatewayTimeout)]
    public void İstisnadaki_408_429_5xx_belirsizdir(HttpStatusCode status) =>
        Assert.True(CarrierErrors.IsUncertain(new HttpRequestException("x", null, status), CancellationToken.None));

    private sealed class HttpIOExceptionStub : IOException;
}
