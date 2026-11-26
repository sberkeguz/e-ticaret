using CargoService.Messaging.Contracts;

namespace CargoService.Carriers;

/// <summary>
/// Kargo firması bağlantısı. Uygulamalar alıcı bilgisini ve firmaya giden/gelen gövdeyi LOGLAMAZ.
/// Aynı <c>idempotencyKey</c> ile tekrar edilen <see cref="CreateAsync"/> firmada ikinci gönderi
/// açmamalıdır (ilkinin takip numarası döner).
/// </summary>
public interface ICarrierGateway
{
    /// <summary>Olaylarda <c>carrierCode</c> olarak gider (ör. "FAKE").</summary>
    string CarrierCode { get; }

    /// <summary>Olaylarda <c>carrierName</c> olarak gider (ör. "Test Kargo").</summary>
    string CarrierName { get; }

    Task<CarrierCreateResult> CreateAsync(string idempotencyKey, Recipient recipient, Parcel parcel, CancellationToken ct);

    Task<CarrierLookupResult> FindByReferenceAsync(string idempotencyKey, CancellationToken ct);

    Task<CarrierStatusResult> GetStatusAsync(string trackingNumber, CancellationToken ct);
}
