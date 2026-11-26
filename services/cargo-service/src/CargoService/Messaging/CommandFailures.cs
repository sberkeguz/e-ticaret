using System.Buffers.Text;
using System.Net.Sockets;
using Npgsql;

namespace CargoService.Messaging;

/// <summary>Kargo komutu işlenirken çıkan hatanın türü; tüketicinin ne yapacağını belirler.</summary>
public enum CommandFailureKind
{
    /// <summary>Bağlantı, zaman aşımı, serileştirme/kilitlenme (40001/40P01) vb.: bekleyip sınırsız tekrar denenir.</summary>
    Transient,

    /// <summary>Veritabanının her seferinde aynı şekilde reddettiği veri (SqlState sınıf 22/23): hemen DLQ.</summary>
    Permanent,

    /// <summary>Tanınmayan hata: sayılarak tekrar denenir, sınır aşılınca DLQ.</summary>
    Unknown,
}

/// <summary>
/// Komut işleme hatalarının sınıflandırılması, deneme sayacı başlığı ve servisin TÜM loglarında ve
/// <c>outbox_messages.last_error</c>'da kullanılan hata özeti (<see cref="Describe"/>).
/// </summary>
public static class CommandFailures
{
    /// <summary>
    /// Tanınmayan hatayla kaç kez işlenemediğini taşıyan AMQP başlığı. Kuyruk klasik (quorum değil) ve
    /// argümanları backend ile ortak olduğundan RabbitMQ'nun teslim sayacı kullanılamaz; tüketici mesajın
    /// kopyasını bu başlığı artırarak kuyruğa yeniden yayınlar.
    /// </summary>
    public const string AttemptsHeader = "x-cargo-attempts";

    /// <summary>
    /// İstisna zincirini (InnerException'lar) gezer. Önce kalıcı veri hatası aranır (PostgresException
    /// SqlState sınıf 22 "veri" ya da 23 "kısıt"), sonra geçici hata (Npgsql'in IsTransient'ı — bağlantı,
    /// 40001, 40P01, 53xxx, 57P0x dahil — ya da zaman aşımı/soket/G-Ç/iptal). Hiçbiri değilse Unknown.
    /// </summary>
    public static CommandFailureKind Classify(Exception ex)
    {
        var chain = Chain(ex).ToList();
        if (chain.OfType<PostgresException>().Any(pg => pg.SqlState.StartsWith("22", StringComparison.Ordinal)
                                                        || pg.SqlState.StartsWith("23", StringComparison.Ordinal)))
        {
            return CommandFailureKind.Permanent;
        }
        return chain.Any(IsTransient) ? CommandFailureKind.Transient : CommandFailureKind.Unknown;
    }

    /// <summary>
    /// Loga yazılabilir özet: zincirdeki istisna TİPLERİ ve varsa SqlState (ör. "DbUpdateException →
    /// PostgresException 22P05"). İstisna mesajı, Detail ya da Where YAZILMAZ: satır verisi (alıcı adresi)
    /// taşıyabilirler.
    /// </summary>
    public static string Describe(Exception ex) =>
        string.Join(" → ", Chain(ex).Select(e => e is PostgresException pg ? $"{e.GetType().Name} {pg.SqlState}" : e.GetType().Name));

    /// <summary>
    /// Başlıktaki deneme sayısı, [0, <see cref="ShipmentRequestsConsumer.MaxUnknownAttempts"/>] aralığına
    /// sıkıştırılmış olarak. Yoksa, okunamıyorsa ya da negatifse 0; sınırdan büyükse (ör. int.MaxValue) sınır —
    /// böylece +1 taşıp negatife dönemez ve mesaj ilk tanınmayan hatada DLQ'ya gider.
    /// </summary>
    public static int ReadAttempts(IDictionary<string, object?>? headers)
    {
        if (headers is null || !headers.TryGetValue(AttemptsHeader, out var value))
        {
            return 0;
        }
        long attempts = value switch
        {
            int i => i,
            long l => l,
            byte[] bytes when Utf8Parser.TryParse(bytes, out long parsed, out var used) && used == bytes.Length => parsed,
            string s when long.TryParse(s, out var parsed) => parsed,
            _ => 0,
        };
        return (int)Math.Clamp(attempts, 0, ShipmentRequestsConsumer.MaxUnknownAttempts);
    }

    private static bool IsTransient(Exception e) => e switch
    {
        NpgsqlException npgsql => npgsql.IsTransient,
        TimeoutException or SocketException or IOException or OperationCanceledException => true,
        _ => false,
    };

    private static IEnumerable<Exception> Chain(Exception ex)
    {
        for (var e = ex; e is not null; e = e.InnerException)
        {
            yield return e;
        }
    }
}
