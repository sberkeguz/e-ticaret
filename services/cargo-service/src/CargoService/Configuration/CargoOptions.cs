using Microsoft.Extensions.Options;

namespace CargoService.Configuration;

/// <summary>
/// Servisin tüm ayarları. <see cref="ConnectionString"/> ve <c>Carrier:Provider</c> zorunludur ve
/// açılışta doğrulanır (Provider tanınan bir değerle birebir eşleşmeli); eksik ya da geçersizse servis
/// anlaşılır bir hatayla durur (güvensiz varsayılan bağlantı ya da
/// sessizce seçilen sahte kargo firması yoktur).
/// RabbitMq ve Workers arka plan işlerince, Carrier kargo firması katmanınca kullanılır.
/// </summary>
public sealed class CargoOptions
{
    /// <summary>Yapılandırmadaki anahtar; ortam değişkeni olarak <c>ConnectionStrings__Cargo</c>.</summary>
    public const string ConnectionStringKey = "ConnectionStrings:Cargo";

    public const string CarrierProviderKey = "Carrier:Provider";

    /// <summary>cargo_app rolüyle bağlanan Npgsql bağlantı dizesi (<c>ConnectionStrings:Cargo</c>).</summary>
    public string ConnectionString { get; set; } = "";

    public DatabaseSettings Database { get; set; } = new();

    public RabbitMqSettings RabbitMq { get; set; } = new();

    public CarrierSettings Carrier { get; set; } = new();

    public WorkerSettings Workers { get; set; } = new();

    /// <summary>
    /// İç uçların paylaşılan sırrı (<c>X-Internal-Token</c> başlığıyla karşılaştırılır). Boşsa bu sırla
    /// korunan uçlar (ör. sahte kargo firması test uçları) hiç eşlenmez. Loglanmaz.
    /// </summary>
    public string? InternalApiToken { get; set; }

    /// <summary>Ayarları yapılandırmadan okur; bağlantı dizesini ve kargo firması sağlayıcısını açılışta (ValidateOnStart) doğrular.</summary>
    public static void Register(IServiceCollection services)
    {
        services.AddOptions<CargoOptions>()
            .Configure<IConfiguration>((options, configuration) =>
            {
                configuration.Bind(options);
                options.ConnectionString = configuration.GetConnectionString("Cargo") ?? "";
            })
            .Validate(
                options => !string.IsNullOrWhiteSpace(options.ConnectionString),
                $"{ConnectionStringKey} zorunludur (ortam değişkeni: ConnectionStrings__Cargo). " +
                "Kargo servisi veritabanı bağlantısı olmadan açılmaz.")
            .Validate(
                options => !string.IsNullOrWhiteSpace(options.Carrier.Provider),
                $"{CarrierProviderKey} zorunludur (ortam değişkeni: Carrier__Provider). Varsayılan yoktur; " +
                $"şu an yalnızca \"{CarrierSettings.FakeProvider}\" (sahte kargo firması) destekleniyor.")
            .Validate(
                options => string.IsNullOrWhiteSpace(options.Carrier.Provider) || options.Carrier.IsSupported,
                $"{CarrierProviderKey} tanınmıyor (ortam değişkeni: Carrier__Provider). Büyük/küçük harf ve boşluk " +
                $"dahil tam eşleşme gerekir; şu an yalnızca \"{CarrierSettings.FakeProvider}\" destekleniyor.")
            .Validate(
                options => options.Workers.UndeliveredRecipientRetention > TimeSpan.Zero,
                "Workers:UndeliveredRecipientRetention sıfırdan büyük olmalıdır.")
            .ValidateOnStart();
    }

    /// <summary>Doğrulanmış ayarlar; doğrulama başarısızsa <see cref="OptionsValidationException"/> fırlatır.</summary>
    public static CargoOptions From(IServiceProvider services) =>
        services.GetRequiredService<IOptions<CargoOptions>>().Value;
}

public sealed class DatabaseSettings
{
    /// <summary>true ise açılışta bekleyen EF Core migration'ları uygulanır; hata olursa servis açılmaz.</summary>
    public bool MigrateOnStartup { get; set; } = true;
}

public sealed class RabbitMqSettings
{
    /// <summary>
    /// amqp(s):// adresi. Boşsa olaylar outbox'ta bekler ve komut kuyruğu dinlenmez; sağlık durumunu
    /// etkilemez. Parola içerebileceği için loglanmaz.
    /// </summary>
    public string? Url { get; set; }
}

public sealed class CarrierSettings
{
    public const string FakeProvider = "Fake";

    /// <summary>Tanınan sağlayıcılar; <see cref="Provider"/> bunlardan biriyle BİREBİR (ordinal) eşleşmelidir.</summary>
    public static readonly IReadOnlyList<string> SupportedProviders = [FakeProvider];

    /// <summary>
    /// Kargo firması sağlayıcısı; ZORUNLU, varsayılanı yoktur. Boşsa ya da <see cref="SupportedProviders"/>
    /// içinde birebir yoksa ("fake", " Fake", "Fakee" dahil) servis açılışta doğrulama hatasıyla durur.
    /// Şu an yalnızca "Fake" (sahte firma) vardır.
    /// </summary>
    public string Provider { get; set; } = "";

