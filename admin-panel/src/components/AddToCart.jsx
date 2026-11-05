import { useEffect, useState } from "react";
import { addToCart, getCart, removeFromCart, setQuantity } from "../cart";

const quantityOf = (id) => getCart().find((item) => item.id === id)?.quantity ?? 0;

export default function AddToCart({ product, className = "button button-quiet" }) {
    const [quantity, setQty] = useState(() => quantityOf(product.id));
    const stock = Number(product.stock ?? 0);

    useEffect(() => {
        const refresh = () => setQty(quantityOf(product.id));
        refresh();
        window.addEventListener("cart-changed", refresh);
        return () => window.removeEventListener("cart-changed", refresh);
    }, [product.id]);

    const stop = (handler) => (event) => {
        event.stopPropagation();
        handler();
    };

    if (quantity > 0) {
        return (
            <div className="qty-stepper" onClick={(event) => event.stopPropagation()}>
                <button type="button" onClick={stop(() => (quantity <= 1 ? removeFromCart(product.id) : setQuantity(product.id, quantity - 1)))}>−</button>
                <span>{quantity}</span>
                <button type="button" disabled={stock > 0 && quantity >= stock} onClick={stop(() => addToCart(product))}>+</button>
            </div>
        );
    }

    return (
        <button type="button" className={className} disabled={stock <= 0} onClick={stop(() => addToCart(product))}>
            Sepete ekle
        </button>
    );
}
