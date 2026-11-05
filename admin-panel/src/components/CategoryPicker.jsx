import { useMemo, useState } from "react";

const parentsOf = (category) => Array.isArray(category.parentIds)
    ? category.parentIds
    : category.parentId == null ? [] : [category.parentId];

export default function CategoryPicker({ categories, selectedIds, onChange, onClose }) {
    const [search, setSearch] = useState("");
    const [draft, setDraft] = useState(selectedIds.map(String));

    const names = useMemo(() => new Map(categories.map((category) => [String(category.id), category.name])), [categories]);
    const term = search.trim().toLocaleLowerCase("tr");
    const rows = useMemo(() => {
        const all = categories.map((category) => ({
            id: String(category.id),
            name: category.name,
            parentIds: parentsOf(category).map(String),
            parents: parentsOf(category).map((parentId) => names.get(String(parentId))).filter(Boolean),
            depth: 0,
            hasChildren: categories.some((other) => parentsOf(other).map(String).includes(String(category.id))),
        })).sort((a, b) => a.name.localeCompare(b.name, "tr"));
        if (term) {
            return all.filter((row) => row.name.toLocaleLowerCase("tr").includes(term) || row.parents.join(" ").toLocaleLowerCase("tr").includes(term));
        }
        const ordered = [];
        const seen = new Set();
        const walk = (row, depth) => {
            if (seen.has(row.id)) return;
            seen.add(row.id);
            ordered.push({ ...row, depth });
            if (draft.includes(row.id)) {
                all.filter((child) => child.parentIds.includes(row.id)).forEach((child) => walk(child, depth + 1));
            }
        };
        all.filter((row) => row.parentIds.length === 0).forEach((row) => walk(row, 0));
        return ordered;
    }, [categories, names, term, draft]);

    const allVisible = rows.length > 0 && rows.every((row) => draft.includes(row.id));
    const toggle = (id) => setDraft((current) => current.includes(id) ? current.filter((value) => value !== id) : [...current, id]);
    const toggleAll = () => setDraft((current) => allVisible
        ? current.filter((id) => !rows.some((row) => row.id === id))
        : [...new Set([...current, ...rows.map((row) => row.id)])]);

    return (
        <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
            <div className="modal-card modal-card-wide" role="dialog" aria-modal="true" aria-label="Kategori seç">
                <div className="modal-head">
                    <div>
                        <h2>Kategoriler</h2>
                        <p>Ana kategoriyi işaretleyince alt kategorileri açılır. Arama tüm kategorilerde çalışır.</p>
                    </div>
                    <button type="button" className="button button-quiet button-small" onClick={onClose}>✕</button>
                </div>
                <div className="modal-body">
                    <input className="input" placeholder="Kategori ara…" value={search} onChange={(event) => setSearch(event.target.value)} autoFocus />
                    <div className="picker-table-wrap">
                        <table className="picker-table">
                            <thead>
                                <tr>
                                    <th><label className="picker-item"><input type="checkbox" checked={allVisible} onChange={toggleAll} aria-label="Tümünü seç" /><span>Kategori</span></label></th>
                                    <th>Üst kategori</th>
                                </tr>
                            </thead>
                            <tbody>
                                {rows.length ? rows.map((row) => {
                                    const checked = draft.includes(row.id);
                                    return (
                                        <tr key={row.id} className={checked ? "checked" : ""} onClick={() => toggle(row.id)}>
                                            <td>
                                                <div className={`picker-item${row.depth ? " child" : ""}`} style={{ marginLeft: row.depth * 28 }}>
                                                    {row.depth > 0 && <span className="picker-arrow">↳</span>}
                                                    <input type="checkbox" checked={checked} onChange={() => toggle(row.id)} onClick={(event) => event.stopPropagation()} />
                                                    <span className="picker-name">{row.name}</span>
                                                    {row.hasChildren && !term && <span className={`picker-chevron${checked ? " open" : ""}`}>›</span>}
                                                </div>
                                            </td>
                                            <td className="picker-parent">{row.parents.length ? row.parents.join(", ") : "—"}</td>
                                        </tr>
                                    );
                                }) : <tr><td colSpan={2} className="picker-empty">Kategori bulunamadı.</td></tr>}
                            </tbody>
                        </table>
                    </div>
                </div>
                <div className="modal-actions">
                    <span className="picker-count">{draft.length} kategori seçili</span>
                    <button type="button" className="button button-quiet" onClick={() => setDraft([])}>Temizle</button>
                    <button type="button" className="button button-primary" onClick={() => { onChange(draft); onClose(); }}>Seçimi uygula</button>
                </div>
            </div>
        </div>
    );
}
