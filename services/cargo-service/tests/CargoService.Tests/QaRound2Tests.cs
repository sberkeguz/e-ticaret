using System.Text;
using System.Text.Json;
using CargoService.Messaging;
using CargoService.Messaging.Contracts;
using CargoService.Shipping;
using CargoService.Tests.Infrastructure;
using Microsoft.Extensions.DependencyInjection;
using Npgsql;
using RabbitMQ.Client;
using static CargoService.Tests.Infrastructure.Shipments;

namespace CargoService.Tests;

/// <summary>
/// QA 2. tur: backend komut sözleşmesi (örnek JSON), deneme sayacı başlığının dışarıdan gelmesi,
/// aynı sipariş için eşzamanlı komutların tüketici üzerinden DLQ'ya düşmemesi, log özetleri.
/// </summary>
public sealed class QaContractTests
{
    /// <summary>
    /// Backend'in (Java/Jackson) yayınlayacağı "kargoya ver" komutunun örneği. Sözleşmenin tek kaynağı
    /// olarak backend geliştiricisine verilir: camelCase, declaredValue SAYI (ondalık), occurredAt ISO-8601
    /// ve saat dilimli, idempotencyKey tam olarak "order-{orderId}", version 1, paymentStatus büyük harf "PAID".
    /// </summary>
    public const string SampleJson = """
        {
          "messageId": "3f1c2a9e-8b7d-4c1e-9a55-0d2f6b7e4a10",
          "type": "ShipmentRequested",
          "version": 1,
          "occurredAt": "2026-11-26T10:15:30.123Z",
          "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
          "orderId": 123456,
          "requestNo": 1,
          "idempotencyKey": "order-123456",
          "paymentStatus": "PAID",
          "recipient": {
            "firstName": "Çağrı",
            "lastName": "Öztürk",
            "phone": "05551234567",
            "city": "İstanbul",
            "district": "Kadıköy",
            "neighborhood": "Moda",
            "address": "Şair Nefi Sk. No:5 D:3"
          },
          "parcel": { "itemCount": 2, "declaredValue": 149.90 }
        }
        """;

    private static ShipmentRequested Parse(string json) =>
        JsonSerializer.Deserialize<ShipmentRequested>(json, JsonSerializerOptions.Web)!;

    [Fact]
    public void Örnek_backend_JSONu_kabul_edilir_alanlar_doğru_okunur()
    {
        var command = Parse(SampleJson);

        Assert.Empty(command.Validate());
        Assert.Equal(123456L, command.OrderId);
        Assert.Equal(1, command.RequestNo);
        Assert.Equal("order-123456", command.IdempotencyKey);
        Assert.Equal(149.90m, command.Parcel!.DeclaredValue);
        Assert.Equal("149.90", command.Parcel.DeclaredValue!.Value.ToString(System.Globalization.CultureInfo.InvariantCulture));
        Assert.Equal(new DateTimeOffset(2026, 11, 26, 10, 15, 30, 123, TimeSpan.Zero), command.OccurredAt);
        Assert.Equal("Şair Nefi Sk. No:5 D:3", command.Recipient!.Address);
    }

    /// <summary>Jackson'ın varsayılan ya da sık görülen çıktı biçimleri; hepsi kabul edilmeli.</summary>
    public static TheoryData<string, string, string> AcceptedVariants => new()
    {
        { "declaredValue string", "\"declaredValue\": 149.90", "\"declaredValue\": \"149.90\"" },
        { "declaredValue tam sayı", "\"declaredValue\": 149.90", "\"declaredValue\": 150" },
        { "declaredValue sıfır", "\"declaredValue\": 149.90", "\"declaredValue\": 0" },
        { "declaredValue bilimsel (BigDecimal.toString 1E+3)", "\"declaredValue\": 149.90", "\"declaredValue\": 1E+3" },
        { "occurredAt +03:00", "\"2026-11-26T10:15:30.123Z\"", "\"2026-11-26T13:15:30.123+03:00\"" },
        { "occurredAt mikro saniye", "\"2026-11-26T10:15:30.123Z\"", "\"2026-11-26T10:15:30.123456Z\"" },
        { "occurredAt nano saniye (Instant.toString)", "\"2026-11-26T10:15:30.123Z\"", "\"2026-11-26T10:15:30.123456789Z\"" },
        { "orderId string", "\"orderId\": 123456", "\"orderId\": \"123456\"" },
        { "traceId null", "\"traceId\": \"4bf92f3577b34da6a3ce929d0e0e4736\"", "\"traceId\": null" },
        { "bilinmeyen ek alan", "\"requestNo\": 1,", "\"requestNo\": 1, \"customerNote\": \"x\", \"items\": [1,2]," },
        { "version 2 (alan eklenmiş sürüm)", "\"version\": 1", "\"version\": 2" },
        { "PascalCase alan adı", "\"orderId\": 123456", "\"OrderId\": 123456" },
    };

