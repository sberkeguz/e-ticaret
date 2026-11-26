using System.Text;
using CargoService.Messaging;
using RabbitMQ.Client;

namespace CargoService.Tests.Infrastructure;

/// <summary>
/// Testlerin RabbitMQ'yu doğrudan kullanması için küçük yardımcı: topolojiyi tanımlar, geçici kuyruk
/// bağlar, mesaj yayınlar ve kuyruktan messageId'ye göre mesaj arar.
/// </summary>
public sealed class RabbitTestClient : IAsyncDisposable
{
    private readonly IConnection _connection;
    private readonly IChannel _channel;

    private RabbitTestClient(IConnection connection, IChannel channel)
    {
        _connection = connection;
        _channel = channel;
    }

    public static async Task<RabbitTestClient> ConnectAsync(ContainersFixture containers)
    {
        var factory = new ConnectionFactory { Uri = new Uri(containers.RabbitMqUrl) };
        var connection = await factory.CreateConnectionAsync();
        var channel = await connection.CreateChannelAsync();
        await CargoTopology.DeclareAsync(channel);
        return new RabbitTestClient(connection, channel);
    }

    /// <summary>cargo exchange'ine verilen anahtarla bağlı, sunucu adlı geçici (exclusive) kuyruk açar.</summary>
    public async Task<string> BindTemporaryQueueAsync(string routingKey)
    {
        var queue = await _channel.QueueDeclareAsync("", durable: false, exclusive: true, autoDelete: true);
        await _channel.QueueBindAsync(queue.QueueName, CargoTopology.Exchange, routingKey);
        return queue.QueueName;
    }

    public async Task PublishCommandAsync(string body, string messageId)
    {
        var props = new BasicProperties
        {
            MessageId = messageId,
            ContentType = "application/json",
            Persistent = true,
        };
        await _channel.BasicPublishAsync(
            CargoTopology.Exchange, CargoTopology.ShipmentCreateRoutingKey, mandatory: false, props,
            Encoding.UTF8.GetBytes(body));
    }

    /// <summary>
    /// Kuyruktaki mesajları sırayla alır (ack'ler) ve AMQP messageId'si eşleşeni döner; süre dolarsa null.
    /// Eşleşmeyen mesajlar da kuyruktan silinir (yalnızca test kuyruklarında kullanın).
    /// </summary>
    public async Task<BasicGetResult?> FindAsync(string queue, string messageId, TimeSpan? timeout = null)
    {
        var deadline = DateTime.UtcNow + (timeout ?? TimeSpan.FromSeconds(10));
        while (DateTime.UtcNow < deadline)
        {
            var message = await _channel.BasicGetAsync(queue, autoAck: true);
            if (message is null)
            {
                await Task.Delay(100);
                continue;
            }
            if (message.BasicProperties.MessageId == messageId)
            {
                return message;
            }
        }
        return null;
    }

    /// <summary>Kuyrukta şu an bekleyen bütün mesajları alır (ack'ler).</summary>
    public async Task<List<BasicGetResult>> DrainAsync(string queue)
    {
        var all = new List<BasicGetResult>();
        while (await _channel.BasicGetAsync(queue, autoAck: true) is { } message)
        {
            all.Add(message);
        }
        return all;
    }

    public async ValueTask DisposeAsync()
    {
        await _channel.DisposeAsync();
        await _connection.DisposeAsync();
    }
}
