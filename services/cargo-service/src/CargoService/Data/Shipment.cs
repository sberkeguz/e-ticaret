namespace CargoService.Data;

/// <summary>Bir siparişin kargo gönderisi (cargo.shipments). Sipariş başına en fazla bir satır.</summary>
public sealed class Shipment
{
    public Guid Id { get; set; }

    /// <summary>Backend'deki sipariş numarası; tekil.</summary>
    public long OrderId { get; set; }

    /// <summary>Kargo firmasına giden isteğin tekrar anahtarı (ör. "order-123"); tekil.</summary>
    public string IdempotencyKey { get; set; } = "";

    /// <summary>Backend'in bu sipariş için gönderdiği kargo isteğinin sıra numarası.</summary>
    public int RequestNo { get; set; }

    public ShipmentStatus Status { get; set; }

    public string? CarrierCode { get; set; }

    public string? CarrierName { get; set; }

    public string? TrackingNumber { get; set; }

    /// <summary>Kalıcı başarısızlık/ret nedeni (kullanıcıya gösterilebilir özet).</summary>
    public string? FailureReason { get; set; }

    /// <summary>Son teknik hata (tekrar denemelerde güncellenir). Kişisel veri yazılmaz.</summary>
    public string? LastError { get; set; }

    /// <summary>
    /// Alıcı bilgileri (jsonb). KİŞİSEL VERİDİR: loglanmaz, olaylara konmaz; gerekmediğinde null'lanabilir.
    /// </summary>
    public string? Recipient { get; set; }

    /// <summary>
    /// Koli bilgisi (jsonb: ürün adedi, beyan edilen değer); firmada gönderi açarken kullanılır.
    /// Kişisel veri değildir ama firmaya giden gövdenin parçasıdır: loglanmaz, olaylara konmaz.
    /// </summary>
    public string? Parcel { get; set; }

    /// <summary>Bu gönderi için yayınlanan son olayın sıra numarası (dinleyiciler eski olayı bununla eler).</summary>
    public int EventSequence { get; set; }

    public int Attempts { get; set; }

    public DateTimeOffset? NextAttemptAt { get; set; }

    /// <summary>Firmaya istek gönderilmeye başlandığı an (SENDING'de takılı kalanları bulmak için).</summary>
    public DateTimeOffset? SendingSince { get; set; }

    public DateTimeOffset? LastPolledAt { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    /// <summary>Gönderinin kargo firmasında oluşturulduğu an.</summary>
    public DateTimeOffset? CreatedAtCarrier { get; set; }

    public DateTimeOffset? InTransitAt { get; set; }

    public DateTimeOffset? DeliveredAt { get; set; }

    public DateTimeOffset? DeliveryFailedAt { get; set; }

    /// <summary>PostgreSQL xmin sistem sütunu; iyimser eşzamanlılık belirteci.</summary>
    public uint Version { get; set; }
}