    [Theory]
    [MemberData(nameof(AcceptedVariants))]
    public void Backend_biçim_varyasyonları_kabul_edilir(string name, string from, string to)
    {
        Assert.Contains(from, SampleJson);
        var command = Parse(SampleJson.Replace(from, to));
        Assert.True(command.Validate().Count == 0, $"{name}: {string.Join(", ", command.Validate())}");
    }

    /// <summary>Sözleşmeye aykırı biçimler reddedilir (DLQ). Backend bunları üretmemeli.</summary>
    public static TheoryData<string, string, string> RejectedVariants => new()
    {
        { "idempotencyKey farklı biçim", "\"order-123456\"", "\"ORDER-123456\"" },
        { "declaredValue negatif", "\"declaredValue\": 149.90", "\"declaredValue\": -1" },
        { "requestNo 0", "\"requestNo\": 1", "\"requestNo\": 0" },
        { "version 0", "\"version\": 1", "\"version\": 0" },
        { "type farklı", "\"type\": \"ShipmentRequested\"", "\"type\": \"shipmentRequested\"" },
    };

    [Theory]
    [MemberData(nameof(RejectedVariants))]
    public void Sözleşmeye_aykırı_biçimler_reddedilir(string name, string from, string to)
    {
        Assert.Contains(from, SampleJson);
        var command = Parse(SampleJson.Replace(from, to));
        Assert.True(command.Validate().Count > 0, $"{name} kabul edildi");
    }

    /// <summary>
    /// Jackson'da JavaTimeModule yoksa ya da WRITE_DATES_AS_TIMESTAMPS açıksa tarih dizi/sayı olarak yazılır:
    /// JSON çözülemez → DLQ. Backend'e not: ISO-8601 metin yazılmalı.
    /// </summary>
    [Theory]
    [InlineData("[2026,11,26,10,15,30,123000000]")]
    [InlineData("1795688130.123")]
    public void Dizi_ya_da_sayı_olarak_yazılmış_occurredAt_çözülemez(string occurredAt)
    {
        var json = SampleJson.Replace("\"2026-11-26T10:15:30.123Z\"", occurredAt);
        Assert.Throws<JsonException>(() => Parse(json));
    }

    /// <summary>
    /// Küçük harf "paid" ödenmemiş sayılır: komut DLQ'ya değil REJECTED'a gider (kargoya verilmez).
    /// Backend'e not: paymentStatus enum adı birebir "PAID" olmalı.
    /// </summary>
    [Fact]
    public void Küçük_harf_paid_geçerli_komuttur_ama_ödenmiş_sayılmaz()
    {
        var command = Parse(SampleJson.Replace("\"PAID\"", "\"paid\""));
        Assert.Empty(command.Validate());
        Assert.NotEqual("PAID", command.PaymentStatus);
    }

    private static string C(params int[] codePoints) => string.Concat(codePoints.Select(char.ConvertFromUtf32));

    public static TheoryData<string> ValidUnicodeTexts => new()
    {
        "I" + C(0x0307) + "stanbul",                       // birleşik nokta (combining dot above)
        "Gu" + C(0x0308) + "l sk.",                        // birleşik iki nokta
        "Ev " + C(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467) + " No 3", // ZWJ emoji dizisi
        "Kalp " + C(0x2764, 0xFE0F) + " sk.",              // varyasyon seçici FE0F
        "Bayrak " + C(0x1F1F9, 0x1F1F7),                   // bölgesel gösterge çifti
        "No" + C(0x00A0) + "5",                            // bölünmez boşluk
        "Ad" + C(0x200B) + "soyad",                        // sıfır genişlikli boşluk
        "Sokak" + C(0x2028) + "No 5",                      // satır ayırıcı
        C(0x1D400, 0x1D41D, 0x1D42B),                      // matematiksel harfler (BMP dışı)
        C(0x65E5, 0x672C, 0x0020, 0x0627, 0x0644, 0x05E2), // CJK, Arapça, İbranice
    };

    [Theory]
    [MemberData(nameof(ValidUnicodeTexts))]
    public void Geçerli_Unicode_metin_reddedilmez(string text)
    {
        Assert.True(SafeText.IsSafe(text));
        Assert.Empty((Shipments.Command(1) with { Recipient = Shipments.Recipient() with { Address = text, FirstName = text } }).Validate());
    }
}

