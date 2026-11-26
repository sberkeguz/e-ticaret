using System.Net.Sockets;
using System.Text;
using CargoService.Data;
using CargoService.Messaging;
using CargoService.Messaging.Contracts;
using CargoService.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Npgsql;
using static CargoService.Tests.Infrastructure.Shipments;

namespace CargoService.Tests;

/// <summary>
/// Docker gerektirmeyen birim testleri: komut alanlarındaki yasak karakterler, tüketicinin hata
/// sınıflandırması ve deneme sayacı başlığı, EF Core hata loglarının kapatılması, Carrier:Provider
/// doğrulaması. Davranışın uçtan uca kanıtı QaBreakTests'teki entegrasyon testleridir.
/// </summary>
public sealed class CommandTextValidationTests
{
    public static TheoryData<string> ForbiddenTexts => new()
    {
        "Sokak\u0000 No 1",   // jsonb reddeder (22P05)
        "Sokak \uD800 No 1",  // eşsiz yüksek vekil
        "Sokak \uDC00 No 1",  // eşsiz düşük vekil
        "Sokak \uFFFD No 1",  // yerine-koyma karakteri: JSON yazıcısı eşsiz vekili buna çevirir
        "Sokak \u0007 No 1",  // C0 kontrol karakteri
        "Sokak \u007F No 1",  // DEL
        "Sokak \u0085 No 1",  // C1 kontrol karakteri
        "Sokak \uFFFF No 1",  // Unicode karakter olmayan kod noktası
    };

    [Theory]
    [MemberData(nameof(ForbiddenTexts))]
    public void Alıcı_alanlarında_yasak_karakter_komutu_geçersiz_kılar(string text)
    {
        var fields = new Func<Recipient, Recipient>[]
        {
            r => r with { FirstName = text }, r => r with { LastName = text }, r => r with { Phone = text },
            r => r with { City = text }, r => r with { District = text }, r => r with { Neighborhood = text },
            r => r with { Address = text },
        };
        foreach (var change in fields)
        {
            var command = Command(1) with { Recipient = change(Recipient()) };
            var errors = command.Validate();
            Assert.Single(errors);
            Assert.StartsWith("recipient.", errors[0]);
            Assert.DoesNotContain("Sokak", errors[0]); // değer değil, yalnızca alan adı
        }
    }

    [Theory]
    [MemberData(nameof(ForbiddenTexts))]
    public void MessageId_ve_traceId_de_yasak_karakter_taşıyamaz(string text)
    {
        Assert.Equal(["messageId"], (Command(1) with { MessageId = text }).Validate());
        Assert.Equal(["traceId"], (Command(1) with { TraceId = text }).Validate());
    }

    [Fact]
    public void Türkçe_harfler_emoji_ve_satır_sonu_kabul_edilir()
    {
        var recipient = Recipient() with
        {
            FirstName = "Çağrı Şükrü", LastName = "Öğüt İnce", Address = "Gül sk. No:5\r\nDaire 3\t🙂",
        };
        Assert.Empty((Command(1) with { Recipient = recipient, TraceId = null }).Validate());
    }
}

public sealed class CommandFailureTests
{
    private static PostgresException Pg(string sqlState) => new("gizli adres Saklisokak", "ERROR", "ERROR", sqlState);

    [Theory]
    [InlineData("22P05")] // jsonb: desteklenmeyen Unicode kaçışı
    [InlineData("22021")] // geçersiz bayt dizisi
    [InlineData("22001")] // değer çok uzun
    [InlineData("23514")] // check kısıtı
    [InlineData("23505")] // tekil indeks
    public void Veri_ve_kısıt_hataları_kalıcıdır(string sqlState)
    {
        Assert.Equal(CommandFailureKind.Permanent, CommandFailures.Classify(Pg(sqlState)));
        Assert.Equal(CommandFailureKind.Permanent,
            CommandFailures.Classify(new DbUpdateException("kaydedilemedi", Pg(sqlState))));
    }

    [Theory]
    [InlineData("40001")] // serileştirme hatası
    [InlineData("40P01")] // kilitlenme
    [InlineData("08006")] // bağlantı koptu
    [InlineData("57P01")] // yönetici kapatması
    [InlineData("53300")] // çok fazla bağlantı
    public void Geçici_veritabanı_hataları_geçicidir(string sqlState)
    {
        Assert.Equal(CommandFailureKind.Transient, CommandFailures.Classify(Pg(sqlState)));
        Assert.Equal(CommandFailureKind.Transient,
            CommandFailures.Classify(new DbUpdateException("kaydedilemedi", Pg(sqlState))));
    }

    [Fact]
    public void Bağlantı_ve_zaman_aşımı_hataları_geçicidir()
    {
        Assert.Equal(CommandFailureKind.Transient,
            CommandFailures.Classify(new NpgsqlException("bağlanılamadı", new SocketException(10061))));
        Assert.Equal(CommandFailureKind.Transient, CommandFailures.Classify(new TimeoutException()));
        Assert.Equal(CommandFailureKind.Transient, CommandFailures.Classify(new IOException()));
        Assert.Equal(CommandFailureKind.Transient, CommandFailures.Classify(new OperationCanceledException()));
    }

