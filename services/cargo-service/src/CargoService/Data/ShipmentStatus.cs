using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

namespace CargoService.Data;

/// <summary>Gönderinin yaşam döngüsü. Veritabanında büyük harfli metin olarak (ör. "IN_TRANSIT") saklanır.</summary>
public enum ShipmentStatus
{
    Requested,
    Sending,
    Created,
    InTransit,
    Delivered,
    DeliveryFailed,
    Failed,
    Rejected,
}

/// <summary>ShipmentStatus ↔ veritabanı metni ("REQUESTED", "IN_TRANSIT", ...) dönüşümü.</summary>
public sealed class ShipmentStatusConverter() : ValueConverter<ShipmentStatus, string>(
    status => ToDb(status),
    value => FromDb(value))
{
    private static readonly Dictionary<ShipmentStatus, string> Names = new()
    {
        [ShipmentStatus.Requested] = "REQUESTED",
        [ShipmentStatus.Sending] = "SENDING",
        [ShipmentStatus.Created] = "CREATED",
        [ShipmentStatus.InTransit] = "IN_TRANSIT",
        [ShipmentStatus.Delivered] = "DELIVERED",
        [ShipmentStatus.DeliveryFailed] = "DELIVERY_FAILED",
        [ShipmentStatus.Failed] = "FAILED",
        [ShipmentStatus.Rejected] = "REJECTED",
    };

    private static readonly Dictionary<string, ShipmentStatus> Values =
        Names.ToDictionary(pair => pair.Value, pair => pair.Key, StringComparer.Ordinal);

    /// <summary>Veritabanında izin verilen tüm durum metinleri (CHECK kısıtı için).</summary>
    public static IReadOnlyCollection<string> AllDbValues => Names.Values;

    public static string ToDb(ShipmentStatus status) => Names[status];

    public static ShipmentStatus FromDb(string value) =>
        Values.TryGetValue(value, out var status)
            ? status
            : throw new InvalidOperationException($"Bilinmeyen gönderi durumu: {value}");
}