[Collection("Integration")]
public sealed class QaRound2IntegrationTests(ContainersFixture containers)
{
    private const string Marker = "QA2-UNKNOWN";

    [Fact]
    public async Task Örnek_backend_JSONu_kuyruktan_işlenir_koli_ve_alıcı_aynen_saklanır()
    {
        await using var app = TestApp.For(containers);
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        var orderId = NewOrderId();
        var messageId = Guid.NewGuid().ToString();
        var json = QaContractTests.SampleJson
            .Replace("3f1c2a9e-8b7d-4c1e-9a55-0d2f6b7e4a10", messageId)
            .Replace("123456", orderId.ToString());
        await rabbit.PublishCommandAsync(json, messageId);

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(Ct);
        var consuming = app.Services.GetRequiredService<ShipmentRequestsConsumer>().ConsumeAsync(cts.Token);
        try
        {
            string? status = null;
            for (var i = 0; i < 100 && status is null; i++)
            {
                await Task.Delay(100, Ct);
                status = await Db.ScalarAsync<string>(containers, "SELECT status FROM cargo.shipments WHERE order_id = $1", orderId);
            }
            Assert.Equal("REQUESTED", status);
            Assert.Equal("149.90", await Db.ScalarAsync<string>(containers,
                "SELECT parcel->>'declaredValue' FROM cargo.shipments WHERE order_id = $1", orderId));
            Assert.Equal("Şair Nefi Sk. No:5 D:3", await Db.ScalarAsync<string>(containers,
                "SELECT recipient->>'address' FROM cargo.shipments WHERE order_id = $1", orderId));
            Assert.Equal($"order-{orderId}", await Db.ScalarAsync<string>(containers,
                "SELECT idempotency_key FROM cargo.shipments WHERE order_id = $1", orderId));
        }
        finally
        {
            await cts.CancelAsync();
            await consuming;
        }
    }

    /// <summary>
    /// Aynı sipariş için farklı messageId'li komutlar İKİ tüketici kopyasına aynı anda gelir. Biri
    /// 23505'e takılır; intake bir kez baştan dener. Sınıflandırma 23505'i "kalıcı" saydığından, bu yol
    /// tutmazsa geçerli komut DLQ'ya düşerdi. Hiçbir komut DLQ'ya düşmemeli, her siparişte tek satır olmalı.
    /// </summary>
    [Fact]
    public async Task Aynı_sipariş_paralel_komutlar_iki_tüketicide_DLQya_düşmez()
    {
        await using var app1 = TestApp.For(containers);
        await using var app2 = TestApp.For(containers);
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        await rabbit.DrainAsync(CargoTopology.ShipmentRequestsDeadLetterQueue);

        var orders = Enumerable.Range(0, 15).Select(_ => NewOrderId()).ToList();
        var messageIds = new HashSet<string>();
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(Ct);
        var c1 = app1.Services.GetRequiredService<ShipmentRequestsConsumer>().ConsumeAsync(cts.Token);
        var c2 = app2.Services.GetRequiredService<ShipmentRequestsConsumer>().ConsumeAsync(cts.Token);
        try
        {
            await Task.Delay(1500, Ct); // iki tüketici de bağlansın
            foreach (var orderId in orders)
            {
                for (var k = 0; k < 2; k++)
                {
                    var command = Command(orderId);
                    messageIds.Add(command.MessageId!);
                    await rabbit.PublishCommandAsync(JsonSerializer.Serialize(command, JsonSerializerOptions.Web), command.MessageId!);
                }
            }

            long processed = 0;
            for (var i = 0; i < 200 && processed < messageIds.Count; i++)
            {
                await Task.Delay(100, Ct);
                processed = await Db.ScalarAsync<long>(containers,
                    "SELECT count(*) FROM cargo.processed_messages WHERE message_id = ANY($1)", (object)messageIds.ToArray());
            }

            var dead = (await rabbit.DrainAsync(CargoTopology.ShipmentRequestsDeadLetterQueue))
                .Count(m => messageIds.Contains(m.BasicProperties.MessageId ?? ""));
            Assert.Equal(0, dead);
            Assert.Equal(messageIds.Count, processed);
            Assert.Equal(orders.Count, await Db.ScalarAsync<long>(containers,
                "SELECT count(*) FROM cargo.shipments WHERE order_id = ANY($1)", orders.ToArray()));
        }
        finally
        {
            await cts.CancelAsync();
            await Task.WhenAll(c1, c2);
            await rabbit.DrainAsync(CargoTopology.ShipmentRequestsQueue);
        }
    }