    [Fact]
    public void Tanınmayan_hatalar_sayılarak_denenir()
    {
        Assert.Equal(CommandFailureKind.Unknown, CommandFailures.Classify(new InvalidOperationException()));
        Assert.Equal(CommandFailureKind.Unknown, CommandFailures.Classify(Pg("42501"))); // yetki
        Assert.Equal(CommandFailureKind.Unknown, CommandFailures.Classify(new EncoderFallbackException()));
    }

    [Fact]
    public void Hata_özeti_yalnızca_tip_ve_SqlState_içerir()
    {
        var described = CommandFailures.Describe(new DbUpdateException("ayrıntı Saklisokak", Pg("22P05")));
        Assert.Contains("DbUpdateException", described);
        Assert.Contains("PostgresException", described);
        Assert.Contains("22P05", described);
        Assert.DoesNotContain("Saklisokak", described);
        Assert.DoesNotContain("gizli", described);
    }

    [Fact]
    public void Deneme_sayacı_başlığı_okunur_yoksa_ya_da_bozuksa_sıfırdır()
    {
        Assert.Equal(0, CommandFailures.ReadAttempts(null));
        Assert.Equal(0, CommandFailures.ReadAttempts(new Dictionary<string, object?>()));
        Assert.Equal(2, CommandFailures.ReadAttempts(new Dictionary<string, object?> { [CommandFailures.AttemptsHeader] = 2 }));
        Assert.Equal(3, CommandFailures.ReadAttempts(new Dictionary<string, object?> { [CommandFailures.AttemptsHeader] = 3L }));
        Assert.Equal(4, CommandFailures.ReadAttempts(new Dictionary<string, object?> { [CommandFailures.AttemptsHeader] = "4"u8.ToArray() }));
        Assert.Equal(0, CommandFailures.ReadAttempts(new Dictionary<string, object?> { [CommandFailures.AttemptsHeader] = "x"u8.ToArray() }));
        Assert.Equal(0, CommandFailures.ReadAttempts(new Dictionary<string, object?> { [CommandFailures.AttemptsHeader] = -7 }));
    }

    [Theory]
    [InlineData(6)]
    [InlineData(int.MaxValue)]
    [InlineData(long.MaxValue)]
    public void Sınır_üstü_deneme_sayısı_sınıra_sıkıştırılır_taşmaz(object header)
    {
        var headers = new Dictionary<string, object?> { [CommandFailures.AttemptsHeader] = header };
        Assert.Equal(ShipmentRequestsConsumer.MaxUnknownAttempts, CommandFailures.ReadAttempts(headers));
        headers[CommandFailures.AttemptsHeader] = Encoding.ASCII.GetBytes(header.ToString()!);
        Assert.Equal(ShipmentRequestsConsumer.MaxUnknownAttempts, CommandFailures.ReadAttempts(headers));
    }

    [Fact]
    public void Hata_özeti_istisna_mesajını_hiç_yazmaz()
    {
        var described = CommandFailures.Describe(new InvalidOperationException("satir: Saklisokak No 41"));
        Assert.Equal("InvalidOperationException", described);
    }
}

public sealed class EfErrorLoggingTests
{
    /// <summary>
    /// Bu olaylar istisnayı (PostgresException.Where gibi alanlarda satır verisiyle) loglar; kapalı olmalılar.
    /// Hassas veri loglaması da kapalı kalmalı.
    /// </summary>
    [Fact]
    public void Veri_taşıyabilen_EF_hata_olayları_kapalıdır_hassas_veri_loglaması_kapalıdır()
    {
        var builder = new DbContextOptionsBuilder<CargoDbContext>();
        CargoDbContext.Configure(builder, "Host=127.0.0.1;Database=x");
        var core = builder.Options.FindExtension<CoreOptionsExtension>()!;

        foreach (var eventId in new[]
                 {
                     CoreEventId.SaveChangesFailed, CoreEventId.QueryIterationFailed,
                     RelationalEventId.CommandError, RelationalEventId.TransactionError,
                 })
        {
            Assert.Equal(WarningBehavior.Ignore, core.WarningsConfiguration.GetBehavior(eventId));
        }
        Assert.False(core.IsSensitiveDataLoggingEnabled);
    }
}

public sealed class CarrierProviderConfigTests
{
    private const string UnreachableDb =
        "Host=127.0.0.1;Port=1;Database=eticaret;Username=cargo_app;Password=x;Timeout=3";

    private static TestApp App(string provider) => new(new Dictionary<string, string?>
    {
        ["ConnectionStrings:Cargo"] = UnreachableDb,
        ["Database:MigrateOnStartup"] = "false",
        ["Carrier:Provider"] = provider,
    });

    [Theory]
    [InlineData("Baska")]
    [InlineData("Fakee")]
    [InlineData(" Fake")]
    [InlineData("Fake ")]
    [InlineData("fake")]
    [InlineData("FAKE")]
    public void Tanınmayan_sağlayıcıyla_uygulama_açılmaz(string provider)
    {
        using var app = App(provider);

        var ex = Record.Exception(() => app.CreateClient());

        Assert.NotNull(ex);
        Assert.Contains("Carrier:Provider", ex.ToString());
    }

    [Fact]
    public void Tam_olarak_Fake_ile_uygulama_açılır()
    {
        using var app = App("Fake");
        using var client = app.CreateClient();
    }
}
