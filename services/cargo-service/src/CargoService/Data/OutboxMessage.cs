namespace CargoService.Data;

/// <summary>
/// Outbox: yayınlanacak olaylar (cargo.outbox_messages). Durum değişikliğiyle aynı işlemde yazılır;
/// yayıncı bunları RabbitMQ'ya iletip published_at'i doldurur. Sütunlar iyzico.outbox_messages ile aynıdır.
/// </summary>
public sealed class OutboxMessage
{
    /// <summary>RabbitMQ messageId'si; dinleyiciler tekrar gelen mesajı bununla tanır.</summary>
    public Guid Id { get; set; }

    public string Exchange { get; set; } = "";

    public string RoutingKey { get; set; } = "";

    public string Type { get; set; } = "";

    /// <summary>Olay gövdesi (jsonb). Alıcı/kişisel veri içermez.</summary>
    public string Payload { get; set; } = "";

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset? PublishedAt { get; set; }

    public int Attempts { get; set; }

    public string? LastError { get; set; }
}
