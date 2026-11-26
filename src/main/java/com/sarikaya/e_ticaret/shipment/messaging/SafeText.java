package com.sarikaya.e_ticaret.shipment.messaging;

/**
 * cargo-service'in SafeText.IsSafe kuralının birebir karşılığı (Messaging/Contracts/SafeText.cs). Bu
 * karakterlerden birini taşıyan komutu cargo-service DLQ'ya atar; backend "Kargoya ver"den önce denetler ki
 * gönderi REQUESTED'da takılı kalmasın. Yasak: U+0000–U+001F (\t \n \r hariç), U+007F–U+009F, eşi
 * olmayan vekil karakter, U+FFFD, U+FFFE, U+FFFF. Türkçe harfler ve emoji (geçerli vekil çifti) serbesttir.
 * Ayrıca cargo-service'in "boş alan" kuralı (.NET string.IsNullOrWhiteSpace) {@link #isNullOrWhiteSpace}'tedir.
 */
public final class SafeText {
    private SafeText() {
    }

    /** null ya da boş metin geçerlidir (zorunluluk ayrı denetlenir). */
    public static boolean isSafe(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    i++; // geçerli çift (ör. emoji)
                    continue;
                }
                return false;
            }
            if (Character.isLowSurrogate(c) || isForbidden(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * .NET string.IsNullOrWhiteSpace'in karşılığı (cargo-service zorunlu alanları böyle denetler). Java
     * String.isBlank() U+00A0, U+2007, U+202F'yi boşluk saymaz, .NET sayar; bu yüzden ayrı yazıldı.
     * .NET char.IsWhiteSpace: Zs, Zl, Zp kategorileri ile U+0009–U+000D ve U+0085.
     */
    public static boolean isNullOrWhiteSpace(String text) {
        if (text == null) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!isDotNetWhiteSpace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDotNetWhiteSpace(char c) {
        if ((c >= '\t' && c <= '\r') || c == '\u0085') {
            return true;
        }
        int type = Character.getType(c);
        return type == Character.SPACE_SEPARATOR || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    private static boolean isForbidden(char c) {
        return (c < ' ' && c != '\t' && c != '\n' && c != '\r')
                || (c >= '\u007F' && c <= '\u009F')
                || c == '�' || c == '￾' || c == '￿';
    }
}
