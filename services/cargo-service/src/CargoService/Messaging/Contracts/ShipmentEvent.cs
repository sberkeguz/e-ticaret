namespace CargoService.Messaging.Contracts;

/// <summary>
/// Bu servisin yayınladığı gönderi olayı (v1). KİŞİSEL VERİ İÇERMEZ (alıcı adı, telefon, adres yok).
/// <see cref="Sequence"/> gönderi başına artar (<c>shipments.event_sequence</c>); dinleyici elindekinden
/// eski numaralı olayı uygulamaz. Sürüm kuralı: alan silinmez, yalnızca eklenir.
/// </summary>
public sealed record ShipmentEvent(
    string MessageId,
    string Type,
    int Version,
    DateTimeOffset OccurredAt,
    string TraceId,
    int Sequence,
    ShipmentEventData Data);

/// <summary>Olayın verisi. <see cref="Status"/> veritabanındaki metindir (ör. "IN_TRANSIT", "REJECTED").</summary>
public sealed record ShipmentEventData(
    long OrderId,
    Guid ShipmentId,
    int RequestNo,
    string Status,
    string? CarrierCode,
    string? CarrierName,
    string? TrackingNumber,
    DateTimeOffset StatusAt,
    string? FailureReason);
