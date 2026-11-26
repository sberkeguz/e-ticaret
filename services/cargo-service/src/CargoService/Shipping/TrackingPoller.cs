using System.Diagnostics;
using CargoService.Carriers;
using CargoService.Configuration;
using CargoService.Data;
using CargoService.Messaging;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace CargoService.Shipping;

/// <summary>
/// Firmada açılmış gönderilerin durumunu takip numarasıyla sorgular (varsayılan 5 dk'da bir,
/// <c>Workers:PollInterval</c>). Her turda, parti parti (en fazla 20):
///  1. Kısa bir UPDATE ile sahiplenir: CREATED/IN_TRANSIT/DELIVERY_FAILED olan ve son sorgusunun üzerinden
///     <c>PollInterval</c> geçmiş satırlar <c>FOR UPDATE SKIP LOCKED</c> ile seçilir, <c>last_polled_at</c> yazılır.
///     DELIVERY_FAILED gönderi, son değişikliğinin (updated_at) üzerinden
///     <c>Workers:UndeliveredRecipientRetention</c> (varsayılan 90 gün) geçmişse artık seçilmez (alıcı
///     bilgisi de o süreden sonra <see cref="Cleanup"/> ile silinir).
///  2. İşlem DIŞINDA, 30 sn zaman aşımıyla <see cref="ICarrierGateway.GetStatusAsync"/> çağrılır.
///  3. Satır <c>FOR UPDATE</c> ile kilitlenir; firmanın bildirdiği durum şu anki durumdan izinli bir geçişse
///     uygulanır: durum + ilgili zaman damgası (firmanın verdiği an) + aynı işlemde outbox olayı
///     (shipment.in_transit / delivered / delivery_failed, sequence+1). Aynı durum sessizce geçilir;
///     izin verilmeyen geçiş (ör. DELIVERED'dan geri dönüş) uygulanmaz, uyarı loglanır.
/// İzinli geçişler: CREATED → IN_TRANSIT/DELIVERED/DELIVERY_FAILED; IN_TRANSIT → DELIVERED/DELIVERY_FAILED;
/// DELIVERY_FAILED → IN_TRANSIT/DELIVERED. DELIVERED son durumdur.
/// Firma "bulunamadı" ya da belirsiz yanıt verirse durum değişmez, sonraki turda tekrar sorulur.
/// </summary>
public sealed class TrackingPoller(
    IServiceProvider services,
    IServiceScopeFactory scopes,
    OutboxSignal outboxSignal,
    IOptions<CargoOptions> options,
    ILogger<TrackingPoller> logger) : BackgroundService
{
    public const int BatchSize = 20;

    /// <summary>Tur aralığının alt sınırı: PollInterval yanlışlıkla 0 verilse de döngü işlemciyi yakmaz.</summary>
    private static readonly TimeSpan MinTurnDelay = TimeSpan.FromSeconds(1);

    private static readonly Dictionary<ShipmentStatus, ShipmentStatus[]> AllowedTransitions = new()
    {
        [ShipmentStatus.Created] = [ShipmentStatus.InTransit, ShipmentStatus.Delivered, ShipmentStatus.DeliveryFailed],
        [ShipmentStatus.InTransit] = [ShipmentStatus.Delivered, ShipmentStatus.DeliveryFailed],
        [ShipmentStatus.DeliveryFailed] = [ShipmentStatus.InTransit, ShipmentStatus.Delivered],
    };

    public static bool IsAllowed(ShipmentStatus from, ShipmentStatus to) =>
        AllowedTransitions.TryGetValue(from, out var targets) && targets.Contains(to);

    protected override Task ExecuteAsync(CancellationToken stoppingToken) =>
        options.Value.Workers.Enabled
            ? WorkerLoop.RunAsync("Takip sorgusu", RunOnceAsync,
                ct => Task.Delay(Max(options.Value.Workers.PollInterval, MinTurnDelay), ct), logger, stoppingToken)
            : Task.CompletedTask;

    /// <summary>
    /// Sorgu zamanı gelmiş gönderileri bir kez sorgular (bu turda sorgulananlar aynı turda tekrar
    /// alınmaz); sahiplenilen gönderi sayısını döner.
    /// </summary>
    public async Task<int> RunOnceAsync(CancellationToken ct)
    {
        var gateway = services.GetRequiredService<ICarrierGateway>();
        var dueBefore = DateTimeOffset.UtcNow - options.Value.Workers.PollInterval;
        var total = 0;
        while (true)
        {
            var claimed = await ClaimDueAsync(dueBefore, ct);
            await Task.WhenAll(claimed.Select(shipment => PollAsync(gateway, shipment, ct)));
            total += claimed.Count;
            if (claimed.Count < BatchSize)
            {
                return total;
            }
        }
    }

    /// <summary>
    /// Takip numarası verilen gönderiyi sırasını beklemeden hemen sorgular (sahte firma test ucu, firmadaki
    /// durumu değiştirdikten sonra çağırır; <c>Workers:Enabled</c>'dan bağımsızdır). Durumu ne olursa olsun
    /// sorgulanır: DELIVERED gibi son durumdaki gönderiye gelen geri bir durum da izin verilmeyen geçiş olarak
    /// loglanır. Hata fırlatmaz (loglar); gönderi yoksa false.
    /// </summary>
    public async Task<bool> PollNowAsync(string trackingNumber, CancellationToken ct)
    {
        try
        {
            var gateway = services.GetRequiredService<ICarrierGateway>();
            await using var scope = scopes.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<CargoDbContext>();
            var now = DateTimeOffset.UtcNow;
            var claimed = await db.Shipments
                .FromSql($"""
                    UPDATE cargo.shipments SET last_polled_at = {now}
                    WHERE tracking_number = {trackingNumber}
                    RETURNING *, xmin
                    """)
                .AsNoTracking()
                .ToListAsync(ct);
            await Task.WhenAll(claimed.Select(shipment => PollAsync(gateway, shipment, ct)));
            return claimed.Count > 0;
        }
        catch (Exception ex) when (ex is not OperationCanceledException || !ct.IsCancellationRequested)
        {
            logger.LogWarning("Takip no {TrackingNumber} hemen sorgulanamadı, periyodik turda sorgulanacak: {Error}",
                trackingNumber, CommandFailures.Describe(ex));
            return false;
        }
    }

    private async Task<List<Shipment>> ClaimDueAsync(DateTimeOffset dueBefore, CancellationToken ct)
    {
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<CargoDbContext>();
        var now = DateTimeOffset.UtcNow;
        var deliveryFailedSince = now - options.Value.Workers.UndeliveredRecipientRetention;
        return await db.Shipments
            .FromSql($"""
                UPDATE cargo.shipments SET last_polled_at = {now}
                WHERE id IN (
                    SELECT id FROM cargo.shipments
                    WHERE status IN ('CREATED', 'IN_TRANSIT', 'DELIVERY_FAILED')
                      AND (status <> 'DELIVERY_FAILED' OR updated_at >= {deliveryFailedSince})
                      AND tracking_number IS NOT NULL
                      AND (last_polled_at IS NULL OR last_polled_at < {dueBefore})
                    ORDER BY last_polled_at NULLS FIRST, created_at
                    LIMIT {BatchSize}
                    FOR UPDATE SKIP LOCKED)
                RETURNING *, xmin
                """)
            .AsNoTracking()
            .ToListAsync(ct);
    }

    private async Task PollAsync(ICarrierGateway gateway, Shipment claimed, CancellationToken ct)
    {
        var result = await ShipmentOutcomes.CallCarrierAsync(
            t => gateway.GetStatusAsync(claimed.TrackingNumber!, t),
            error => new CarrierStatusResult.Uncertain(error),
            ct);
        switch (result)
        {
            case CarrierStatusResult.Status status:
                await ApplyStatusAsync(claimed, status.Value, status.At, CancellationToken.None);
                break;
            case CarrierStatusResult.NotFound:
                logger.LogWarning("Takip no {TrackingNumber} (gönderi {ShipmentId}) firmada bulunamadı; durum değişmedi",
                    claimed.TrackingNumber, claimed.Id);
                break;
            case CarrierStatusResult.Uncertain uncertain:
                logger.LogWarning("Takip no {TrackingNumber} (gönderi {ShipmentId}) sorgulanamadı, sonraki turda tekrar denenecek: {Error}",
                    claimed.TrackingNumber, claimed.Id, uncertain.Error);
                break;
        }
    }

    private async Task ApplyStatusAsync(Shipment claimed, ShipmentStatus to, DateTimeOffset at, CancellationToken ct)
    {
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<CargoDbContext>();
        await using var tx = await db.Database.BeginTransactionAsync(ct);
        var shipment = await db.Shipments
            .FromSql($"SELECT *, xmin FROM cargo.shipments WHERE id = {claimed.Id} FOR UPDATE")
            .SingleOrDefaultAsync(ct);
        if (shipment is null || shipment.Status == to)
        {
            await tx.CommitAsync(ct);
            return;
        }
        if (!IsAllowed(shipment.Status, to))
        {
            await tx.CommitAsync(ct);
            logger.LogWarning(
                "Takip no {TrackingNumber} (gönderi {ShipmentId}): firmanın bildirdiği {From} → {To} izin verilmeyen bir durum geçişi, yok sayıldı",
                shipment.TrackingNumber, shipment.Id,
                ShipmentStatusConverter.ToDb(shipment.Status), ShipmentStatusConverter.ToDb(to));
            return;
        }

        var from = shipment.Status;
        shipment.Status = to;
        switch (to)
        {
            case ShipmentStatus.InTransit:
                shipment.InTransitAt = at;
                break;
            case ShipmentStatus.Delivered:
                shipment.DeliveredAt = at;
                break;
            case ShipmentStatus.DeliveryFailed:
                shipment.DeliveryFailedAt = at;
                break;
            default:
                throw new UnreachableException(); // IsAllowed yalnızca bu üç hedefe izin verir
        }
        shipment.UpdatedAt = DateTimeOffset.UtcNow;
        ShipmentEvents.Append(db, shipment, at, Activity.Current?.TraceId.ToHexString());
        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);
        outboxSignal.Notify();
        logger.LogInformation("Takip no {TrackingNumber} (gönderi {ShipmentId}): {From} → {To}",
            shipment.TrackingNumber, shipment.Id, ShipmentStatusConverter.ToDb(from), ShipmentStatusConverter.ToDb(to));
    }

    private static TimeSpan Max(TimeSpan a, TimeSpan b) => a > b ? a : b;
}
