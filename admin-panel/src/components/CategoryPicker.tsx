import { useMemo, useState, type CSSProperties } from "react";
import type { CategoryResponse } from "../types/api";
import { buildCategoryIndex } from "../categoryTree";

interface CategoryPickerProps {
    categories: CategoryResponse[];
    selectedIds: (number | string)[];
    onChange: (ids: string[]) => void;
    onClose: () => void;
}

export default function CategoryPicker({ categories, selectedIds, onChange, onClose }: CategoryPickerProps) {
    const index = useMemo(() => buildCategoryIndex(categories), [categories]);
    const [search, setSearch] = useState("");
    const [draft, setDraft] = useState(() => new Set<number>(selectedIds.map(Number)));
    const [withParents, setWithParents] = useState(true);
    const [expanded, setExpanded] = useState(() => {
        const open = new Set<number>(index.roots.map((root) => Number(root.id)));
        selectedIds.forEach((id) => index.ancestorsOf(id).forEach((ancestor) => open.add(ancestor)));
        return open;
    });

    const term = search.trim().toLocaleLowerCase("tr");
    const visibleIds = useMemo(() => {
        if (!term) return null;
        const visible = new Set();
        for (const category of categories) {
            if (category.name.toLocaleLowerCase("tr").includes(term)) {
                visible.add(Number(category.id));
                index.ancestorsOf(category.id).forEach((id) => visible.add(id));
            }
        }
        return visible;
    }, [categories, index, term]);

    const toggleSelected = (id: number) => setDraft((current) => {
        const next = new Set(current);
        if (next.has(id)) {
            next.delete(id);
        } else {
            next.add(id);
            if (withParents) index.ancestorsOf(id).forEach((ancestor) => next.add(ancestor));
        }
        return next;
    });
    const toggleOpen = (id: number) => setExpanded((current) => {
        const next = new Set(current);
        if (next.has(id)) next.delete(id); else next.add(id);
        return next;
    });

    const renderNode = (category: CategoryResponse, depth: number, trail: Set<number>) => {
        const id = Number(category.id);
        if (trail.has(id) || (visibleIds && !visibleIds.has(id))) return null;
        const kids = index.children.get(id) ?? [];
        const isOpen = term ? true : expanded.has(id);
        const checked = draft.has(id);
        const selectedBelow = [...index.descendantsOf(id)].filter((child) => draft.has(child)).length;
        const nextTrail = new Set(trail).add(id);
        return (
            <li key={`${[...trail].join("-")}-${id}`}>
                <div className={`picker-node${checked ? " checked" : ""}`} style={{ "--depth": depth } as CSSProperties}>
                    <button type="button" className={`category-toggle${kids.length ? "" : " leaf"}${isOpen ? " open" : ""}`}
                        onClick={() => kids.length && toggleOpen(id)} tabIndex={kids.length ? 0 : -1}
                        aria-label={kids.length ? (isOpen ? "Daralt" : "Genişlet") : undefined}>{kids.length ? "›" : "•"}</button>
                    <label className="picker-node-label">
                        <input type="checkbox" checked={checked} onChange={() => toggleSelected(id)} />
                        <span className="picker-name">{category.name}</span>
                        {!isOpen && selectedBelow > 0 && <span className="category-pill info">{selectedBelow} seçili alt</span>}
                    </label>
                </div>
                {kids.length > 0 && isOpen && <ul>{kids.map((child) => renderNode(child, depth + 1, nextTrail))}</ul>}
            </li>
        );
    };

    const selectedList = [...draft].map((id) => index.byId.get(id)).filter((category): category is CategoryResponse => Boolean(category))
        .sort((a, b) => index.pathOf(a.id).join(" › ").localeCompare(index.pathOf(b.id).join(" › "), "tr"));

    return (
        <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
            <div className="modal-card modal-card-wide" role="dialog" aria-modal="true" aria-label="Kategori seç">
                <div className="modal-head">
                    <div>
                        <h2>Kategori seç</h2>
                        <p>Ürünün görüneceği kategorileri işaretleyin. Aramada eşleşen kategoriler üst kategorileriyle birlikte gösterilir.</p>
                    </div>
                    <button type="button" className="button button-quiet button-small" onClick={onClose} aria-label="Kapat">✕</button>
                </div>
                <div className="modal-body picker-body">
                    <div className="picker-tools">
                        <div className="search-field" style={{ flex: 1 }}>
                            <span className="search-glyph">⌕</span>
                            <input className="input" placeholder="Kategori ara…" value={search} onChange={(event) => setSearch(event.target.value)} autoFocus />
                        </div>
                        <label className="picker-switch" title="Bir alt kategori seçildiğinde üst kategorileri de otomatik seçilir">
                            <input type="checkbox" checked={withParents} onChange={(event) => setWithParents(event.target.checked)} />
                            <span>Üst kategorileri de seç</span>
                        </label>
                    </div>
                    <div className="picker-columns">
                        <div className="picker-tree-wrap">
                            {visibleIds && visibleIds.size === 0
                                ? <p className="picker-empty">Kategori bulunamadı.</p>
                                : <ul className="picker-tree">{index.roots.map((root) => renderNode(root, 0, new Set()))}</ul>}
                        </div>
                        <aside className="picker-selected">
                            <div className="field-label">Seçilenler · {draft.size}</div>
                            {selectedList.length ? (
                                <ul>
                                    {selectedList.map((category) => (
                                        <li key={category.id}>
                                            <span title={index.pathOf(category.id).join(" › ")}>
                                                {index.pathOf(category.id).length > 1 && <small>{index.pathOf(category.id).slice(0, -1).join(" › ")} › </small>}
                                                {category.name}
                                            </span>
                                            <button type="button" aria-label={`${category.name} seçimini kaldır`} onClick={() => toggleSelected(Number(category.id))}>✕</button>
                                        </li>
                                    ))}
                                </ul>
                            ) : <p className="section-caption">Henüz kategori seçilmedi.</p>}
                        </aside>
                    </div>
                </div>
                <div className="modal-actions">
                    <button type="button" className="button button-quiet" onClick={() => setDraft(new Set())} disabled={!draft.size}>Temizle</button>
                    <button type="button" className="button button-primary" onClick={() => { onChange([...draft].map(String)); onClose(); }}>Seçimi uygula</button>
                </div>
            </div>
        </div>
    );
}
