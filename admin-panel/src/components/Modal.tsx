import { useEffect, useRef, type MouseEvent, type ReactNode } from "react";

interface ModalProps {
    onClose: () => void;
    /** false iken (ör. kayıt sürerken) Esc ve dışarı tıklama pencereyi kapatmaz. */
    closable?: boolean;
    className?: string;
    labelledBy?: string;
    label?: string;
    children: ReactNode;
}

/**
 * Tarayıcının kendi modal penceresi (<dialog> + showModal): arkadaki sayfa tıklanamaz ve klavyeyle
 * gezilemez, odak pencerede kalır, Esc kapatır, ekran okuyucu pencere olarak duyurur.
 * İlk odak data-autofocus işaretli öğeye verilir (yoksa tarayıcı ilk odaklanabilir öğeyi seçer).
 * Dışarı (karartılmış alana) tıklamak da kapatır; basılı tutup sürüklerken dışarıda bırakmak kapatmaz.
 */
export default function Modal({ onClose, closable = true, className = "", labelledBy, label, children }: ModalProps) {
    const dialogRef = useRef<HTMLDialogElement>(null);
    const pressedOutside = useRef(false);

    useEffect(() => {
        const dialog = dialogRef.current;
        if (!dialog) return undefined;
        if (!dialog.open) dialog.showModal();
        dialog.querySelector<HTMLElement>("[data-autofocus]")?.focus();
        return () => dialog.close();
    }, []);

    const isBackdrop = (event: MouseEvent<HTMLDialogElement>) => event.target === event.currentTarget;

    return (
        <dialog
            ref={dialogRef}
            className={`modal-dialog ${className}`.trim()}
            aria-labelledby={labelledBy}
            aria-label={label}
            onCancel={(event) => { event.preventDefault(); if (closable) onClose(); }}
            onMouseDown={(event) => { pressedOutside.current = isBackdrop(event); }}
            onClick={(event) => {
                if (closable && pressedOutside.current && isBackdrop(event)) onClose();
                pressedOutside.current = false;
            }}
        >
            {children}
        </dialog>
    );
}
