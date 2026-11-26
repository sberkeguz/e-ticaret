using CargoService.Configuration;
using Microsoft.Extensions.Options;
using RabbitMQ.Client;

namespace CargoService.Messaging;

/// <summary>
/// Servisin tek RabbitMQ bağlantısı (yayıncı ve tüketici ayrı kanallar açar). Bağlantı ilk istendiğinde
/// kurulur; koptuysa (IsOpen=false) bir sonraki istekte atılıp yenisi kurulur. İstemcinin kendi
/// otomatik kurtarması kapalıdır: yeniden bağlanma tek yerden, burada yapılır.
///
/// RabbitMQ yoksa ya da <c>RabbitMq:Url</c> boşsa servis çalışmaya devam eder; yalnızca bağlantı
/// isteyen işlem hata alır (outbox satırları bekler, komutlar kuyrukta bekler).
/// Adres parola içerebileceği için hiçbir yere loglanmaz.
/// </summary>
public sealed class RabbitConnectionProvider(IOptions<CargoOptions> options) : IAsyncDisposable
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    private IConnection? _connection;

    public bool IsConfigured => !string.IsNullOrWhiteSpace(options.Value.RabbitMq.Url);

    /// <summary>Açık bağlantıyı döner; yoksa kurar. Kurulamazsa istisna fırlatır.</summary>
    public async Task<IConnection> GetConnectionAsync(CancellationToken ct)
    {
        if (!IsConfigured)
        {
            throw new InvalidOperationException("RabbitMq:Url tanımlı değil.");
        }
        if (_connection is { IsOpen: true } current)
        {
            return current;
        }

        await _gate.WaitAsync(ct);
        try
        {
            if (_connection is { IsOpen: true } again)
            {
                return again;
            }
            if (_connection is not null)
            {
                await DisposeQuietlyAsync(_connection);
                _connection = null;
            }

            var factory = new ConnectionFactory
            {
                Uri = new Uri(options.Value.RabbitMq.Url!),
                ClientProvidedName = "cargo-service",
                AutomaticRecoveryEnabled = false,
                TopologyRecoveryEnabled = false,
                RequestedConnectionTimeout = TimeSpan.FromSeconds(5),
            };
            _connection = await factory.CreateConnectionAsync(ct);
            return _connection;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_connection is not null)
        {
            await DisposeQuietlyAsync(_connection);
            _connection = null;
        }
        _gate.Dispose();
    }

    private static async Task DisposeQuietlyAsync(IConnection connection)
    {
        try
        {
            if (connection.IsOpen)
            {
                await connection.CloseAsync();
            }
            await connection.DisposeAsync();
        }
        catch
        {
            // Zaten kopmuş bağlantıyı kapatırken çıkan hata önemsizdir.
        }
    }
}
