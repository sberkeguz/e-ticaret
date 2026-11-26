package com.sarikaya.e_ticaret.shipment.messaging;

/**
 * cargo-service'in SafeText.IsSafe kuralının birebir karşılığı (Messaging/Contracts/SafeText.cs). Bu
 * karakterlerden birini taşıyan komutu cargo-service DLQ'ya atar; backend "Kargoya ver"den önce denetler ki
 * gönderi REQUESTED'da takılı kalmasın. Yasak: U+0000–U+001F (\t \n \r hariç), U+007F–U+009F, eşi
 * olmayan vekil karakter, U+FFFD, U+FFFE, U+FFFF. Türkçe harfler ve emoji (geçerli vekil çifti) serbesttir.
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

    private static boolean isForbidden(char c) {
        return (c < ' ' && c != '\t' && c != '\n' && c != '\r')
                || (c >= '\u007F' && c <= '\u009F')
                || c == '�' || c == '￾' || c == '￿';
    }
}
