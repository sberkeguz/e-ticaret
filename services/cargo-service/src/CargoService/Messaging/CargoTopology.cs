using RabbitMQ.Client;

namespace CargoService.Messaging;

/// <summary>
/// Kargo mesajlaşma sözleşmesi (v1): exchange, kuyruk ve routing key adları.
///
/// Exchange/kuyruk tanımları idempotenttir; bu servis ve backend AYNI ADLARI AYNI AYARLARLA tanımlar,
/// hangisi önce açılırsa açılsın uyumludur. Ayarlardan biri farklı olursa RabbitMQ ikinci tanımı
/// PRECONDITION_FAILED ile reddeder ve kanal kapanır; bu yüzden aşağıdaki argümanlar değiştirilmez.
///
/// Sürüm kuralı: alanlar silinmez ve anlamı değişmez, yalnızca yeni alan eklenir. Kırıcı değişiklik
/// gerekirse yeni bir mesaj tipi açılır.
/// </summary>
public static class CargoTopology
{
    /// <summary>Topic, durable; yönlendirilemeyen mesajlar <see cref="UnroutedExchange"/>'e gider.</summary>
    public const string Exchange = "cargo";

    /// <summary>Fanout, durable. Hiçbir kuyruğa yönlenemeyen mesaj kaybolmasın diye buraya düşer.</summary>
    public const string UnroutedExchange = "cargo.unrouted";

    /// <summary>Durable; <see cref="UnroutedExchange"/>'e bağlıdır.</summary>
    public const string UnroutedQueue = "cargo.unrouted";

    /// <summary>Backend'in "kargoya ver" komutu. Olay anahtarlarından ("shipment.*") ayrıdır.</summary>
    public const string ShipmentCreateRoutingKey = "commands.shipment.create";

    /// <summary>
    /// Komut kuyruğu. Durable; argümanlar <see cref="ShipmentRequestsQueueArguments"/>. Backend de bu
    /// kuyruğu birebir aynı argümanlarla tanımlar (servis kapalıyken komut kuyrukta bekler).
    /// </summary>
    public const string ShipmentRequestsQueue = "cargo.shipment-requests";

    /// <summary>Reddedilen (bozuk) komutların düştüğü kuyruk; durable, argümansız. Bu servis tanımlar.</summary>
    public const string ShipmentRequestsDeadLetterQueue = ShipmentRequestsQueue + ".dlq";

    /// <summary>
    /// Direct, durable. <see cref="ShipmentRequestsQueue"/>'nun dead-letter exchange'i; yalnız
    /// <see cref="ShipmentRequestsDeadLetterQueue"/> bağlıdır (anahtar = DLQ adı). Varsayılan exchange ("")
    /// kullanılmaz: cargo kullanıcısının "" üzerinde yazma izni olsaydı her kuyruğa mesaj bırakabilirdi.
    /// </summary>
    public const string DeadLetterExchange = "cargo.dlx";

    /// <summary>
    /// Direct, durable. Tanınmayan hatada sayaçlı kopyanın komut kuyruğuna geri yayınlandığı exchange; yalnız
    /// <see cref="ShipmentRequestsQueue"/> bağlıdır (anahtar = kuyruk adı).
    /// </summary>
    public const string RetryExchange = "cargo.retry";

    /// <summary>Her olayda AMQP başlığı <c>x-event-version</c> olarak gider.</summary>
    public const int EventVersion = 1;

    public const string EventVersionHeader = "x-event-version";

    public const string ShipmentCreated = "shipment.created";
    public const string ShipmentInTransit = "shipment.in_transit";
    public const string ShipmentDelivered = "shipment.delivered";
    public const string ShipmentDeliveryFailed = "shipment.delivery_failed";
    public const string ShipmentFailed = "shipment.failed";

    /// <summary>
    /// <see cref="ShipmentRequestsQueue"/> argümanları — backend ile birebir aynı olmalıdır:
    /// <c>x-dead-letter-exchange = "cargo.dlx"</c> (<see cref="DeadLetterExchange"/>) ve
    /// <c>x-dead-letter-routing-key = "cargo.shipment-requests.dlq"</c>. Reddedilen mesaj böylece
    /// DLX'teki tek bağ üzerinden DLQ kuyruğuna gider.
    /// </summary>
    public static IDictionary<string, object?> ShipmentRequestsQueueArguments => new Dictionary<string, object?>
    {
        ["x-dead-letter-exchange"] = DeadLetterExchange,
        ["x-dead-letter-routing-key"] = ShipmentRequestsDeadLetterQueue,
    };

    /// <summary>Bütün exchange, kuyruk ve bağları tanımlar (zaten varsa bir şey değişmez).</summary>
    public static async Task DeclareAsync(IChannel channel, CancellationToken ct = default)
    {
        await channel.ExchangeDeclareAsync(UnroutedExchange, ExchangeType.Fanout, durable: true, autoDelete: false, cancellationToken: ct);
        await channel.QueueDeclareAsync(UnroutedQueue, durable: true, exclusive: false, autoDelete: false, cancellationToken: ct);
        await channel.QueueBindAsync(UnroutedQueue, UnroutedExchange, "", cancellationToken: ct);

        await channel.ExchangeDeclareAsync(Exchange, ExchangeType.Topic, durable: true, autoDelete: false,
            arguments: new Dictionary<string, object?> { ["alternate-exchange"] = UnroutedExchange }, cancellationToken: ct);

        await channel.ExchangeDeclareAsync(DeadLetterExchange, ExchangeType.Direct, durable: true, autoDelete: false, cancellationToken: ct);
        await channel.ExchangeDeclareAsync(RetryExchange, ExchangeType.Direct, durable: true, autoDelete: false, cancellationToken: ct);

        await channel.QueueDeclareAsync(ShipmentRequestsDeadLetterQueue, durable: true, exclusive: false, autoDelete: false, cancellationToken: ct);
        await channel.QueueBindAsync(ShipmentRequestsDeadLetterQueue, DeadLetterExchange, ShipmentRequestsDeadLetterQueue, cancellationToken: ct);
        await channel.QueueDeclareAsync(ShipmentRequestsQueue, durable: true, exclusive: false, autoDelete: false,
            arguments: ShipmentRequestsQueueArguments, cancellationToken: ct);
        await channel.QueueBindAsync(ShipmentRequestsQueue, Exchange, ShipmentCreateRoutingKey, cancellationToken: ct);
        await channel.QueueBindAsync(ShipmentRequestsQueue, RetryExchange, ShipmentRequestsQueue, cancellationToken: ct);
    }
}
