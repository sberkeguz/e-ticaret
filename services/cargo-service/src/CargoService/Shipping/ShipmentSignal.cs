using System.Threading.Channels;

namespace CargoService.Shipping;

/// <summary>
/// Süreç içi "firmaya iletilecek yeni REQUESTED gönderi var" sinyali. Intake, gönderiyi REQUESTED olarak
/// COMMIT ettikten sonra <see cref="Notify"/> çağırır; <see cref="ShipmentWorker"/> beklemeden bir tur
/// atar. Birden çok sinyal tek bir tura birleşir (kapasite 1). Sinyal kaçsa (ör. başka bir servis kopyası
/// kaydetti) worker yedek aralıkla (<c>Workers:SendInterval</c>) yine tur atar.
/// </summary>
public sealed class ShipmentSignal
{
    private readonly Channel<bool> _signals = Channel.CreateBounded<bool>(
        new BoundedChannelOptions(1) { FullMode = BoundedChannelFullMode.DropWrite });

    public void Notify() => _signals.Writer.TryWrite(true);

    /// <summary>Sinyal gelene ya da <paramref name="timeout"/> dolana kadar bekler.</summary>
    public async Task WaitAsync(TimeSpan timeout, CancellationToken ct)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(timeout);
        try
        {
            await _signals.Reader.ReadAsync(cts.Token);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            // Süre doldu: yedek tur.
        }
    }
}
