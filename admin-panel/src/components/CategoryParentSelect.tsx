import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import type { CategoryIndex } from "../categoryTree";
import type { CategoryResponse } from "../types/api";

interface CategoryParentSelectProps {
    index: CategoryIndex<CategoryResponse>;
    categories: CategoryResponse[];
    /** Seçili üst kategori numaraları. */
    value: number[];
    onChange: (ids: number[]) => void;
    /** Seçilemeyecek kategoriler (kendisi ve alt kategorileri: döngü olmasın). */
    excludedIds?: Set<number>;
    placeholder?: string;
}

export default function CategoryParentSelect({ index, categories, value, onChange, excludedIds = new Set<number>(), placeholder = "Üst kategori ara…" }: CategoryParentSelectProps) {
    const [query, setQuery] = useState("");
    const [open, setOpen] = useState(false);
    const [highlight, setHighlight] = useState(0);
    const boxRef = useRef<HTMLDivElement>(null);
    const inputRef = useRef<HTMLInputElement>(null);

    useEffect(() => {
        const close = (event: MouseEvent) => { if (boxRef.current && !boxRef.current.contains(event.target as Node)) setOpen(false); };
        document.addEventListener("mousedown", close);
        return () => document.removeEventListener("mousedown", close);
    }, []);

    const options = useMemo(() => {
        const term = query.trim().toLocaleLowerCase("tr");
        return categories
            .filter((category) => !excludedIds.has(Number(category.id)) && !value.includes(Number(category.id)))
            .map((category) => ({ id: Number(category.id), name: category.name, path: index.pathOf(category.id) }))
            .filter((option) => !term || option.path.join(" ").toLocaleLowerCase("tr").includes(term))
            .sort((a, b) => a.path.join(" › ").localeCompare(b.path.join(" › "), "tr"));
    }, [categories, excludedIds, value, query, index]);

    const add = (id: number) => {
        onChange([...value, id]);
        setQuery("");
        setHighlight(0);
        inputRef.current?.focus();
    };
    const remove = (id: number) => onChange(value.filter((item) => item !== id));

    const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
        if (event.key === "ArrowDown") { event.preventDefault(); setOpen(true); setHighlight((current) => Math.min(current + 1, options.length - 1)); }
        else if (event.key === "ArrowUp") { event.preventDefault(); setHighlight((current) => Math.max(current - 1, 0)); }
        else if (event.key === "Enter" && open && options[highlight]) { event.preventDefault(); add(options[highlight].id); }
        else if (event.key === "Backspace" && !query && value.length) { remove(value[value.length - 1]!); }
        else if (event.key === "Escape") { setOpen(false); }
    };

    return (
        <div className={`parent-select${open ? " open" : ""}`} ref={boxRef}>
            <div className="parent-select-box" onClick={() => { setOpen(true); inputRef.current?.focus(); }}>
                {value.map((id) => (
                    <span className="category-chip" key={id} title={index.pathOf(id).join(" › ")}>
                        {index.byId.get(id)?.name ?? `#${id}`}
                        <button type="button" aria-label={`${index.byId.get(id)?.name ?? id} üst kategorisini kaldır`} onClick={(event) => { event.stopPropagation(); remove(id); }}>✕</button>
                    </span>
                ))}
                <input
                    ref={inputRef}
                    className="parent-select-input"
                    value={query}
                    placeholder={value.length ? "" : placeholder}
                    onFocus={() => setOpen(true)}
                    onChange={(event) => { setQuery(event.target.value); setOpen(true); setHighlight(0); }}
                    onKeyDown={onKeyDown}
                    role="combobox"
                    aria-expanded={open}
                    aria-autocomplete="list"
                />
            </div>
            {open && (
                <ul className="parent-select-menu" role="listbox">
                    {options.length ? options.map((option, position) => (
                        <li
                            key={option.id}
                            role="option"
                            aria-selected={position === highlight}
                            className={position === highlight ? "active" : ""}
                            onMouseEnter={() => setHighlight(position)}
                            onMouseDown={(event) => { event.preventDefault(); add(option.id); }}
                        >
                            <span className="parent-select-name">{option.name}</span>
                            {option.path.length > 1 && <span className="parent-select-path">{option.path.slice(0, -1).join(" › ")}</span>}
                        </li>
                    )) : <li className="parent-select-empty">{query ? "Eşleşen kategori yok" : "Seçilebilecek başka kategori yok"}</li>}
                </ul>
            )}
        </div>
    );
}
