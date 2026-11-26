using CargoService.Configuration;
using CargoService.Data;
using CargoService.Messaging;
using CargoService.Shipping;
using CargoService.Carriers;
using CargoService.Carriers.Fake;
using Microsoft.AspNetCore.Diagnostics.HealthChecks;

var builder = WebApplication.CreateBuilder(args);

// Ayarlar: ConnectionStrings:Cargo yoksa ya da Carrier:Provider yok/tanınmıyorsa host başlarken OptionsValidationException ile durur.
CargoOptions.Register(builder.Services);

// Bağlantı dizesi ayarlardan çözümlenir; böylece eksik ayar DI kaydında değil açılış doğrulamasında yakalanır.
builder.Services.AddDbContext<CargoDbContext>((services, options) =>
    CargoDbContext.Configure(options, CargoOptions.From(services).ConnectionString));

builder.Services.AddHostedService<MigrationHostedService>();

// RabbitMQ: tek bağlantı (kopunca yeniden kurulur), outbox yayıncısı ve commit sonrası uyandırma sinyali.
// Yayıncı tekil kaydedilir ki testler aynı örneği çözüp RunOnceAsync çağırabilsin; arka plan döngüsü
// Workers:Enabled=false iken hiç çalışmaz.
builder.Services.AddSingleton<RabbitConnectionProvider>();
builder.Services.AddSingleton<OutboxSignal>();
builder.Services.AddSingleton<OutboxRelay>();
builder.Services.AddHostedService(services => services.GetRequiredService<OutboxRelay>());

// Kargo komutları: kuyruk tüketicisi (Workers:Enabled=false iken dinlemez) ve işlem başına intake.
builder.Services.AddScoped<ShipmentIntake>();
builder.Services.AddSingleton<ShipmentRequestsConsumer>();
builder.Services.AddHostedService(services => services.GetRequiredService<ShipmentRequestsConsumer>());

// Gönderi işleri: firmada açma (intake sinyaliyle uyanır), takılı/belirsiz kayıtları uzlaştırma, takip
// sorgusu ve günlük temizlik. Hepsi tekil kaydedilir ki testler RunOnceAsync çağırabilsin; arka plan
// döngüleri Workers:Enabled=false iken çalışmaz. Metrikler (cargo_shipments_uncertain) IMeterFactory ile
// "CargoService" Meter'ında yayınlanır; dışarı aktarım (OTLP) henüz kurulu değil.
builder.Services.AddMetrics();
builder.Services.AddSingleton<ShipmentSignal>();
builder.Services.AddSingleton<ShipmentOutcomes>();
builder.Services.AddSingleton<ShipmentWorker>();
builder.Services.AddHostedService(services => services.GetRequiredService<ShipmentWorker>());
builder.Services.AddSingleton<ShipmentReconciler>();
builder.Services.AddHostedService(services => services.GetRequiredService<ShipmentReconciler>());
builder.Services.AddSingleton<TrackingPoller>();
builder.Services.AddHostedService(services => services.GetRequiredService<TrackingPoller>());
builder.Services.AddSingleton<Cleanup>();
builder.Services.AddHostedService(services => services.GetRequiredService<Cleanup>());

// Kargo firması: şimdilik yalnız sahte firma var. Carrier:Provider zorunludur ve tanınan bir değerle
// birebir eşleşmelidir; değilse servis açılış doğrulamasında durur (CargoOptions.Register). Aşağıdaki
// hata yalnızca ikinci bir savunmadır: yeni bir sağlayıcı SupportedProviders'a eklenip burada
// bağlanmazsa sahte firmaya sessizce düşülmez.
builder.Services.AddSingleton<FakeCarrierFaults>();
builder.Services.AddSingleton<FakeCarrierGateway>();
builder.Services.AddSingleton<ICarrierGateway>(services => CargoOptions.From(services).Carrier.IsFake
    ? services.GetRequiredService<FakeCarrierGateway>()
    : throw new InvalidOperationException(
        "Carrier:Provider için kargo firması bağlantısı yok; şu an yalnızca \"Fake\" destekleniyor."));

// Hazır olma yalnızca veritabanına bağlıdır; RabbitMQ kapalıyken de servis "ready" sayılır
// (outbox mesajları birikir, bağlantı gelince yayınlanır).
builder.Services.AddHealthChecks()
    .AddDbContextCheck<CargoDbContext>("database", tags: ["ready"]);

var app = builder.Build();

// Canlılık: süreç ayaktaysa 200; hiçbir bağımlılığı denetlemez.
app.MapHealthChecks("/health/live", new HealthCheckOptions { Predicate = _ => false });

// Hazır olma: veritabanına bağlanılabiliyorsa 200, bağlanılamıyorsa 503.
app.MapHealthChecks("/health/ready", new HealthCheckOptions { Predicate = check => check.Tags.Contains("ready") });

app.MapFakeCarrierEndpoints();

await app.RunAsync();

/// <summary>WebApplicationFactory&lt;Program&gt; için görünür kılınır.</summary>
public partial class Program;
