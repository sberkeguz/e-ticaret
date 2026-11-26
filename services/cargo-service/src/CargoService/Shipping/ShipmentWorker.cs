using CargoService.Carriers;
using CargoService.Configuration;
using CargoService.Data;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace CargoService.Shipping;

/// <summary>
/// REQUESTED gönderileri kargo firmasında açar. Her turda, parti parti (en fazla 10):
///  T2. Tek bir kısa UPDATE ile sahiplenir: <c>FOR UPDATE SKIP LOCKED</c> ile seçilen REQUESTED satırlar
///      SENDING olur, <c>sending_since</c> yazılır, <c>attempts</c> bir artar (başka bir servis kopyası aynı
///      satırı alamaz). İşlem burada biter.
///  -   Firma çağrısı (<see cref="ICarrierGateway.CreateAsync"/>, anahtar <c>idempotency_key</c>, alıcı ve
///      koli jsonb'den) HİÇBİR veritabanı işlemi açık değilken, 30 sn zaman aşımıyla yapılır.
///  T3. Sonuç <see cref="ShipmentOutcomes.ApplyCreateResultAsync"/> ile uygulanır.
/// Süreç firma çağrısı sırasında ölürse satır SENDING kalır; <see cref="ShipmentReconciler"/> onu firmada
/// tekrar anahtarıyla arar (çift gönderi açılmaz).
///
/// Tur iki şekilde başlar: intake yeni REQUESTED gönderiyi commit edince <see cref="ShipmentSignal"/> ile
/// (hemen) ya da yedek olarak <c>Workers:SendInterval</c> dolunca. <c>Workers:Enabled=false</c> iken arka
/// plan döngüsü çalışmaz; testler <see cref="RunOnceAsync"/> ile tur attırır.
/// Kargo firması bağlantısı çözülemiyorsa (Carrier:Provider tanınmıyor) tur hiçbir şey sahiplenmeden hata
/// verir; gönderiler REQUESTED bekler.
/// </summary>
public sealed class ShipmentWorker(
    IServiceProvider services,
    IServiceScopeFactory scopes,
    ShipmentOutcomes outcomes,
    ShipmentSignal signal,
    IOptions<CargoOptions> options,
    ILogger<ShipmentWorker> logger) : BackgroundService
{
    public const int BatchSize = 10;

    protected override Task ExecuteAsync(CancellationToken stoppingToken) =>
        options.Value.Workers.Enabled
            ? WorkerLoop.RunAsync("Gönderi açma", RunOnceAsync,
                ct => signal.WaitAsync(options.Value.Workers.SendInterval, ct), logger, stoppingToken)
            : Task.CompletedTask;

    /// <summary>Sahiplenilebilir REQUESTED gönderiler bitene kadar işler; sahiplenilen gönderi sayısını döner.</summary>
    public async Task<int> RunOnceAsync(CancellationToken ct)
    {
        // Sahiplenmeden ÖNCE çözülür: bağlantı yoksa satırlar SENDING'e çekilip takılı bırakılmaz.
        var gateway = services.GetRequiredService<ICarrierGateway>();
        var total = 0;
        while (true)
        {
            var claimed = await ClaimAsync(ct);
            if (claimed.Count == 0)
            {
                return total;
            }
            await Task.WhenAll(claimed.Select(shipment => SendAsync(gateway, shipment, ct)));
            total += claimed.Count;
            if (claimed.Count < BatchSize)
            {
                return total;
            }
        }
    }

    /// <summary>T2: sahiplenme. Tek UPDATE ifadesi kendi kısa işleminde çalışır ve hemen commit olur.</summary>
    private async Task<List<Shipment>> ClaimAsync(CancellationToken ct)
    {
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<CargoDbContext>();
        var now = DateTimeOffset.UtcNow;
        return await db.Shipments
            .FromSql($"""
                UPDATE cargo.shipments
                SET status = 'SENDING', sending_since = {now}, attempts = attempts + 1, updated_at = {now}
                WHERE status = 'REQUESTED' AND id IN (
                    SELECT id FROM cargo.shipments
                    WHERE status = 'REQUESTED' AND coalesce(next_attempt_at, {now}) <= {now}
                    ORDER BY created_at
                    LIMIT {BatchSize}
                    FOR UPDATE SKIP LOCKED)
                RETURNING *, xmin
                """)
            .AsNoTracking()
            .ToListAsync(ct);
    }

    private async Task SendAsync(ICarrierGateway gateway, Shipment claimed, CancellationToken ct)
    {
        logger.LogInformation("Gönderi {ShipmentId} (sipariş {OrderId}) kargo firmasına iletiliyor ({Attempts}. deneme)",
            claimed.Id, claimed.OrderId, claimed.Attempts);
        var result = await outcomes.CreateAtCarrierAsync(gateway, claimed, ct);
        // Firmanın verdiği sonuç kapanış sırasında da kaydedilsin diye T3 iptal edilmez (veritabanı
        // komut zaman aşımı yine sınırlar); yazılamazsa satır SENDING kalır, reconciler toparlar.
        await outcomes.ApplyCreateResultAsync(claimed, result, gateway, CancellationToken.None);
    }
}
