import { CUSTOMER_SHIPMENT_MESSAGES, SHIPMENT_STATUS_LABELS } from "../orderStatus";
import { parseCustomerShipment } from "../shipment";

/**
 * Müşterinin sipariş kartındaki kargo bilgisi: durum, kargo firması, takip numarası ve (https ise)
 * kargo firmasının takip sayfasına bağlantı. Gönderi yoksa ya da okunamıyorsa hiçbir şey gösterilmez.
 * Alıcı bilgisi burada yoktur; takip numarası hiçbir adrese eklenmez (bağlantı backend'den hazır gelir).
 */
export default function CustomerShipmentInfo({ shipment: raw }: { shipment: unknown }) {
    const shipment = parseCustomerShipment(raw);
    if (!shipment) return null;
    const message = CUSTOMER_SHIPMENT_MESSAGES[shipment.status] ?? SHIPMENT_STATUS_LABELS[shipment.status];

    return (
        <section aria-label="Kargo bilgisi" style={{ marginTop: 12, overflowWrap: "anywhere" }}>
            <p><b>{message}</b></p>
            <p className="section-caption">
                {shipment.carrierName && <>{shipment.carrierName} · </>}
                Takip no: <code>{shipment.trackingNumber}</code>
            </p>
            {shipment.trackingUrl && (
                <a className="button button-quiet button-small" href={shipment.trackingUrl} target="_blank" rel="noopener noreferrer">
                    Kargonuzu takip edin
                </a>
            )}
        </section>
    );
}
