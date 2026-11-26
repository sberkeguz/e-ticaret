using System.Diagnostics;
using System.Diagnostics.Metrics;
using CargoService.Carriers;
using CargoService.Configuration;
using CargoService.Data;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace CargoService.Shipping;

/// <summary>
/// SENDING'de takılı/belirsiz kalan gönderileri toparlar (varsayılan dakikada bir, <c>Workers:ReconcileInterval</c>).
/// Her turda, parti parti (en fazla 10):
///  1. Koşullu tek UPDATE ile sahiplenir: SENDING olan, <c>sending_since</c>'ı <c>Workers:SendingStuckAfter</c>
///     (varsayılan 2 dk) kadar eski ve <c>next_attempt_at</c>'i gelmiş satırlar <c>FOR UPDATE SKIP LOCKED</c>
///     ile seçilir; <c>attempts</c> bir artar ve <c>next_attempt_at</c> <see cref="ClaimLease"/> kadar ileri
///     alınır. Böylece başka bir tur/kopya aynı satırı aynı anda işlemez; artan <c>attempts</c> da worker'ın
///     geç gelen eski sonucunu geçersiz kılar. <c>sending_since</c> değişmez (belirsizliğin başladığı an).
///  2. İşlem DIŞINDA firmada tekrar anahtarıyla arar (<see cref="ICarrierGateway.FindByReferenceAsync"/>):
///     Found → CREATED (+ shipment.created); NotFound → aynı anahtarla <see cref="ICarrierGateway.CreateAsync"/>
///     (sonuç worker'daki gibi uygulanır); belirsiz → SENDING kalır, geri çekilir.
///     Belirsiz kayıt ASLA otomatik FAILED yapılmaz; yalnızca firmanın kesin reddi FAILED yapar.
/// Tur sonunda 30 dakikadan uzun süredir belirsiz (SENDING) kalan gönderi sayısı <see cref="UncertainMetric"/>
/// göstergesine yazılır ve sıfırdan büyükse uyarı loglanır.
/// </summary>
public sealed class ShipmentReconciler : BackgroundService
{
    public const int BatchSize = 10;

    /// <summary>Meter adı (OpenTelemetry/OTLP aktarımı bu adla abone olur).</summary>
    public const string MeterName = "CargoService";

    /// <summary>Gösterge: 30 dakikadan uzun süredir sonucu belirsiz (SENDING) gönderi sayısı.</summary>
    public const string UncertainMetric = "cargo_shipments_uncertain";

    public static readonly TimeSpan UncertainAlertAfter = TimeSpan.FromMinutes(30);

    /// <summary>
    /// Sahiplenilen satırın başka bir tura kapalı kaldığı süre: iki firma çağrısından (arama + açma,
    /// her biri en fazla 30 sn) uzundur. Süreç bu arada ölürse kayıt süre dolunca yeniden ele alınır.
    /// </summary>
    public static readonly TimeSpan ClaimLease = TimeSpan.FromMinutes(2);

    private readonly IServiceProvider _services;
    private readonly IServiceScopeFactory _scopes;
    private readonly ShipmentOutcomes _outcomes;
    private readonly IOptions<CargoOptions> _options;
    private readonly ILogger<ShipmentReconciler> _logger;

    // İlk tur bitene kadar -1: gösterge değer yayınlamaz (bilinmeyen sayı 0 gibi görünmesin).
    private long _uncertain = -1;

    public ShipmentReconciler(
        IServiceProvider services,
        IServiceScopeFactory scopes,
        ShipmentOutcomes outcomes,
        IOptions<CargoOptions> options,
        IMeterFactory meters,
        ILogger<ShipmentReconciler> logger)
    {
        _services = services;
        _scopes = scopes;
        _outcomes = outcomes;
        _options = options;
        _logger = logger;
        meters.Create(MeterName).CreateObservableGauge(
            UncertainMetric,
            ObserveUncertain,
            unit: "{shipment}",
            description: "30 dakikadan uzun süredir kargo firmasındaki sonucu belirsiz (SENDING) gönderi sayısı");
    }

    protected override Task ExecuteAsync(CancellationToken stoppingToken) =>
        _options.Value.Workers.Enabled
            ? WorkerLoop.RunAsync("Gönderi uzlaştırma", RunOnceAsync,
                ct => Task.Delay(_options.Value.Workers.ReconcileInterval, ct), _logger, stoppingToken)
            : Task.CompletedTask;

