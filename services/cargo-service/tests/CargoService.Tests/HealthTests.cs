using System.Net;
using CargoService.Tests.Infrastructure;

namespace CargoService.Tests;

[Collection("Integration")]
public sealed class HealthTests(ContainersFixture containers)
{
    // Hiçbir şeyin dinlemediği bir port: bağlantı hemen reddedilir.
    private const string UnreachableDb =
        "Host=127.0.0.1;Port=1;Database=eticaret;Username=cargo_app;Password=x;Timeout=3";

    [Fact]
    public async Task Live_200()
    {
        await using var app = TestApp.For(containers);
        using var client = app.CreateClient();

        var response = await client.GetAsync("/health/live", TestContext.Current.CancellationToken);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task Ready_veritabanına_ulaşınca_200()
    {
        await using var app = TestApp.For(containers);
        using var client = app.CreateClient();

        var response = await client.GetAsync("/health/ready", TestContext.Current.CancellationToken);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task Veritabanına_ulaşılamazsa_ready_503_live_yine_200()
    {
        // Açılış migration'ı kapalı: aksi hâlde uygulama hiç açılmaz ve ready ölçülemez.
        await using var app = TestApp.For(containers, new Dictionary<string, string?>
        {
            ["ConnectionStrings:Cargo"] = UnreachableDb,
            ["Database:MigrateOnStartup"] = "false",
        });
        using var client = app.CreateClient();

        var ready = await client.GetAsync("/health/ready", TestContext.Current.CancellationToken);
        var live = await client.GetAsync("/health/live", TestContext.Current.CancellationToken);

        Assert.Equal(HttpStatusCode.ServiceUnavailable, ready.StatusCode);
        Assert.Equal(HttpStatusCode.OK, live.StatusCode);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("amqp://guest:guest@127.0.0.1:1/")]
    public async Task RabbitMQ_ayarı_yokken_ya_da_ulaşılamazken_ready_yine_200(string? rabbitUrl)
    {
        await using var app = TestApp.For(containers, new Dictionary<string, string?>
        {
            ["RabbitMq:Url"] = rabbitUrl,
        });
        using var client = app.CreateClient();

        var response = await client.GetAsync("/health/ready", TestContext.Current.CancellationToken);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }
}