    public FakeCarrierSettings Fake { get; set; } = new();

    public bool IsSupported => SupportedProviders.Contains(Provider, StringComparer.Ordinal);

    public bool IsFake => string.Equals(Provider, FakeProvider, StringComparison.Ordinal);
}

/// <summary>Sahte kargo firmasının davranışı (yalnızca Provider=Fake iken kullanılır).</summary>
public sealed class FakeCarrierSettings
{
    /// <summary>Her firma çağrısından önce beklenen süre (ms); gerçek firma gecikmesini taklit eder.</summary>
    public int DelayMs { get; set; }

    /// <summary>Açılıştaki hata modu; test ucuyla çalışırken değiştirilebilir.</summary>
    public FakeFailMode FailMode { get; set; } = FakeFailMode.None;

    /// <summary><see cref="FailMode"/>'un kaç gönderi açma çağrısına uygulanacağı; bitince None'a döner.</summary>
    public int FailTimes { get; set; }

    /// <summary>
    /// true ise /test/fake-carrier/* uçları eşlenir — yalnızca Provider=Fake ve InternalApiToken dolu
    /// iken. Varsayılan kapalı.
    /// </summary>
    public bool TestEndpointsEnabled { get; set; }
}

/// <summary>Sahte firmanın gönderi açarken taklit ettiği hata.</summary>
public enum FakeFailMode
{
    None,

    /// <summary>Gönderi AÇILMADAN zaman aşımı: sonuç belirsiz, firmada kayıt yok.</summary>
    TimeoutBeforeCreate,

    /// <summary>Gönderi AÇILDIKTAN sonra zaman aşımı: sonuç belirsiz, ama firmada kayıt var.</summary>
    TimeoutAfterCreate,

    /// <summary>Firma isteği kesin reddeder; kayıt açılmaz.</summary>
    Permanent,
}

public sealed class WorkerSettings
{
    /// <summary>
    /// Arka plan işlerinin (outbox yayıncısı, komut tüketicisi, gönderi açma, uzlaştırma, takip sorgusu,
    /// temizlik) açık olup olmadığı; testlerde kapalı (testler RunOnceAsync ile tur attırır).
    /// </summary>
    public bool Enabled { get; set; } = true;

    /// <summary>
    /// Gönderi açma işinin (ShipmentWorker) yedek tur aralığı. Yeni REQUESTED gönderi commit edilince iş
    /// beklemeden uyanır; sinyal kaçarsa gönderi en geç bu sürede ele alınır.
    /// </summary>
    public TimeSpan SendInterval { get; set; } = TimeSpan.FromSeconds(30);

    /// <summary>Uzlaştırıcının (ShipmentReconciler) tur aralığı: takılı/belirsiz SENDING kayıtlarına bakar.</summary>
    public TimeSpan ReconcileInterval { get; set; } = TimeSpan.FromMinutes(1);

    /// <summary>
    /// SENDING'e geçtikten bu kadar süre sonra hâlâ SENDING olan kayıt "takılı" sayılır ve uzlaştırıcı
    /// firmada tekrar anahtarıyla arar (servis firma çağrısı sırasında ölmüş olabilir).
    /// </summary>
    public TimeSpan SendingStuckAfter { get; set; } = TimeSpan.FromMinutes(2);

    /// <summary>
    /// Takip sorgusu aralığı: CREATED/IN_TRANSIT/DELIVERY_FAILED gönderiler firmadan en fazla bu
    /// sıklıkla sorgulanır (aynı zamanda tur aralığıdır). DELIVERY_FAILED gönderi
    /// <see cref="UndeliveredRecipientRetention"/> boyunca değişmezse artık sorgulanmaz.
    /// </summary>
    public TimeSpan PollInterval { get; set; } = TimeSpan.FromMinutes(5);

    /// <summary>
    /// Sonucu belirsiz gönderi açma denemeleri arasındaki temel bekleme; her denemede iki katına çıkar
    /// (en fazla 1 saat).
    /// </summary>
    public TimeSpan RetryBaseDelay { get; set; } = TimeSpan.FromSeconds(30);

    /// <summary>Temizlik işinin (eski inbox/outbox satırları, gönderilerin alıcı bilgisi) aralığı.</summary>
    public TimeSpan CleanupInterval { get; set; } = TimeSpan.FromDays(1);

    /// <summary>
    /// Teslim edilemeyen (DELIVERY_FAILED) ya da firmada açılamayan (FAILED) gönderide, son değişiklikten
    /// (<c>updated_at</c>) bu kadar süre sonra alıcı bilgisi silinir (varsayılan 90 gün; sıfırdan büyük
    /// olmalıdır). Aynı süreden sonra DELIVERY_FAILED gönderi firmadan sorgulanmaz. Gönderi satırı ve
    /// olayları silinmez.
    /// </summary>
    public TimeSpan UndeliveredRecipientRetention { get; set; } = TimeSpan.FromDays(90);

    /// <summary>
    /// Outbox yayıncısının yedek tur aralığı: commit sonrası sinyal kaçsa bile olay en geç bu sürede gider.
    /// </summary>
    public TimeSpan OutboxInterval { get; set; } = TimeSpan.FromSeconds(1);
}
