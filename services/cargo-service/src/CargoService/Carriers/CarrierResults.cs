using System.Net;
using System.Net.Sockets;
using CargoService.Data;

namespace CargoService.Carriers;

/// <summary>
/// Firmada gönderi açma sonucu. Üç durum bilerek ayrıdır:
///  - <see cref="Created"/>: firma gönderiyi açtı, takip numarası belli.
///  - <see cref="PermanentFailure"/>: firma isteği kesin reddetti (ör. geçersiz adres); aynı istekle
///    tekrar denemek işe yaramaz.
///  - <see cref="Uncertain"/>: sonuç BİLİNMİYOR (zaman aşımı, 5xx, ağ hatası). Gönderi firmada açılmış
///    olabilir; tekrar açmadan önce <see cref="ICarrierGateway.FindByReferenceAsync"/> ile bakılmalıdır.
/// </summary>
public abstract record CarrierCreateResult
{
    private CarrierCreateResult()
    {
    }

    public sealed record Created(string TrackingNumber) : CarrierCreateResult;

    /// <summary><paramref name="Reason"/> kullanıcıya gösterilebilir sade Türkçe özettir; kişisel veri içermez.</summary>
    public sealed record PermanentFailure(string Reason) : CarrierCreateResult;

    /// <summary><paramref name="Error"/> teknik özettir; firmaya giden/gelen gövde içermez.</summary>
    public sealed record Uncertain(string Error) : CarrierCreateResult;
}

/// <summary>Tekrar anahtarıyla firmada gönderi arama sonucu.</summary>
public abstract record CarrierLookupResult
{
    private CarrierLookupResult()
    {
    }

    public sealed record Found(string TrackingNumber) : CarrierLookupResult;

    public sealed record NotFound : CarrierLookupResult;

    public sealed record Uncertain(string Error) : CarrierLookupResult;
}

/// <summary>Takip numarasıyla firmadan durum sorgulama sonucu.</summary>
public abstract record CarrierStatusResult
{
    private CarrierStatusResult()
    {
    }

    /// <summary>Firmadaki durum: Created, InTransit, Delivered ya da DeliveryFailed.</summary>
    public sealed record Status(ShipmentStatus Value, DateTimeOffset At) : CarrierStatusResult;

    public sealed record NotFound : CarrierStatusResult;

    public sealed record Uncertain(string Error) : CarrierStatusResult;
}

/// <summary>
/// Gerçek (HTTP) firma adaptörlerinin hata sınıflandırması: zaman aşımı, ağ hatası, 5xx, 408 ve 429
/// "belirsiz"dir (tekrar denenir, önce firmada aranır); diğer 4xx yanıtlar kalıcı başarısızlıktır.
/// </summary>
public static class CarrierErrors
{
    public static bool IsUncertain(HttpStatusCode status) =>
        (int)status >= 500 || status is HttpStatusCode.RequestTimeout or HttpStatusCode.TooManyRequests;

    /// <summary>
    /// İstisna belirsiz bir sonuç mu: zaman aşımı (TimeoutException ya da çağıranın iptali olmayan
    /// TaskCanceledException), ağ hatası (HttpRequestException/SocketException/IOException) ya da 5xx.
    /// <paramref name="callerToken"/> iptal edildiyse bu bir zaman aşımı değil, kapanıştır: false.
    /// </summary>
    public static bool IsUncertain(Exception ex, CancellationToken callerToken) => ex switch
    {
        OperationCanceledException => !callerToken.IsCancellationRequested,
        TimeoutException => true,
        HttpRequestException { StatusCode: { } status } => IsUncertain(status),
        HttpRequestException => true,
        SocketException or IOException => true,
        _ => false,
    };
}
