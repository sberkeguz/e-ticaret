using System.Threading.Channels;

namespace CargoService.Messaging;

/// <summary>
/// Süreç içi "outbox'a yeni satır yazıldı" sinyali. Satırı yazan kod, işlemi COMMIT ettikten sonra
/// <see cref="Notify"/> çağırır; yayıncı beklemeden bir tur atar. Birden çok sinyal tek bir tura
/// birleşir (kapasite 1). Sinyal kaçsa bile yayıncı yedek aralıkla yine tur atar.
/// </summary>
public sealed class OutboxSignal
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