    /// <summary>Aynı sipariş için dört komut aynı anda: tek baştan deneme yeterli olmalı (23505 dışarı sızmaz).</summary>
    [Fact]
    public async Task Aynı_sipariş_dört_eşzamanlı_komutta_23505_dışarı_sızmaz()
    {
        await using var app = TestApp.For(containers);
        var orders = Enumerable.Range(0, 10).Select(_ => NewOrderId()).ToList();

        var results = await Task.WhenAll(orders.SelectMany(orderId =>
            Enumerable.Range(0, 4).Select(_ => IntakeAsync(app, Command(orderId)))));

        Assert.Equal(orders.Count, results.Count(r => r == IntakeOutcome.Requested));
        Assert.Equal(orders.Count * 3, results.Count(r => r == IntakeOutcome.Ignored));
    }

    /// <summary>
    /// Deneme sayacı başlığı dışarıdan (kötü niyetli/bozuk üretici) sınırın üstünde gelirse, ilk tanınmayan
    /// hatada DLQ beklenir (başlık 4 ya da 5 gelince olduğu gibi). int.MaxValue + 1 taşınca sayaç negatife
    /// döner, mesaj 5 sn bekleyip x-cargo-attempts = int.MinValue ile yeniden yayınlanır ve sayım sıfırdan başlar.
    /// </summary>
    [Theory]
    [InlineData(4)]
    [InlineData(int.MaxValue)]
    public async Task Sınır_üstü_deneme_başlığıyla_gelen_komut_ilk_tanınmayan_hatada_DLQya_gider(int header)
    {
        await using var app = TestApp.For(containers);
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        _ = app.Services;
        await InstallUnknownFailureTriggerAsync();
        var command = Command(NewOrderId()) with { Recipient = Recipient() with { Address = Address + " " + Marker } };
        await PublishWithHeaderAsync(JsonSerializer.Serialize(command, JsonSerializerOptions.Web), command.MessageId!, header);

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(Ct);
        var consuming = app.Services.GetRequiredService<ShipmentRequestsConsumer>().ConsumeAsync(cts.Token);
        try
        {
            var dead = await rabbit.FindAsync(CargoTopology.ShipmentRequestsDeadLetterQueue, command.MessageId!,
                TimeSpan.FromSeconds(4));
            Assert.NotNull(dead);
        }
        finally
        {
            await cts.CancelAsync();
            await consuming;
            await rabbit.DrainAsync(CargoTopology.ShipmentRequestsQueue);
            await AdminAsync("DROP TRIGGER IF EXISTS qa2_unknown_failure ON cargo.shipments; DROP FUNCTION IF EXISTS cargo.qa2_unknown_failure();");
        }
    }

    /// <summary>
    /// H2: tüketici hata özetinde istisna mesajını yazmıyor; ama arka plan işlerinin ortak döngüsü
    /// (WorkerLoop.Describe — gönderi işçisi, uzlaştırıcı, takip, temizlik) hâlâ "Tip: ex.Message" yazıyor.
    /// PostgresException.Message = "SqlState: MessageText"; tetikleyici/kural mesajları ve bazı SqlState'ler
    /// (ör. 22P02 'invalid input syntax for type ...: "değer"') satır değerini MessageText'e koyar.
    /// Burada tetikleyici, işçinin SENDING'e çektiği satırın adresini hata mesajına koyar.
    /// </summary>
    [Fact]
    public async Task Arka_plan_işi_turu_hatası_satır_verisini_loga_yazmaz()
    {
        var logs = new LogCapture();
        await using (var setup = TestApp.For(containers))
        {
            _ = setup.Services;
            await DrainAsync(setup); // önceki testlerin REQUESTED gönderileri bu tura karışmasın
        }
        await AdminAsync($$"""
            CREATE OR REPLACE FUNCTION cargo.qa2_leak() RETURNS trigger LANGUAGE plpgsql AS $f$
            BEGIN
              IF NEW.status = 'SENDING' AND NEW.recipient::text LIKE '%{{Marker}}-LEAK%' THEN
                RAISE EXCEPTION 'satir: %', NEW.recipient->>'address' USING ERRCODE = 'P0001';
              END IF;
              RETURN NEW;
            END $f$;
            DROP TRIGGER IF EXISTS qa2_leak ON cargo.shipments;
            CREATE TRIGGER qa2_leak BEFORE UPDATE ON cargo.shipments
              FOR EACH ROW EXECUTE FUNCTION cargo.qa2_leak();
            """);
        try
        {
            await using var app = TestApp.For(containers, FastSettings(new() { ["Workers:Enabled"] = "true" }), logs);
            var command = Command(NewOrderId()) with { Recipient = Recipient() with { Address = $"Saklisokak {Marker}-LEAK No 41" } };
            Assert.Equal(IntakeOutcome.Requested, await IntakeAsync(app, command));

            for (var i = 0; i < 100 && !logs.Lines.Any(l => l.Contains("turu başarısız")); i++)
            {
                await Task.Delay(100, Ct);
            }
            Assert.Contains(logs.Lines, l => l.Contains("turu başarısız"));
            Assert.DoesNotContain(logs.Lines, l => l.Contains("Saklisokak"));
        }
        finally
        {
            await AdminAsync("DROP TRIGGER IF EXISTS qa2_leak ON cargo.shipments; DROP FUNCTION IF EXISTS cargo.qa2_leak();");
            await using var cleanup = TestApp.For(containers);
            _ = cleanup.Services;
            await DrainAsync(cleanup);
        }
    }

