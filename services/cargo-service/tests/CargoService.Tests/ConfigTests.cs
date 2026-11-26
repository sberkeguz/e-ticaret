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
}
