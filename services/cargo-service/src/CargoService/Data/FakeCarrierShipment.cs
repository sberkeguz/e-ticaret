namespace CargoService.Data;

/// <summary>
/// Sahte kargo firmasının kalıcı durumu (cargo.fake_carrier_shipments). Aynı tekrar anahtarıyla gelen
/// ikinci istek yeni gönderi açmaz; servis yeniden başlasa da firma tarafındaki durum korunur.
/// </summary>
public sealed class FakeCarrierShipment
{
    public string IdempotencyKey { get; set; } = "";

    public string TrackingNumber { get; set; } = "";

    public string Status { get; set; } = "";

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }
}
