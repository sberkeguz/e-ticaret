import { useId, type ReactNode } from "react";
import Modal from "./Modal";

interface ConfirmDialogProps {
    title: string;
    children: ReactNode;
    confirmLabel: string;
    /** İstek sürerken onay düğmesinde görünen yazı. */
    busyLabel: string;
    /** true iken iki düğme de kilitli; Esc ve dışarı tıklama pencereyi kapatmaz. */
    busy: boolean;
    onConfirm: () => void;
    onCancel: () => void;
}

/** Geri alınması zor bir işlemden önce onay penceresi. İlk odak "Vazgeç"tedir (yanlışlıkla Enter onaylamaz). */
export default function ConfirmDialog({ title, children, confirmLabel, busyLabel, busy, onConfirm, onCancel }: ConfirmDialogProps) {
    const titleId = useId();
    return (
        <Modal onClose={onCancel} closable={!busy} labelledBy={titleId}>
            <section className="modal-card">
                <div className="modal-head">
                    <div><h2 id={titleId}>{title}</h2></div>
                </div>
                <div className="modal-body">{children}</div>
                <div className="modal-actions">
                    <button className="button button-quiet" type="button" data-autofocus disabled={busy} onClick={onCancel}>Vazgeç</button>
                    <button className="button button-primary" type="button" disabled={busy} onClick={onConfirm}>{busy ? busyLabel : confirmLabel}</button>
                </div>
            </section>
        </Modal>
    );
}