    private async Task PublishWithHeaderAsync(string body, string messageId, object header)
    {
        var factory = new ConnectionFactory { Uri = new Uri(containers.RabbitMqUrl) };
        await using var connection = await factory.CreateConnectionAsync(Ct);
        await using var channel = await connection.CreateChannelAsync(cancellationToken: Ct);
        var props = new BasicProperties
        {
            MessageId = messageId,
            ContentType = "application/json",
            Persistent = true,
            Headers = new Dictionary<string, object?> { [CommandFailures.AttemptsHeader] = header },
        };
        await channel.BasicPublishAsync(CargoTopology.Exchange, CargoTopology.ShipmentCreateRoutingKey, mandatory: false,
            props, Encoding.UTF8.GetBytes(body), Ct);
    }

    private Task InstallUnknownFailureTriggerAsync() => AdminAsync($$"""
        CREATE OR REPLACE FUNCTION cargo.qa2_unknown_failure() RETURNS trigger LANGUAGE plpgsql AS $f$
        BEGIN
          IF NEW.recipient::text LIKE '%{{Marker}}%' THEN
            RAISE EXCEPTION 'qa2: bilinmeyen hata' USING ERRCODE = 'P0001';
          END IF;
          RETURN NEW;
        END $f$;
        DROP TRIGGER IF EXISTS qa2_unknown_failure ON cargo.shipments;
        CREATE TRIGGER qa2_unknown_failure BEFORE INSERT OR UPDATE ON cargo.shipments
          FOR EACH ROW EXECUTE FUNCTION cargo.qa2_unknown_failure();
        """);

    private async Task AdminAsync(string sql)
    {
        await using var admin = new NpgsqlConnection(containers.AdminConnectionString);
        await admin.OpenAsync(CancellationToken.None);
        await using var command = new NpgsqlCommand(sql, admin);
        await command.ExecuteNonQueryAsync(CancellationToken.None);
    }
}

/// <summary>Ortam değişkeni süreç geneli olduğundan bu koleksiyon diğer testlerle paralel çalışmaz.</summary>
[CollectionDefinition("EnvVar", DisableParallelization = true)]
public sealed class EnvVarCollection;

[Collection("EnvVar")]
public sealed class QaProviderEnvVarTests
{
    private const string UnreachableDb =
        "Host=127.0.0.1;Port=1;Database=eticaret;Username=cargo_app;Password=x;Timeout=3";

    private static Exception? Start(string? provider)
    {
        var previous = Environment.GetEnvironmentVariable("Carrier__Provider");
        Environment.SetEnvironmentVariable("Carrier__Provider", provider);
        try
        {
            using var app = new TestApp(new Dictionary<string, string?>
            {
                ["ConnectionStrings:Cargo"] = UnreachableDb,
                ["Database:MigrateOnStartup"] = "false",
            });
            return Record.Exception(() => app.CreateClient().Dispose());
        }
        finally
        {
            Environment.SetEnvironmentVariable("Carrier__Provider", previous);
        }
    }

    [Fact]
    public void Ortam_değişkeninden_Fake_ile_açılır()
    {
        Assert.Null(Start("Fake"));
    }

    /// <summary>Windows satır sonlu .env dosyasından "Fake\r" ya da küçük harf gelirse servis açılmaz (güvenli taraf).</summary>
    [Theory]
    [InlineData("fake")]
    [InlineData("Fake\r")]
    [InlineData("Fake ")]
    public void Ortam_değişkeninden_tanınmayan_değerle_açılmaz(string provider)
    {
        var ex = Start(provider);
        Assert.NotNull(ex);
        Assert.Contains("Carrier:Provider", ex.ToString());
    }
}
