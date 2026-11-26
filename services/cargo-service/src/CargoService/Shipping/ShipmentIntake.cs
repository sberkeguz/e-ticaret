using System.Text.Json;
using CargoService.Data;
using CargoService.Messaging;
using CargoService.Messaging.Contracts;
using Microsoft.EntityFrameworkCore;
using Npgsql;

namespace CargoService.Shipping;

/// <summary>Bir kargo komutunun işlenme sonucu.</summary>
public enum IntakeOutcome
{
    /// <summary>Bu messageId daha önce işlenmiş; hiçbir şey yapılmadı.</summary>
    Duplicate,

    /// <summary>Yeni gönderi REQUESTED olarak açıldı.</summary>
    Requested,

    /// <summary>Ödemesi onaylanmamış sipariş: REJECTED + <c>shipment.failed</c> olayı.</summary>
    Rejected,

    /// <summary>FAILED/REJECTED gönderi, daha yeni bir requestNo ile yeniden REQUESTED'a çekildi.</summary>
    Reopened,

    /// <summary>Gönderi zaten var ve komut onu değiştirmiyor (eski requestNo ya da süren gönderi).</summary>
    Ignored,
}

/// <summary>
/// "Kargoya ver" komutunu tek bir veritabanı işleminde kaydeder:
///  1. messageId'yi <c>processed_messages</c>'a ekler (ON CONFLICT DO NOTHING); eklenemediyse komut daha
///     önce işlenmiştir → commit, <see cref="IntakeOutcome.Duplicate"/>.
///  2. Siparişin gönderisini <c>FOR UPDATE</c> ile kilitler.
///     - Yoksa: paymentStatus "PAID" değilse REJECTED + shipment.failed olayı; PAID ise REQUESTED (alıcı jsonb'ye).
///     - Varsa: yalnızca komutun requestNo'su daha büyükse VE durum FAILED/REJECTED ise yeniden açar;
///       aksi hâlde yok sayar. Yeni requestNo ile gelen komut da ödenmemişse gönderi REJECTED kalır
///       (request_no güncellenir) ve yeni bir shipment.failed olayı yazılır.
///  3. Commit; olay yazıldıysa yayıncı, gönderi REQUESTED olduysa <see cref="ShipmentWorker"/> uyandırılır.
/// Aynı sipariş için iki farklı komut aynı anda ilk kez gelirse ikisi de satır bulamaz; ikinci INSERT
/// order_id (ya da aynı anlama gelen idempotency_key) tekil indeksine takılır ve o taraf işlemi bir kez
/// baştan dener (bu kez satırı bulur).
/// Kargo firması burada ÇAĞRILMAZ; REQUESTED gönderiyi <see cref="ShipmentWorker"/> firmaya iletir.
/// </summary>
public sealed class ShipmentIntake(CargoDbContext db, OutboxSignal signal, ShipmentSignal shipmentSignal)
{
    public const string UnpaidReason = "Ödemesi onaylanmamış sipariş kargoya verilemez.";
    private const string PaidStatus = "PAID";

    /// <summary>
    /// Eşzamanlı ilk komutta hangi tekil indeksin önce ihlal edileceği belli değildir; idempotency_key
    /// doğrulamada "order-{orderId}" olmaya zorlandığından ikisi de aynı sipariş çakışmasıdır.
    /// </summary>
    private static readonly HashSet<string> SameOrderIndexes = ["ix_shipments_order_id", "ix_shipments_idempotency_key"];

    /// <summary>Komut önceden <see cref="ShipmentRequested.Validate"/>'ten geçmiş olmalıdır.</summary>
    public async Task<IntakeOutcome> HandleAsync(ShipmentRequested command, CancellationToken ct)
    {
        try
        {
            return await HandleOnceAsync(command, ct);
        }
        catch (DbUpdateException ex) when (IsOrderIdConflict(ex))
        {
            db.ChangeTracker.Clear();
            return await HandleOnceAsync(command, ct);
        }
    }

    private async Task<IntakeOutcome> HandleOnceAsync(ShipmentRequested command, CancellationToken ct)
    {
        var now = DateTimeOffset.UtcNow;
        await using var tx = await db.Database.BeginTransactionAsync(ct);

        var inserted = await db.Database.ExecuteSqlAsync(
            $"INSERT INTO cargo.processed_messages (message_id, processed_at) VALUES ({command.MessageId}, {now}) ON CONFLICT DO NOTHING",
            ct);
        if (inserted == 0)
        {
            await tx.CommitAsync(ct);
            return IntakeOutcome.Duplicate;
        }

        // xmin, SELECT * ile gelmez; EF eşzamanlılık belirteci olarak onu da bekler.
        var shipment = await db.Shipments
            .FromSql($"SELECT *, xmin FROM cargo.shipments WHERE order_id = {command.OrderId} FOR UPDATE")
            .SingleOrDefaultAsync(ct);

        IntakeOutcome outcome;
        if (shipment is null)
        {
            shipment = new Shipment
            {
                Id = Guid.CreateVersion7(),
                OrderId = command.OrderId,
                IdempotencyKey = command.IdempotencyKey!,
                CreatedAt = now,
            };
            db.Shipments.Add(shipment);
            outcome = Apply(shipment, command, now) ? IntakeOutcome.Requested : IntakeOutcome.Rejected;
        }
        else if (command.RequestNo > shipment.RequestNo
                 && shipment.Status is ShipmentStatus.Failed or ShipmentStatus.Rejected)
        {
            outcome = Apply(shipment, command, now) ? IntakeOutcome.Reopened : IntakeOutcome.Rejected;
        }
        else
        {
            await tx.CommitAsync(ct);
            return IntakeOutcome.Ignored;
        }

        var evt = ShipmentEvents.Append(db, shipment, now, command.TraceId);
        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);
        if (evt is not null)
        {
            signal.Notify();
        }
        if (outcome is IntakeOutcome.Requested or IntakeOutcome.Reopened)
        {
            shipmentSignal.Notify();
        }
        return outcome;
    }

    /// <summary>
    /// Komutu gönderiye uygular: ödenmişse REQUESTED (alıcı ve koli saklanır, önceki hata alanları
    /// temizlenir), değilse REJECTED (alıcı ve koli saklanmaz). Ödenmişse true döner.
    /// </summary>
    private static bool Apply(Shipment shipment, ShipmentRequested command, DateTimeOffset now)
    {
        var paid = command.PaymentStatus == PaidStatus;
        shipment.RequestNo = command.RequestNo;
        shipment.Status = paid ? ShipmentStatus.Requested : ShipmentStatus.Rejected;
        shipment.Recipient = paid ? JsonSerializer.Serialize(command.Recipient, JsonSerializerOptions.Web) : null;
        shipment.Parcel = paid ? JsonSerializer.Serialize(command.Parcel, JsonSerializerOptions.Web) : null;
        shipment.FailureReason = paid ? null : UnpaidReason;
        shipment.LastError = null;
        // Sıfırlama güvenlidir: yeniden açma request_no'yu artırır, sahiplenme belirteci (request_no, attempts)
        // yine de öncekilerden farklı kalır (bkz. ShipmentOutcomes.IsSameClaim).
        shipment.Attempts = 0;
        shipment.NextAttemptAt = null;
        shipment.SendingSince = null;
        shipment.UpdatedAt = now;
        return paid;
    }

    private static bool IsOrderIdConflict(DbUpdateException ex) =>
        ex.InnerException is PostgresException { SqlState: PostgresErrorCodes.UniqueViolation, ConstraintName: { } name }
        && SameOrderIndexes.Contains(name);
}
