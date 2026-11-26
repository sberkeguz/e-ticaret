namespace CargoService.Messaging.Contracts;

/// <summary>
/// Alıcı bilgileri (komut gövdesinde <c>recipient</c>). KİŞİSEL VERİDİR: loglanmaz, olaylara konmaz;
/// yalnızca <c>shipments.recipient</c> jsonb sütununa ve kargo firmasına gider.
/// <see cref="ToString"/> bilerek "[gizli]" döner: record'un otomatik ToString'i alanları yazardı ve
/// nesne yanlışlıkla bir log şablonuna verilirse kişisel veri loga düşerdi.
/// </summary>
public sealed record Recipient
{
    public string? FirstName { get; init; }
    public string? LastName { get; init; }
    public string? Phone { get; init; }
    public string? City { get; init; }
    public string? District { get; init; }
    public string? Neighborhood { get; init; }
    public string? Address { get; init; }

    public override string ToString() => "[gizli]";

    /// <summary>
    /// Boş, eksik ya da <see cref="SafeText"/>'e uymayan karakter içeren alanların ADLARI (değerleri değil).
    /// </summary>
    public IEnumerable<string> InvalidFields()
    {
        if (!IsValid(FirstName)) yield return "recipient.firstName";
        if (!IsValid(LastName)) yield return "recipient.lastName";
        if (!IsValid(Phone)) yield return "recipient.phone";
        if (!IsValid(City)) yield return "recipient.city";
        if (!IsValid(District)) yield return "recipient.district";
        if (!IsValid(Neighborhood)) yield return "recipient.neighborhood";
        if (!IsValid(Address)) yield return "recipient.address";
    }

    private static bool IsValid(string? value) => !string.IsNullOrWhiteSpace(value) && SafeText.IsSafe(value);
}
