using System.Diagnostics;
using System.Text.Json;
using CargoService.Carriers;
using CargoService.Configuration;
using CargoService.Data;
using CargoService.Messaging;
using CargoService.Messaging.Contracts;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace CargoService.Shipping;

/// <summary>
/// <see cref="ShipmentWorker"/> ile <see cref="ShipmentReconciler"/>'ın ortak adımları: firmada gönderi
/// açma çağrısı (veritabanı işleminin DIŞINDA, 30 sn zaman aşımıyla) ve çağrının sonucunu gönderiye
/// uygulama (T3: kısa bir işlemde, satır kilitliyken).
/// </summary>
public sealed class ShipmentOutcomes(
    IServiceScopeFactory scopes,
    OutboxSignal outboxSignal,
    IOptions<CargoOptions> options,
    ILogger<ShipmentOutcomes> logger)
{
    /// <summary>Tek bir kargo firması çağrısının en uzun süresi; dolarsa sonuç "belirsiz" sayılır.</summary>
    public static readonly TimeSpan CarrierTimeout = TimeSpan.FromSeconds(30);

    /// <summary>Geri çekilmenin üst sınırı.</summary>
    public static readonly TimeSpan MaxRetryDelay = TimeSpan.FromHours(1);

    public const string MissingDataReason = "Gönderinin alıcı ya da koli bilgisi eksik; kargo firmasına iletilemedi.";

    /// <summary>
    /// <paramref name="attempts"/>. denemeden sonra beklenecek süre: temel × 2^(deneme-1), en fazla 1 saat.
    /// </summary>
    public static TimeSpan RetryDelay(TimeSpan baseDelay, int attempts)
    {
        var exponent = Math.Clamp(attempts - 1, 0, 30);
        var seconds = baseDelay.TotalSeconds * Math.Pow(2, exponent);
        return seconds >= MaxRetryDelay.TotalSeconds ? MaxRetryDelay : TimeSpan.FromSeconds(seconds);
    }

    /// <summary>
    /// Firma çağrısını <see cref="CarrierTimeout"/> ile sınırlar. Süre dolarsa ya da çağrı istisna
    /// fırlatırsa <paramref name="uncertain"/> ile "belirsiz" sonuç döner (yalnızca süre/istisna tipi
    /// yazılır; gövde yazılmaz). <paramref name="ct"/> iptal edildiyse (kapanış) iptal yukarı iletilir.
    /// </summary>
    public static async Task<T> CallCarrierAsync<T>(
        Func<CancellationToken, Task<T>> call, Func<string, T> uncertain, CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(CarrierTimeout);
        try
        {
            return await call(timeout.Token);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            return uncertain($"Kargo firması {CarrierTimeout.TotalSeconds:0} sn içinde yanıt vermedi");
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            return uncertain($"Kargo firması çağrısı başarısız: {ex.GetType().Name}");
        }
    }

    /// <summary>
    /// Sahiplenme belirteci <c>(request_no, attempts)</c> çiftidir ve her sahiplenmede kesin artar:
    /// worker ve uzlaştırıcı her sahiplenmede <c>attempts</c>'ı bir artırır; <c>attempts</c>'ın sıfırlandığı tek
    /// yer olan "Tekrar dene" (<see cref="ShipmentIntake"/>) yalnızca daha BÜYÜK bir <c>request_no</c> ile
    /// yeniden açar. Bu yüzden yeniden açmadan önceki bir sahiplenmenin geç gelen sonucu, <c>attempts</c>
    /// tesadüfen eşit olsa bile (ABA) reddedilir.
    /// </summary>
    public static bool IsSameClaim(Shipment current, Shipment claimed) =>
        current.RequestNo == claimed.RequestNo && current.Attempts == claimed.Attempts;

    /// <summary>
    /// Gönderiyi firmada tekrar anahtarıyla (idempotency_key) açar; alıcı ve koli satırdaki jsonb'den
    /// okunur. Biri eksik ya da okunamıyorsa firma hiç çağrılmaz, kalıcı başarısızlık döner.
    /// </summary>
    public async Task<CarrierCreateResult> CreateAtCarrierAsync(ICarrierGateway gateway, Shipment shipment, CancellationToken ct)
    {
        var recipient = Read<Recipient>(shipment.Recipient);
        var parcel = Read<Parcel>(shipment.Parcel);
        if (recipient is null || parcel is null)
        {
            return new CarrierCreateResult.PermanentFailure(MissingDataReason);
        }
        return await CallCarrierAsync(
            t => gateway.CreateAsync(shipment.IdempotencyKey, recipient, parcel, t),
            error => new CarrierCreateResult.Uncertain(error),
            ct);
    }

    /// <summary>
    /// T3: firma sonucunu gönderiye uygular. Satır <c>FOR UPDATE</c> ile kilitlenir; yalnızca durum hâlâ
    /// SENDING ve sahiplenme belirteci (<see cref="IsSameClaim"/>) sahiplenmedekiyle aynıysa uygulanır,
    /// aksi hâlde hiçbir şey yapılmaz ve null döner.
    ///  - Created: CREATED + takip no + firma kodu/adı + created_at_carrier; aynı işlemde shipment.created.
    ///  - PermanentFailure: FAILED + failure_reason; aynı işlemde shipment.failed.
    ///  - Uncertain: SENDING kalır; last_error ve artan geri çekilmeyle next_attempt_at yazılır. ASLA FAILED olmaz.
    /// </summary>
    public async Task<ShipmentStatus?> ApplyCreateResultAsync(
        Shipment claimed, CarrierCreateResult result, ICarrierGateway gateway, CancellationToken ct)
    {
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<CargoDbContext>();
        await using var tx = await db.Database.BeginTransactionAsync(ct);

        var shipment = await db.Shipments
            .FromSql($"SELECT *, xmin FROM cargo.shipments WHERE id = {claimed.Id} FOR UPDATE")
            .SingleOrDefaultAsync(ct);
        if (shipment is null || shipment.Status != ShipmentStatus.Sending || !IsSameClaim(shipment, claimed))
        {
            await tx.CommitAsync(ct);
            logger.LogInformation("Gönderi {ShipmentId}: firma sonucu uygulanmadı, kayıt bu arada başka bir işçe alınmış",
                claimed.Id);
            return null;
        }

        var now = DateTimeOffset.UtcNow;
        OutboxMessage? evt = null;
        switch (result)
        {
            case CarrierCreateResult.Created created:
                shipment.Status = ShipmentStatus.Created;
                shipment.TrackingNumber = created.TrackingNumber;
                shipment.CarrierCode = gateway.CarrierCode;
                shipment.CarrierName = gateway.CarrierName;
                shipment.CreatedAtCarrier = now;
                shipment.FailureReason = null;
                shipment.LastError = null;
                shipment.NextAttemptAt = null;
                shipment.SendingSince = null;
                evt = ShipmentEvents.Append(db, shipment, now, Activity.Current?.TraceId.ToHexString());
                break;
            case CarrierCreateResult.PermanentFailure failure:
                shipment.Status = ShipmentStatus.Failed;
                shipment.FailureReason = Truncate(failure.Reason);
                shipment.LastError = null;
                shipment.NextAttemptAt = null;
                shipment.SendingSince = null;
                evt = ShipmentEvents.Append(db, shipment, now, Activity.Current?.TraceId.ToHexString());
                break;
            case CarrierCreateResult.Uncertain uncertain:
                shipment.LastError = Truncate(uncertain.Error);
                shipment.NextAttemptAt = now + RetryDelay(options.Value.Workers.RetryBaseDelay, shipment.Attempts);
                break;
            default:
                throw new UnreachableException();
        }
        shipment.UpdatedAt = now;
        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);

        if (evt is not null)
        {
            outboxSignal.Notify();
        }
        switch (result)
        {
            case CarrierCreateResult.Created:
                logger.LogInformation("Gönderi {ShipmentId} (sipariş {OrderId}) firmada açıldı: {TrackingNumber}",
                    shipment.Id, shipment.OrderId, shipment.TrackingNumber);
                break;
            case CarrierCreateResult.PermanentFailure:
                logger.LogWarning("Gönderi {ShipmentId} (sipariş {OrderId}) firma tarafından reddedildi: {Reason}",
                    shipment.Id, shipment.OrderId, shipment.FailureReason);
                break;
            default:
                logger.LogWarning(
                    "Gönderi {ShipmentId} (sipariş {OrderId}) için firma sonucu belirsiz ({Attempts}. deneme), {NextAttemptAt} sonrası firmada aranacak: {Error}",
                    shipment.Id, shipment.OrderId, shipment.Attempts, shipment.NextAttemptAt, shipment.LastError);
                break;
        }
        return shipment.Status;
    }

    private static T? Read<T>(string? json) where T : class
    {
        if (json is null)
        {
            return null;
        }
        try
        {
            return JsonSerializer.Deserialize<T>(json, JsonSerializerOptions.Web);
        }
        catch (JsonException)
        {
            return null; // jsonb'den gelir; bozuksa eksik sayılır (içerik loglanmaz).
        }
    }

    private static string Truncate(string value) => value.Length <= 500 ? value : value[..500];
}
