using System.Diagnostics;
using System.Text.Json;
using System.Text.RegularExpressions;
using CargoService.Data;
using CargoService.Messaging;
using CargoService.Messaging.Contracts;

namespace CargoService.Shipping;

/// <summary>
/// Gönderi durum olaylarının outbox satırlarını üretir. Satır, durum değişikliğiyle AYNI işlemde
/// (aynı DbContext'e eklenerek) yazılmalıdır; commit sonrası <see cref="OutboxSignal.Notify"/> çağrılır.
/// Olay kişisel veri içermez (alıcı bilgisi olaya hiç konmaz).
/// </summary>
public static partial class ShipmentEvents
{
    /// <summary>Hangi durum hangi olayı doğurur. REQUESTED ve SENDING ara durumdur, olay üretmez.</summary>
    private static readonly Dictionary<ShipmentStatus, (string Type, string RoutingKey)> Events = new()
    {
        [ShipmentStatus.Created] = ("ShipmentCreated", CargoTopology.ShipmentCreated),
        [ShipmentStatus.InTransit] = ("ShipmentInTransit", CargoTopology.ShipmentInTransit),
        [ShipmentStatus.Delivered] = ("ShipmentDelivered", CargoTopology.ShipmentDelivered),
        [ShipmentStatus.DeliveryFailed] = ("ShipmentDeliveryFailed", CargoTopology.ShipmentDeliveryFailed),
        // Kalıcı başarısızlık ve ret aynı olayla gider; ayrım data.status'tadır ("FAILED" / "REJECTED").
        [ShipmentStatus.Failed] = ("ShipmentFailed", CargoTopology.ShipmentFailed),
        [ShipmentStatus.Rejected] = ("ShipmentFailed", CargoTopology.ShipmentFailed),
    };

    /// <summary>
    /// Gönderinin şu anki durumu için olay satırını <paramref name="db"/>'ye ekler ve
    /// <see cref="Shipment.EventSequence"/>'i bir artırır. Durum olay üretmiyorsa hiçbir şey yapmaz, null döner.
    /// <paramref name="traceId"/> geçerli bir W3C trace id değilse yeni bir tane üretilir.
    /// </summary>
    public static OutboxMessage? Append(CargoDbContext db, Shipment shipment, DateTimeOffset statusAt, string? traceId)
    {
        if (!Events.TryGetValue(shipment.Status, out var evt))
        {
            return null;
        }
        shipment.EventSequence++;
        var messageId = Guid.CreateVersion7();
        var payload = new ShipmentEvent(
            MessageId: messageId.ToString(),
            Type: evt.Type,
            Version: CargoTopology.EventVersion,
            OccurredAt: DateTimeOffset.UtcNow,
            TraceId: ValidTraceId(traceId) ?? ActivityTraceId.CreateRandom().ToHexString(),
            Sequence: shipment.EventSequence,
            Data: new ShipmentEventData(
                OrderId: shipment.OrderId,
                ShipmentId: shipment.Id,
                RequestNo: shipment.RequestNo,
                Status: ShipmentStatusConverter.ToDb(shipment.Status),
                CarrierCode: shipment.CarrierCode,
                CarrierName: shipment.CarrierName,
                TrackingNumber: shipment.TrackingNumber,
                StatusAt: statusAt,
                FailureReason: shipment.FailureReason));

        var row = new OutboxMessage
        {
            Id = messageId,
            Exchange = CargoTopology.Exchange,
            RoutingKey = evt.RoutingKey,
            Type = evt.Type,
            Payload = JsonSerializer.Serialize(payload, JsonSerializerOptions.Web),
            CreatedAt = DateTimeOffset.UtcNow,
        };
        db.OutboxMessages.Add(row);
        return row;
    }

    private static string? ValidTraceId(string? traceId) =>
        traceId is not null && TraceIdPattern().IsMatch(traceId) && traceId.Any(c => c != '0') ? traceId : null;

    [GeneratedRegex("^[0-9a-f]{32}$")]
    private static partial Regex TraceIdPattern();
}
