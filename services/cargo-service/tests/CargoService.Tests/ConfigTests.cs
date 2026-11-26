using CargoService.Tests.Infrastructure;

namespace CargoService.Tests;

public sealed class ConfigTests
{
    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    public void Bağlantı_dizesi_yoksa_uygulama_açılmaz(string? connectionString)
    {
        using var app = new TestApp(new Dictionary<string, string?>
        {
            ["ConnectionStrings:Cargo"] = connectionString,
        });

        var ex = Record.Exception(() => app.CreateClient());

        Assert.NotNull(ex);
        Assert.Contains("ConnectionStrings:Cargo", ex.ToString());
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    public void Kargo_firması_sağlayıcısı_verilmezse_uygulama_açılmaz(string? provider)
    {
        // Bağlantı dizesi dolu (ulaşılamaz bir adres): hata yalnızca sağlayıcı eksikliğinden gelmeli.
        using var app = new TestApp(new Dictionary<string, string?>
        {
            ["ConnectionStrings:Cargo"] = "Host=127.0.0.1;Port=1;Database=eticaret;Username=cargo_app;Password=x;Timeout=3",
            ["Database:MigrateOnStartup"] = "false",
            ["Carrier:Provider"] = provider,
        });

        var ex = Record.Exception(() => app.CreateClient());

        Assert.NotNull(ex);
        Assert.Contains("Carrier:Provider", ex.ToString());
        Assert.DoesNotContain("ConnectionStrings:Cargo zorunludur", ex.ToString());
    }

    private const string UnreachableDb =
        "Host=127.0.0.1;Port=1;Database=eticaret;Username=cargo_app;Password=x;Timeout=3";

    private static Dictionary<string, string?> Settings(params (string Key, string? Value)[] extra)
    {
        var settings = new Dictionary<string, string?>
        {
            ["ConnectionStrings:Cargo"] = UnreachableDb,
            ["Database:MigrateOnStartup"] = "false",
            ["Carrier:Provider"] = "Fake",
        };
        foreach (var (key, value) in extra)
        {
            settings[key] = value;
        }
        return settings;
    }

    [Fact]
    public void Production_ortamında_sahte_firma_test_uçları_açıksa_uygulama_açılmaz()
    {
        using var app = new TestApp(Settings(
            ("environment", "Production"),
            ("Carrier:Fake:TestEndpointsEnabled", "true"),
            ("InternalApiToken", "x")));

        var ex = Record.Exception(() => app.CreateClient());

        Assert.NotNull(ex);
        Assert.Contains("Carrier:Fake:TestEndpointsEnabled", ex.ToString());
        Assert.Contains("Production", ex.ToString());
    }

    [Fact]
    public void Production_dışında_sahte_firma_test_uçları_açık_olabilir()
    {
        using var app = new TestApp(Settings(
            ("Carrier:Fake:TestEndpointsEnabled", "true"),
            ("InternalApiToken", "x")));

        Assert.Null(Record.Exception(() => app.CreateClient()));
    }

    [Fact]
    public void Production_ortamında_sahte_firma_seçiliyse_uyarı_loglanır_ama_uygulama_açılır()
    {
        var logs = new LogCapture();
        using var app = new TestApp(Settings(("environment", "Production")), logs);

        Assert.Null(Record.Exception(() => app.CreateClient()));
        Assert.Contains(logs.Lines, line => line.StartsWith("Warning") && line.Contains("Carrier:Provider=Fake"));
    }

    [Fact]
    public void Production_dışında_sahte_firma_için_uyarı_loglanmaz()
    {
        var logs = new LogCapture();
        using var app = new TestApp(Settings(), logs);

        app.CreateClient();
        Assert.DoesNotContain(logs.Lines, line => line.Contains("Carrier:Provider=Fake"));
    }

    [Theory]
    [InlineData("00:00:00")]
    [InlineData("-1.00:00:00")]
    public void Takılı_gönderi_alıcı_saklama_süresi_sıfırdan_büyük_olmalı(string retention)
    {
        using var app = new TestApp(Settings(("Workers:StaleRecipientRetention", retention)));

        var ex = Record.Exception(() => app.CreateClient());

        Assert.NotNull(ex);
        Assert.Contains("Workers:StaleRecipientRetention", ex.ToString());
    }
}
