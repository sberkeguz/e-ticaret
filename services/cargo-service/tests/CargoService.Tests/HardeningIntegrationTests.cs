using System.Text;
using System.Text.Json;
using CargoService.Messaging;
using CargoService.Tests.Infrastructure;
using Microsoft.Extensions.DependencyInjection;
using Npgsql;
using static CargoService.Tests.Infrastructure.Shipments;

namespace CargoService.Tests;

/// <summary>
/// Tüketicinin "tanınmayan hata" yolu (sayaçlı yeniden yayın, sınırda DLQ) ve DELIVERY_FAILED
/// gönderilerin sorgu penceresi.
/// </summary>
[Collection("Integration")]
public sealed class HardeningIntegrationTests(ContainersFixture containers)
{
    private const string Marker = "QA-UNKNOWN-FAILURE";

    /// <summary>
    /// İşaretli satırlarda veritabanı sınıf 22/23 DIŞINDA, geçici de olmayan bir hata verir (P0001,
    /// raise_exception). Tüketici bunu tanımaz: sayaçlı kopyayla tekrar dener ve
    /// <see cref="ShipmentRequestsConsumer.MaxUnknownAttempts"/>. denemede DLQ'ya gönderir.
    /// </summary>
    [Fact]
    public async Task Tanınmayan_hata_sayılarak_denenir_sınırda_DLQya_gider_sayaç_başlıkta_taşınır()
    {
        await using var app = TestApp.For(containers);
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        _ = app.Services; // uygulama açılsın: migration tetikleyiciden önce şemayı kursun
        await AdminAsync($$"""
            CREATE OR REPLACE FUNCTION cargo.qa_unknown_failure() RETURNS trigger LANGUAGE plpgsql AS $f$
            BEGIN
              IF NEW.recipient::text LIKE '%{{Marker}}%' THEN
                RAISE EXCEPTION 'qa: bilinmeyen hata' USING ERRCODE = 'P0001';
              END IF;
              RETURN NEW;
            END $f$;
            DROP TRIGGER IF EXISTS qa_unknown_failure ON cargo.shipments;
            CREATE TRIGGER qa_unknown_failure BEFORE INSERT OR UPDATE ON cargo.shipments
              FOR EACH ROW EXECUTE FUNCTION cargo.qa_unknown_failure();
            """);
        var command = Command(NewOrderId()) with { Recipient = Recipient() with { Address = Address + " " + Marker } };
        await rabbit.PublishCommandAsync(JsonSerializer.Serialize(command, JsonSerializerOptions.Web), command.MessageId!);

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(Ct);
        var consuming = app.Services.GetRequiredService<ShipmentRequestsConsumer>().ConsumeAsync(cts.Token);
        try
        {
            // 5 deneme, aralarında 4 × 5 sn bekleme.
            var dead = await rabbit.FindAsync(CargoTopology.ShipmentRequestsDeadLetterQueue, command.MessageId!,
                TimeSpan.FromSeconds(40));
            Assert.NotNull(dead);
            // DLQ'ya düşen, 4. denemeden sonra yayınlanmış kopyadır; gövde ve messageId korunur.
            Assert.Equal(ShipmentRequestsConsumer.MaxUnknownAttempts - 1,
                CommandFailures.ReadAttempts(dead.BasicProperties.Headers));
            Assert.Equal(JsonSerializer.Serialize(command, JsonSerializerOptions.Web), Encoding.UTF8.GetString(dead.Body.Span));
            Assert.Equal(0L, await Db.ScalarAsync<long>(containers,
                "SELECT count(*) FROM cargo.shipments WHERE order_id = $1", command.OrderId));
        }
        finally
        {
            await cts.CancelAsync();
            await consuming;
            await rabbit.DrainAsync(CargoTopology.ShipmentRequestsQueue);
            await AdminAsync("DROP TRIGGER IF EXISTS qa_unknown_failure ON cargo.shipments; DROP FUNCTION IF EXISTS cargo.qa_unknown_failure();");
        }
    }

    [Fact]
    public async Task Uzun_süredir_değişmeyen_DELIVERY_FAILED_gönderi_sorgulanmaz_yenisi_sorgulanır()
    {
        await using var app = TestApp.For(containers, FastSettings());
        var stale = await InsertAsync(containers, "DELIVERY_FAILED", trackingNumber: $"TKH{NewOrderId()}");
        var recent = await InsertAsync(containers, "DELIVERY_FAILED", trackingNumber: $"TKH{NewOrderId()}");
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.shipments SET updated_at = now() - interval '91 days', delivery_failed_at = now() - interval '91 days' WHERE order_id = $1",
            stale);

        await RunPollerAsync(app);

        const string polled = "SELECT last_polled_at IS NOT NULL FROM cargo.shipments WHERE order_id = $1";
        var stalePolled = await Db.ScalarAsync<bool>(containers, polled, stale);
        var recentPolled = await Db.ScalarAsync<bool>(containers, polled, recent);
        // Sonraki testlerin takip turları bu yapay gönderileri aramasın.
        await Db.ExecuteAsync(containers,
            "UPDATE cargo.shipments SET status = 'DELIVERED', recipient = NULL WHERE order_id = ANY($1)", new[] { stale, recent });
        Assert.False(stalePolled);
        Assert.True(recentPolled);
    }

    private async Task AdminAsync(string sql)
    {
        await using var admin = new NpgsqlConnection(containers.AdminConnectionString);
        await admin.OpenAsync(CancellationToken.None);
        await using var command = new NpgsqlCommand(sql, admin);
        await command.ExecuteNonQueryAsync(CancellationToken.None);
    }
}
