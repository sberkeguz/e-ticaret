namespace CargoService.Messaging.Contracts;

/// <summary>
/// Komut metinlerinde izin verilmeyen karakterler. Bunlar ya veritabanında her seferinde aynı hatayı
/// verir (jsonb/text: U+0000, eşsiz vekil → SqlState 22P05/22021) ya da metnin üretici tarafta bozulduğunu
/// gösterir; böyle bir komut tekrar denenerek düzelmez, doğrulamada reddedilip DLQ'ya gider.
/// Yasak olanlar:
///  - U+0000–U+001F (C0 kontrol) — sekme, satır sonu ve satır başı (\t \n \r) HARİÇ,
///  - U+007F (DEL) ve U+0080–U+009F (C1 kontrol),
///  - eşi olmayan vekil (surrogate) karakterler,
///  - U+FFFD (yerine-koyma karakteri): System.Text.Json eşsiz vekili yazarken buna çevirir, yani metin
///    yayından önce bozulmuştur,
///  - U+FFFE ve U+FFFF (karakter olmayan kod noktaları).
/// Türkçe harfler, emoji (geçerli vekil çiftleri) ve diğer tüm karakterler kabul edilir.
/// </summary>
public static class SafeText
{
    /// <summary>null ya da boş metin geçerlidir (zorunluluk ayrı denetlenir).</summary>
    public static bool IsSafe(string? text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return true;
        }
        for (var i = 0; i < text.Length; i++)
        {
            var c = text[i];
            if (char.IsHighSurrogate(c))
            {
                if (i + 1 < text.Length && char.IsLowSurrogate(text[i + 1]))
                {
                    i++; // geçerli çift (ör. emoji)
                    continue;
                }
                return false;
            }
            if (char.IsLowSurrogate(c) || IsForbidden(c))
            {
                return false;
            }
        }
        return true;
    }

    private static bool IsForbidden(char c) =>
        (c < '\u0020' && c is not ('\t' or '\n' or '\r'))
        || c is >= '\u007F' and <= '\u009F'
        || c is '\uFFFD' or '\uFFFE' or '\uFFFF';
}
