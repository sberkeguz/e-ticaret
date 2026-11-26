namespace CargoService.Messaging.Contracts;

/// <summary>
/// Backend'in "kargoya ver" komutu (v1), routing key <c>commands.shipment.create</c>. JSON camelCase.
/// Sürüm kuralı: alanlar silinmez, yalnızca eklenir; tanınmayan alanlar yok sayılır, bu yüzden
/// <c>version &gt;= 1</c> kabul edilir. Alanlar JSON'dan eksik gelebileceği için null olabilir;
/// <see cref="Validate"/> geçmeyen komut işlenmez (DLQ'ya gider).
/// </summary>
public sealed record ShipmentRequested
{
    public const string MessageType = "ShipmentRequested";

    public string? MessageId { get; init; }
    public string? Type { get; init; }
    public int Version { get; init; }
    public DateTimeOffset? OccurredAt { get; init; }
    public string? TraceId { get; init; }
    public long OrderId { get; init; }

    /// <summary>Backend'in bu sipariş için kaçıncı kargo isteği olduğu (yeniden deneme 2, 3, ...).</summary>
    public int RequestNo { get; init; }

    /// <summary>Sözleşme gereği tam olarak <c>order-{orderId}</c>.</summary>
    public string? IdempotencyKey { get; init; }

    /// <summary>Backend'deki ödeme durumu; yalnızca "PAID" kargoya verilir.</summary>
    public string? PaymentStatus { get; init; }

    public Recipient? Recipient { get; init; }
    public Parcel? Parcel { get; init; }

    /// <summary>
    /// Geçersiz alanların ADLARINI döner (boşsa komut geçerlidir). Değerler asla dönmez.
    /// Veritabanına ya da olaya yazılan metinler (messageId, traceId, alıcı alanları)
    /// <see cref="SafeText"/> kurallarına da uymalıdır.
    /// </summary>
    public IReadOnlyList<string> Validate()
    {
        var errors = new List<string>();
        if (string.IsNullOrWhiteSpace(MessageId) || MessageId.Length > 100 || !SafeText.IsSafe(MessageId)) errors.Add("messageId");
        if (!SafeText.IsSafe(TraceId)) errors.Add("traceId");
        if (Type != MessageType) errors.Add("type");
        if (Version < 1) errors.Add("version");
        if (OccurredAt is null) errors.Add("occurredAt");
        if (OrderId <= 0) errors.Add("orderId");
        if (RequestNo < 1) errors.Add("requestNo");
        if (IdempotencyKey != $"order-{OrderId}") errors.Add("idempotencyKey");
        if (string.IsNullOrWhiteSpace(PaymentStatus)) errors.Add("paymentStatus");
        if (Recipient is null) errors.Add("recipient");
        else errors.AddRange(Recipient.InvalidFields());
        if (Parcel is null) errors.Add("parcel");
        else
        {
            if (Parcel.ItemCount < 1) errors.Add("parcel.itemCount");
            if (Parcel.DeclaredValue is null or < 0) errors.Add("parcel.declaredValue");
        }
        return errors;
    }
}

/// <summary>Koli bilgisi: ürün adedi ve beyan edilen değer (TL; ondalık, float değil).</summary>
public sealed record Parcel
{
    public int ItemCount { get; init; }
    public decimal? DeclaredValue { get; init; }
}
