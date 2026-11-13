import { useEffect, useState } from "react";
import { addToCart, getCart, removeFromCart, setQuantity, type CartProduct } from "../cart";
import type { MouseEvent } from "react";

const quantityOf = (id: number): number => getCart().find((item) => item.id === id)?.quantity ?? 0;

interface AddToCartProps {
    product: CartProduct;
    className?: string;
}

export default function AddToCart({ product, className = "button button-quiet" }: AddToCartProps) {
    const [quantity, setQty] = useState(() => quantityOf(product.id));
    const stock = Number(product.stock ?? 0);

    useEffect(() => {
        const refresh = () => setQty(quantityOf(product.id));
        refresh();
        window.addEventListener("cart-changed", refresh);
        return () => window.removeEventListener("cart-changed", refresh);
    }, [product.id]);

    const stop = (handler: () => void) => (event: MouseEvent) => {
        event.stopPropagation();
        handler();
    };

    if (quantity > 0) {
        return (
            <div className="qty-stepper" onClick={(event) => event.stopPropagation()}>
                <button type="button" onClick={stop(() => (quantity <= 1 ? removeFromCart(product.id) : setQuantity(product.id, quantity - 1)))}>−</button>
                <span>{quantity}</span>
                <button type="button" disabled={quantity >= stock} onClick={stop(() => addToCart(product))}>+</button>
            </div>
        );
    }

    if (stock <= 0) {
        return <button type="button" className={className} disabled onClick={(event) => event.stopPropagation()}>Tükendi</button>;
    }

    return (
        <button type="button" className={className} onClick={stop(() => addToCart(product))}>
            Sepete ekle
        </button>
    );
}
