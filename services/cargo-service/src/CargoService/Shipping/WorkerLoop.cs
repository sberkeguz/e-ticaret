using CargoService.Messaging;

namespace CargoService.Shipping;

/// <summary>
/// Gönderi arka plan işlerinin ortak döngüsü: tur at → bekle → tekrar. Turda beklenmeyen bir hata olursa
/// (ör. veritabanı kapalı, kargo firması bağlantısı yapılandırılmamış) yalnızca istisna tipleri ve SqlState
/// (<see cref="CommandFailures.Describe"/>; istisna mesajı satır verisi taşıyabilir, yazılmaz) loglanır, <see cref="FailureBackoff"/> beklenip tekrar denenir. Kapanışta (iptal) döngü sessizce biter.
/// </summary>
internal static class WorkerLoop
{
    public static readonly TimeSpan FailureBackoff = TimeSpan.FromSeconds(30);

    public static async Task RunAsync(
        string job,
        Func<CancellationToken, Task> turn,
        Func<CancellationToken, Task> wait,
        ILogger logger,
        CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            var failed = false;
            try
            {
                await turn(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                logger.LogWarning("{Job} turu başarısız, {Delay} sonra tekrar denenecek: {Error}",
                    job, FailureBackoff, CommandFailures.Describe(ex));
                failed = true;
            }

            try
            {
                await (failed ? Task.Delay(FailureBackoff, stoppingToken) : wait(stoppingToken));
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

}
