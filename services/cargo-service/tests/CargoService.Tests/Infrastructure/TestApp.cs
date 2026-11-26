using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Logging;

namespace CargoService.Tests.Infrastructure;

/// <summary>
/// Servisi bellek içinde ayağa kaldırır. Arka plan işleri (Workers:Enabled=false) kapalıdır;
/// verilen ayarlar uygulamanın kendi yapılandırmasının üzerine yazılır. <paramref name="logs"/>
/// verilirse uygulamanın bütün logları (her kategori, Trace dahil) ona da yazılır.
/// </summary>
public sealed class TestApp(IReadOnlyDictionary<string, string?> settings, LogCapture? logs = null)
    : WebApplicationFactory<Program>
{
    /// <summary>
    /// Kapsayıcılara bağlı, cargo_app rolüyle, RabbitMQ'ya dar izinli cargo kullanıcısıyla ve sahte kargo
    /// firmasıyla çalışan uygulama; <paramref name="overrides"/> varsayılanların üzerine yazar.
    /// </summary>
    public static TestApp For(
        ContainersFixture containers,
        IReadOnlyDictionary<string, string?>? overrides = null,
        LogCapture? logs = null)
    {
        var values = new Dictionary<string, string?>
        {
            ["ConnectionStrings:Cargo"] = containers.CargoConnectionString,
            ["RabbitMq:Url"] = containers.CargoRabbitMqUrl,
            // Carrier:Provider zorunludur ve varsayılanı yoktur; testler sahte firmayla çalışır.
            ["Carrier:Provider"] = "Fake",
        };
        foreach (var (key, value) in overrides ?? new Dictionary<string, string?>())
        {
            values[key] = value;
        }
        return new TestApp(values, logs);
    }

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Testing");
        builder.UseSetting("Workers:Enabled", "false");
        foreach (var (key, value) in settings)
        {
            builder.UseSetting(key, value);
        }
        if (logs is not null)
        {
            builder.ConfigureLogging(logging =>
            {
                logging.SetMinimumLevel(LogLevel.Trace);
                logging.AddProvider(logs);
                // Sağlayıcıya özel kural, appsettings'teki genel seviye kurallarından önce gelir.
                logging.AddFilter<LogCapture>(null, LogLevel.Trace);
            });
        }
    }
}
