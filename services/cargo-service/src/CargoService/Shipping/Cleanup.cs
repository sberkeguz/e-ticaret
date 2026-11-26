using CargoService.Configuration;
using CargoService.Data;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace CargoService.Shipping;

/// <summary>
/// Günlük temizlik (<c>Workers:CleanupInterval</c>, varsayılan 1 gün; açılışta da bir tur atar):
///  - <c>processed_messages</c>: 14 günden eski satırlar silinir (komut tekrarı o süreden sonra gelse de
///    sipariş başına tek gönderi kuralı order_id tekil indeksiyle korunur).
///  - <c>outbox_messages</c>: yalnızca YAYINLANMIŞ ve yayını 7 günden eski satırlar silinir; yayınlanmamış
///    olay ne kadar eski olursa olsun silinmez (veri kaybı olmaz).
///  - <c>shipments.recipient</c> NULL yapılır (kişisel veri gereğinden uzun saklanmaz):
///    · DELIVERED gönderide teslimin (delivered_at) üzerinden 30 gün geçince;
///    · FAILED ve DELIVERY_FAILED gönderide son değişikliğin (updated_at) üzerinden
///      <c>Workers:UndeliveredRecipientRetention</c> (varsayılan 90 gün) geçince. FAILED gönderi "Tekrar dene"
///      ile açılırsa yeni komut alıcıyı yeniden getirir. Süre veritabanı saatiyle (now()) ölçülür.
///    · Üst sınır, durum ne olursa olsun (takılı kalan REQUESTED/SENDING/CREATED/IN_TRANSIT dahil): son
///      değişikliğin üzerinden <c>Workers:StaleRecipientRetention</c> (varsayılan 180 gün) geçince. SENDING'de
///      son değişiklik <c>sending_since</c>'tır (uzlaştırıcı her denemede updated_at'i yeniler). Durum
///      DEĞİŞTİRİLMEZ; alıcısı olmayan REQUESTED/SENDING gönderiyi mevcut akış güvenle kapatır: worker ve
///      uzlaştırıcı (firmada bulunamazsa) firmayı çağırmadan FAILED yapar ve shipment.failed yazar; uzlaştırıcı
///      firmada bulursa gönderi CREATED olur (firmadaki kayıt kaybolmaz).
///    Gönderi satırı ve olayları silinmez.
/// Her adım kendi tek ifadelik işleminde çalışır; tekrar çalıştırılması zararsızdır.
/// </summary>
public sealed class Cleanup(
    IServiceScopeFactory scopes,
    IOptions<CargoOptions> options,
    ILogger<Cleanup> logger) : BackgroundService
{
    public static readonly TimeSpan ProcessedMessagesRetention = TimeSpan.FromDays(14);
    public static readonly TimeSpan PublishedOutboxRetention = TimeSpan.FromDays(7);
    public static readonly TimeSpan RecipientRetentionAfterDelivery = TimeSpan.FromDays(30);

    public sealed record Result(int ProcessedMessages, int OutboxMessages, int Recipients);

    protected override Task ExecuteAsync(CancellationToken stoppingToken) =>
        options.Value.Workers.Enabled
            ? WorkerLoop.RunAsync("Temizlik", RunOnceAsync,
                ct => Task.Delay(options.Value.Workers.CleanupInterval, ct), logger, stoppingToken)
            : Task.CompletedTask;

    public async Task<Result> RunOnceAsync(CancellationToken ct)
    {
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<CargoDbContext>();
        var now = DateTimeOffset.UtcNow;

        var processedBefore = now - ProcessedMessagesRetention;
        var processed = await db.Database.ExecuteSqlAsync(
            $"DELETE FROM cargo.processed_messages WHERE processed_at < {processedBefore}", ct);

        var publishedBefore = now - PublishedOutboxRetention;
        var outbox = await db.Database.ExecuteSqlAsync(
            $"DELETE FROM cargo.outbox_messages WHERE published_at IS NOT NULL AND published_at < {publishedBefore}", ct);

        var deliveredBefore = now - RecipientRetentionAfterDelivery;
        var recipients = await db.Database.ExecuteSqlAsync($"""
            UPDATE cargo.shipments SET recipient = NULL, updated_at = {now}
            WHERE status = 'DELIVERED' AND delivered_at < {deliveredBefore} AND recipient IS NOT NULL
            """, ct);

        // updated_at bilerek değiştirilmez: takip sorgusu DELIVERY_FAILED gönderiyi updated_at'ten itibaren
        // aynı süre sonra bırakır; burada yenilenseydi sorgu 90 gün daha sürerdi.
        var undeliveredRetention = options.Value.Workers.UndeliveredRecipientRetention;
        recipients += await db.Database.ExecuteSqlAsync($"""
            UPDATE cargo.shipments SET recipient = NULL
            WHERE status IN ('FAILED', 'DELIVERY_FAILED') AND updated_at < now() - {undeliveredRetention}
              AND recipient IS NOT NULL
            """, ct);

        // updated_at yukarıdakiyle aynı nedenle değiştirilmez.
        var staleRetention = options.Value.Workers.StaleRecipientRetention;
        recipients += await db.Database.ExecuteSqlAsync($"""
            UPDATE cargo.shipments SET recipient = NULL
            WHERE recipient IS NOT NULL
              AND CASE WHEN status = 'SENDING' THEN coalesce(sending_since, updated_at) ELSE updated_at END
                  < now() - {staleRetention}
            """, ct);

        logger.LogInformation(
            "Temizlik: {ProcessedMessages} eski gelen mesaj kaydı ve {OutboxMessages} yayınlanmış olay silindi, {Recipients} gönderinin alıcı bilgisi kaldırıldı",
            processed, outbox, recipients);
        return new Result(processed, outbox, recipients);
    }
}
