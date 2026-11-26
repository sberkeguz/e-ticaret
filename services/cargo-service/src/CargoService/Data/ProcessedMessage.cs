namespace CargoService.Data;

/// <summary>İşlenmiş gelen mesajlar (cargo.processed_messages); aynı messageId ikinci kez işlenmez.</summary>
public sealed class ProcessedMessage
{
    public string MessageId { get; set; } = "";

    public DateTimeOffset ProcessedAt { get; set; }
}
