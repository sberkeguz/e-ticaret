using CargoService.Configuration;
using CargoService.Data;
using CargoService.Messaging.Contracts;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace CargoService.Carriers.Fake;

/// <summary>
/// Sahte kargo firması ("FAKE" / "Test Kargo"). Firma tarafındaki durumu <c>cargo.fake_carrier_shipments</c>
/// tablosunda tutar; böylece servis yeniden başlasa da "firmadaki" gönderiler kaybolmaz.
///  - Aynı tekrar anahtarı her zaman aynı takip numarasını alır ("TK" + 10 haneli sıra).
///  - Her çağrıdan önce <c>Carrier:Fake:DelayMs</c> kadar bekler.
///  - <see cref="FakeCarrierFaults"/> planı yalnızca <see cref="CreateAsync"/>'e uygulanır.
/// Alıcı ve koli bilgisi kullanılmaz ve saklanmaz (gerçek firmaya gidecek veriyi taklit etmek gerekmez).
/// Firma ayrı bir sistemdir: her çağrı kendi DbContext'iyle çalışır, çağıranın işlemine katılmaz.
/// Veritabanı hatası, ulaşılamayan bir firma gibi "belirsiz" sonuç döner.
/// </summary>
public sealed class FakeCarrierGateway(
    IServiceScopeFactory scopes,
    FakeCarrierFaults faults,
    IOptions<CargoOptions> options) : ICarrierGateway
{
    public const string InitialStatus = "CREATED";
    public const string PermanentFailureReason = "Kargo firması gönderiyi kabul etmedi.";

    public string CarrierCode => "FAKE";

    public string CarrierName => "Test Kargo";

    public async Task<CarrierCreateResult> CreateAsync(
        string idempotencyKey, Recipient recipient, Parcel parcel, CancellationToken ct)
    {
        await DelayAsync(ct);
        var fault = faults.TryConsume();
        switch (fault)
        {
            case FakeFailMode.TimeoutBeforeCreate:
                return new CarrierCreateResult.Uncertain("Sahte firma: zaman aşımı (gönderi açılmadı)");
            case FakeFailMode.Permanent:
                return new CarrierCreateResult.PermanentFailure(PermanentFailureReason);
        }

        string trackingNumber;
        try
        {
            await using var scope = scopes.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<CargoDbContext>();
            var now = DateTimeOffset.UtcNow;
            // Anahtar zaten varsa yeni gönderi açılmaz (sıradan bir numara harcanabilir; önemsiz).
            await db.Database.ExecuteSqlAsync($"""
                INSERT INTO cargo.fake_carrier_shipments (idempotency_key, tracking_number, status, created_at, updated_at)
                VALUES ({idempotencyKey}, 'TK' || lpad(nextval('cargo.fake_carrier_tracking_seq')::text, 10, '0'),
                        {InitialStatus}, {now}, {now})
                ON CONFLICT (idempotency_key) DO NOTHING
                """, ct);
            trackingNumber = await db.FakeCarrierShipments.AsNoTracking()
                .Where(f => f.IdempotencyKey == idempotencyKey)
                .Select(f => f.TrackingNumber)
                .SingleAsync(ct);
        }
        catch (Exception ex) when (ex is not OperationCanceledException || !ct.IsCancellationRequested)
        {
            return new CarrierCreateResult.Uncertain($"Sahte firma: {ex.GetType().Name}");
        }

        return fault == FakeFailMode.TimeoutAfterCreate
            ? new CarrierCreateResult.Uncertain("Sahte firma: zaman aşımı (gönderi açıldı, yanıt gelmedi)")
            : new CarrierCreateResult.Created(trackingNumber);
    }

    public async Task<CarrierLookupResult> FindByReferenceAsync(string idempotencyKey, CancellationToken ct)
    {
        await DelayAsync(ct);
        try
        {
            await using var scope = scopes.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<CargoDbContext>();
            var trackingNumber = await db.FakeCarrierShipments.AsNoTracking()
                .Where(f => f.IdempotencyKey == idempotencyKey)
                .Select(f => f.TrackingNumber)
                .SingleOrDefaultAsync(ct);
            return trackingNumber is null
                ? new CarrierLookupResult.NotFound()
                : new CarrierLookupResult.Found(trackingNumber);
        }
        catch (Exception ex) when (ex is not OperationCanceledException || !ct.IsCancellationRequested)
        {
            return new CarrierLookupResult.Uncertain($"Sahte firma: {ex.GetType().Name}");
        }
    }

    public async Task<CarrierStatusResult> GetStatusAsync(string trackingNumber, CancellationToken ct)
    {
        await DelayAsync(ct);
        try
        {
            await using var scope = scopes.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<CargoDbContext>();
            var row = await db.FakeCarrierShipments.AsNoTracking()
                .SingleOrDefaultAsync(f => f.TrackingNumber == trackingNumber, ct);
            return row is null
                ? new CarrierStatusResult.NotFound()
                : new CarrierStatusResult.Status(ShipmentStatusConverter.FromDb(row.Status), row.UpdatedAt);
        }
        catch (Exception ex) when (ex is not OperationCanceledException || !ct.IsCancellationRequested)
        {
            return new CarrierStatusResult.Uncertain($"Sahte firma: {ex.GetType().Name}");
        }
    }

    /// <summary>
    /// Test ucu için: gönderinin firmadaki durumunu değiştirir (IN_TRANSIT, DELIVERED, DELIVERY_FAILED).
    /// Takip numarası yoksa false.
    /// </summary>
    public async Task<bool> SetStatusAsync(string trackingNumber, ShipmentStatus status, CancellationToken ct)
    {
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<CargoDbContext>();
        var updated = await db.FakeCarrierShipments
            .Where(f => f.TrackingNumber == trackingNumber)
            .ExecuteUpdateAsync(set => set
                .SetProperty(f => f.Status, ShipmentStatusConverter.ToDb(status))
                .SetProperty(f => f.UpdatedAt, DateTimeOffset.UtcNow), ct);
        return updated > 0;
    }

    private Task DelayAsync(CancellationToken ct) =>
        options.Value.Carrier.Fake.DelayMs > 0
            ? Task.Delay(options.Value.Carrier.Fake.DelayMs, ct)
            : Task.CompletedTask;
}