    /// <summary>
    /// Tur başında vadesi gelmiş takılı kayıtları işler (turda belirsiz kalanlar aynı turda tekrar
    /// alınmaz); sahiplenilen kayıt sayısını döner. Sonda belirsiz gönderi göstergesini günceller.
    /// </summary>
    public async Task<int> RunOnceAsync(CancellationToken ct)
    {
        var gateway = _services.GetRequiredService<ICarrierGateway>();
        var turnStart = DateTimeOffset.UtcNow;
        var total = 0;
        while (true)
        {
            var claimed = await ClaimAsync(turnStart, ct);
            await Task.WhenAll(claimed.Select(shipment => ReconcileAsync(gateway, shipment, ct)));
            total += claimed.Count;
            if (claimed.Count < BatchSize)
            {
                break;
            }
        }
        await RefreshUncertainAsync(ct);
        return total;
    }

    private async Task<List<Shipment>> ClaimAsync(DateTimeOffset turnStart, CancellationToken ct)
    {
        await using var scope = _scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<CargoDbContext>();
        var now = DateTimeOffset.UtcNow;
        var stuckBefore = turnStart - _options.Value.Workers.SendingStuckAfter;
        var leaseUntil = now + ClaimLease;
        return await db.Shipments
            .FromSql($"""
                UPDATE cargo.shipments
                SET attempts = attempts + 1, next_attempt_at = {leaseUntil}, updated_at = {now}
                WHERE status = 'SENDING' AND id IN (
                    SELECT id FROM cargo.shipments
                    WHERE status = 'SENDING'
                      AND sending_since < {stuckBefore}
                      AND coalesce(next_attempt_at, {turnStart}) <= {turnStart}
                    ORDER BY sending_since
                    LIMIT {BatchSize}
                    FOR UPDATE SKIP LOCKED)
                RETURNING *, xmin
                """)
            .AsNoTracking()
            .ToListAsync(ct);
    }

    private async Task ReconcileAsync(ICarrierGateway gateway, Shipment claimed, CancellationToken ct)
    {
        var lookup = await ShipmentOutcomes.CallCarrierAsync(
            t => gateway.FindByReferenceAsync(claimed.IdempotencyKey, t),
            error => new CarrierLookupResult.Uncertain(error),
            ct);
        _logger.LogInformation("Gönderi {ShipmentId} (sipariş {OrderId}) uzlaştırılıyor ({Attempts}. deneme): firmada {Lookup}",
            claimed.Id, claimed.OrderId, claimed.Attempts, lookup switch
            {
                CarrierLookupResult.Found => "bulundu",
                CarrierLookupResult.NotFound => "yok, aynı anahtarla açılacak",
                _ => "aranamadı",
            });
        CarrierCreateResult result = lookup switch
        {
            CarrierLookupResult.Found found => new CarrierCreateResult.Created(found.TrackingNumber),
            CarrierLookupResult.NotFound => await _outcomes.CreateAtCarrierAsync(gateway, claimed, ct),
            CarrierLookupResult.Uncertain uncertain => new CarrierCreateResult.Uncertain(uncertain.Error),
            _ => throw new UnreachableException(),
        };
        await _outcomes.ApplyCreateResultAsync(claimed, result, gateway, CancellationToken.None);
    }

    private async Task RefreshUncertainAsync(CancellationToken ct)
    {
        await using var scope = _scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<CargoDbContext>();
        var threshold = DateTimeOffset.UtcNow - UncertainAlertAfter;
        var count = await db.Shipments.LongCountAsync(
            s => s.Status == ShipmentStatus.Sending && s.SendingSince < threshold, ct);
        Volatile.Write(ref _uncertain, count);
        if (count > 0)
        {
            _logger.LogWarning("{Count} gönderinin kargo firmasındaki sonucu 30 dakikadan uzun süredir belirsiz; elle kontrol gerekebilir",
                count);
        }
    }

    private IEnumerable<Measurement<long>> ObserveUncertain()
    {
        var value = Volatile.Read(ref _uncertain);
        return value < 0 ? [] : [new Measurement<long>(value)];
    }
}
