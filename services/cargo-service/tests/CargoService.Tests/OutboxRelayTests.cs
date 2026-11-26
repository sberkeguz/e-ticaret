using System.Text;
using CargoService.Messaging;
using CargoService.Tests.Infrastructure;
using Microsoft.Extensions.DependencyInjection;

namespace CargoService.Tests;

// Test anahtarları "shipment." ile başlar: uygulama dar izinli cargo kullanıcısıyla bağlanır ve "cargo"
// exchange'ine yalnız "shipment.*" yayınlayabilir (init-cargo-user.sh konu izni).
[Collection("Integration")]
public sealed class OutboxRelayTests(ContainersFixture containers)
{
    private const string UnreachableRabbit = "amqp://guest:guest@127.0.0.1:1/";
    private const string TraceId = "4bf92f3577b34da6a3ce929d0e0e4736";

    [Fact]
    public async Task Satır_yayınlanır_mesaj_kuyruğa_düşer_ve_published_at_dolar()
    {
        await using var app = TestApp.For(containers);
        _ = app.Services; // uygulamayı açar (migration)
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        var routingKey = $"shipment.test.relay.{Guid.NewGuid():N}";
        var queue = await rabbit.BindTemporaryQueueAsync(routingKey);
        var id = await Db.InsertOutboxAsync(containers, routingKey, TraceId);

        await Relay(app).RunOnceAsync(TestContext.Current.CancellationToken);

        var message = await rabbit.FindAsync(queue, id.ToString());
        Assert.NotNull(message);
        Assert.Equal("TestEvent", message.BasicProperties.Type);
        Assert.True(message.BasicProperties.Persistent);
        Assert.Equal("application/json", message.BasicProperties.ContentType);
        Assert.Equal(1, Convert.ToInt32(message.BasicProperties.Headers!["x-event-version"]));
        var traceparent = Encoding.UTF8.GetString((byte[])message.BasicProperties.Headers["traceparent"]!);
        Assert.Matches($"^00-{TraceId}-[0-9a-f]{{16}}-01$", traceparent);
        Assert.Contains(id.ToString(), Encoding.UTF8.GetString(message.Body.Span));

        Assert.NotNull(await Db.ScalarAsync<DateTime?>(containers,
            "SELECT published_at FROM cargo.outbox_messages WHERE id = $1", id));
    }

    [Fact]
    public async Task RabbitMQ_ulaşılamazken_satır_bekler_hata_sayacı_dolar_ulaşılınca_gider()
    {
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        var routingKey = $"shipment.test.relay.{Guid.NewGuid():N}";
        var queue = await rabbit.BindTemporaryQueueAsync(routingKey);

        await using (var down = TestApp.For(containers, new Dictionary<string, string?> { ["RabbitMq:Url"] = UnreachableRabbit }))
        {
            _ = down.Services;
            var id = await Db.InsertOutboxAsync(containers, routingKey);

            var published = await Relay(down).RunOnceAsync(TestContext.Current.CancellationToken);

            Assert.Equal(0, published);
            Assert.Null(await Db.ScalarAsync<DateTime?>(containers,
                "SELECT published_at FROM cargo.outbox_messages WHERE id = $1", id));
            Assert.Equal(1, await Db.ScalarAsync<int>(containers,
                "SELECT attempts FROM cargo.outbox_messages WHERE id = $1", id));
            var lastError = await Db.ScalarAsync<string>(containers,
                "SELECT last_error FROM cargo.outbox_messages WHERE id = $1", id);
            Assert.False(string.IsNullOrWhiteSpace(lastError));
            Assert.DoesNotContain("GIZLI-PAYLOAD", lastError);

            // RabbitMQ "geri geldi": aynı veritabanına doğru adresle bağlanan örnek satırı gönderir.
            await using var up = TestApp.For(containers);
            await Relay(up).RunOnceAsync(TestContext.Current.CancellationToken);

            Assert.NotNull(await rabbit.FindAsync(queue, id.ToString()));
            Assert.NotNull(await Db.ScalarAsync<DateTime?>(containers,
                "SELECT published_at FROM cargo.outbox_messages WHERE id = $1", id));
        }
    }

    [Fact]
    public async Task Bağlantı_koparsa_sonraki_turda_yeniden_bağlanıp_gönderir()
    {
        await using var app = TestApp.For(containers);
        _ = app.Services;
        var relay = Relay(app);
        var routingKey = $"shipment.test.relay.{Guid.NewGuid():N}";
        var first = await Db.InsertOutboxAsync(containers, routingKey);
        await relay.RunOnceAsync(TestContext.Current.CancellationToken);
        Assert.NotNull(await Db.ScalarAsync<DateTime?>(containers,
            "SELECT published_at FROM cargo.outbox_messages WHERE id = $1", first));

        await containers.CloseRabbitConnectionsAsync();
        var second = await Db.InsertOutboxAsync(containers, routingKey);

        // Kopuş istemciye geç ulaşırsa ilk tur başarısız olabilir; birkaç tur içinde gitmelidir.
        for (var i = 0; i < 5 && await Db.ScalarAsync<DateTime?>(containers,
                 "SELECT published_at FROM cargo.outbox_messages WHERE id = $1", second) is null; i++)
        {
            await Task.Delay(300, TestContext.Current.CancellationToken);
            await relay.RunOnceAsync(TestContext.Current.CancellationToken);
        }

        Assert.NotNull(await Db.ScalarAsync<DateTime?>(containers,
            "SELECT published_at FROM cargo.outbox_messages WHERE id = $1", second));
    }

    [Fact]
    public async Task Yönlendirilemeyen_mesaj_cargo_unrouted_kuyruğuna_düşer()
    {
        await using var app = TestApp.For(containers);
        _ = app.Services;
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        var id = await Db.InsertOutboxAsync(containers, $"shipment.test.nobody.{Guid.NewGuid():N}");

        await Relay(app).RunOnceAsync(TestContext.Current.CancellationToken);

        Assert.NotNull(await rabbit.FindAsync(CargoTopology.UnroutedQueue, id.ToString()));
    }

    [Fact]
    public async Task Aynı_satır_eşzamanlı_iki_tur_tarafından_iki_kez_yayınlanmaz()
    {
        await using var appA = TestApp.For(containers);
        await using var appB = TestApp.For(containers);
        _ = appA.Services;
        _ = appB.Services;
        await using var rabbit = await RabbitTestClient.ConnectAsync(containers);
        var routingKey = $"shipment.test.relay.{Guid.NewGuid():N}";
        var queue = await rabbit.BindTemporaryQueueAsync(routingKey);
        var ids = new List<Guid>();
        for (var i = 0; i < 45; i++)
        {
            ids.Add(await Db.InsertOutboxAsync(containers, routingKey));
        }

        var ct = TestContext.Current.CancellationToken;
        await Task.WhenAll(
            Relay(appA).RunOnceAsync(ct), Relay(appB).RunOnceAsync(ct),
            Relay(appA).RunOnceAsync(ct), Relay(appB).RunOnceAsync(ct));
        await Task.Delay(500, ct);

        var received = (await rabbit.DrainAsync(queue)).Select(m => m.BasicProperties.MessageId).ToList();
        Assert.Equal(ids.Count, received.Count);
        Assert.Equal(ids.Select(i => i.ToString()).Order(), received.Order());
    }

    private static OutboxRelay Relay(TestApp app) => app.Services.GetRequiredService<OutboxRelay>();
}
