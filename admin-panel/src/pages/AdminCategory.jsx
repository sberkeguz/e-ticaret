import { useEffect, useMemo, useRef, useState } from "react";
import { apiFetch } from "../api";
import { buildCategoryIndex, normalizeName, parentIdsOf } from "../categoryTree";
import CategoryParentSelect from "../components/CategoryParentSelect";

const readError = async (response, fallback) => {
    const body = await response.json().catch(() => null);
    return body?.message || fallback;
};

const emptyPanel = { mode: "create", id: null, name: "", parentIds: [] };

function Highlight({ text, term }) {
    if (!term) return text;
    const start = text.toLocaleLowerCase("tr").indexOf(term);
    if (start < 0) return text;
    return <>{text.slice(0, start)}<mark>{text.slice(start, start + term.length)}</mark>{text.slice(start + term.length)}</>;
}

export default function AdminCategory() {
    const [categories, setCategories] = useState([]);
    const [products, setProducts] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");
    const [toast, setToast] = useState("");
    const [search, setSearch] = useState("");
    const [expanded, setExpanded] = useState(() => new Set());
    const [panel, setPanel] = useState(emptyPanel);
    const [saving, setSaving] = useState(false);
    const nameRef = useRef(null);

    useEffect(() => {
        let active = true;
        Promise.all([apiFetch("/api/admin/categories"), apiFetch("/api/products")])
            .then(async ([categoryResponse, productResponse]) => {
                if (!categoryResponse.ok) throw new Error(await readError(categoryResponse, "Kategoriler yüklenemedi."));
                const loadedCategories = await categoryResponse.json();
                const loadedProducts = productResponse.ok ? await productResponse.json() : [];
                if (!active) return;
                setCategories(loadedCategories);
                setProducts(Array.isArray(loadedProducts) ? loadedProducts : []);
                setExpanded(new Set(loadedCategories.filter((category) => parentIdsOf(category).length === 0).map((category) => Number(category.id))));
            })
            .catch((loadError) => { if (active) setError(loadError.message || "Kategoriler yüklenemedi."); })
            .finally(() => { if (active) setLoading(false); });
        return () => { active = false; };
    }, []);

    useEffect(() => {
        if (!toast) return undefined;
        const timer = setTimeout(() => setToast(""), 2600);
        return () => clearTimeout(timer);
    }, [toast]);

    const index = useMemo(() => buildCategoryIndex(categories), [categories]);

    const productCount = useMemo(() => {
        const counts = new Map();
        for (const product of products) {
            for (const id of product.categoryIds ?? []) counts.set(Number(id), (counts.get(Number(id)) ?? 0) + 1);
        }
        return counts;
    }, [products]);

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

    const stats = useMemo(() => ({
        total: categories.length,
        roots: index.roots.length,
        children: categories.length - index.roots.length,
        empty: categories.filter((category) => !productCount.get(Number(category.id))).length,
    }), [categories, index, productCount]);

    const excludedParents = useMemo(() => {
        if (panel.mode !== "edit") return new Set();
        return new Set([panel.id, ...index.descendantsOf(panel.id)]);
    }, [panel.mode, panel.id, index]);

    const duplicate = useMemo(() => {
        const wanted = normalizeName(panel.name);
        if (!wanted) return null;
        return categories.find((category) => Number(category.id) !== panel.id && normalizeName(category.name) === wanted) ?? null;
    }, [categories, panel.name, panel.id]);

    const previewPaths = panel.parentIds.length
        ? panel.parentIds.map((id) => [...index.pathOf(id), panel.name.trim() || "Yeni kategori"])
        : [[panel.name.trim() || "Yeni kategori"]];

    const toggle = (id) => setExpanded((current) => {
        const next = new Set(current);
        if (next.has(id)) next.delete(id); else next.add(id);
        return next;
    });
    const expandAll = () => setExpanded(new Set(categories.map((category) => Number(category.id))));
    const collapseAll = () => setExpanded(new Set());

    const openCreate = (parentId = null) => {
        setError("");
        setPanel({ ...emptyPanel, parentIds: parentId == null ? [] : [parentId] });
        if (parentId != null) setExpanded((current) => new Set([...current, parentId]));
        setTimeout(() => nameRef.current?.focus(), 0);
    };
    const openEdit = (category) => {
        setError("");
        setPanel({ mode: "edit", id: Number(category.id), name: category.name, parentIds: parentIdsOf(category) });
        setTimeout(() => nameRef.current?.focus(), 0);
    };

    const submit = async (event, keepAdding = false) => {
        event.preventDefault();
        const name = panel.name.trim();
        if (!name) {
            setError("Kategori adı boş olamaz.");
            nameRef.current?.focus();
            return;
        }
        setSaving(true);
        setError("");
        try {
            const editing = panel.mode === "edit";
            const response = await apiFetch(editing ? `/api/admin/categories/${panel.id}` : "/api/admin/categories", {
                method: editing ? "PUT" : "POST",
                body: JSON.stringify({ name, parentIds: panel.parentIds }),
            });
            if (!response.ok) throw new Error(await readError(response, editing ? "Kategori güncellenemedi." : "Kategori oluşturulamadı."));
            const saved = await response.json();
            if (editing) {
                setCategories((current) => current.map((category) => (Number(category.id) === Number(saved.id) ? saved : category)));
                setToast(`"${saved.name}" güncellendi.`);
                setPanel(emptyPanel);
            } else {
                setCategories((current) => [...current, saved]);
                setExpanded((current) => new Set([...current, ...panel.parentIds]));
                setToast(`"${saved.name}" oluşturuldu.`);
                setPanel(keepAdding ? { ...emptyPanel, parentIds: panel.parentIds } : emptyPanel);
                if (keepAdding) setTimeout(() => nameRef.current?.focus(), 0);
            }
        } catch (requestError) {
            setError(requestError.message || "Sunucuya ulaşılamadı.");
        } finally {
            setSaving(false);
        }
    };

    const deleteCategory = async (category) => {
        const id = Number(category.id);
        const childCount = index.children.get(id)?.length ?? 0;
        if (childCount > 0) {
            setError(`"${category.name}" kategorisinin ${childCount} alt kategorisi var. Önce alt kategorileri başka bir yere taşıyın veya silin.`);
            return;
        }
        const count = productCount.get(id) ?? 0;
        const message = count
            ? `"${category.name}" silinsin mi? Bu kategori ${count} üründen kaldırılacak; ürünler silinmez.`
            : `"${category.name}" kategorisi silinsin mi?`;
        if (!window.confirm(message)) return;
        setError("");
        try {
            const response = await apiFetch(`/api/admin/categories/${id}`, { method: "DELETE" });
            if (!response.ok) throw new Error(await readError(response, "Kategori silinemedi."));
            setCategories((current) => current.filter((item) => Number(item.id) !== id));
            setProducts((current) => current.map((product) => ({ ...product, categoryIds: (product.categoryIds ?? []).filter((value) => Number(value) !== id) })));
            if (panel.id === id) setPanel(emptyPanel);
            setToast(`"${category.name}" silindi.`);
        } catch (requestError) {
            setError(requestError.message || "Kategori silinemedi.");
        }
    };

    const renderNode = (category, depth, trail) => {
        const id = Number(category.id);
        if (trail.has(id)) return null;
        if (visibleIds && !visibleIds.has(id)) return null;
        const kids = index.children.get(id) ?? [];
        const isOpen = term ? true : expanded.has(id);
        const parents = parentIdsOf(category);
        const otherParents = parents.length > 1
            ? parents.filter((parentId) => parentId !== [...trail].at(-1)).map((parentId) => index.byId.get(parentId)?.name).filter(Boolean)
            : [];
        const count = productCount.get(id) ?? 0;
        const nextTrail = new Set(trail).add(id);
        return (
            <li key={`${[...trail].join("-")}-${id}`} role="treeitem" aria-expanded={kids.length ? isOpen : undefined}>
                <div className={`category-node${panel.mode === "edit" && panel.id === id ? " selected" : ""}`} style={{ "--depth": depth }}>
                    <button
                        type="button"
                        className={`category-toggle${kids.length ? "" : " leaf"}${isOpen ? " open" : ""}`}
                        onClick={() => kids.length && toggle(id)}
                        aria-label={kids.length ? (isOpen ? "Daralt" : "Genişlet") : undefined}
                        tabIndex={kids.length ? 0 : -1}
                    >{kids.length ? "›" : "•"}</button>
                    <button type="button" className="category-node-main" onClick={() => openEdit(category)}>
                        <span className="category-node-name"><Highlight text={category.name} term={term} /></span>
                        <span className="category-node-meta">
                            <span className={`category-pill${count ? "" : " muted"}`}>{count} ürün</span>
                            {kids.length > 0 && <span className="category-pill muted">{kids.length} alt</span>}
                            {otherParents.length > 0 && <span className="category-pill info" title={`Ayrıca: ${otherParents.join(", ")}`}>+{otherParents.length} üst</span>}
                        </span>
                    </button>
                    <div className="category-node-actions">
                        <button type="button" className="button button-quiet button-small" onClick={() => openCreate(id)} title="Bu kategorinin altına yeni kategori ekle">+ Alt</button>
                        <button type="button" className="button button-quiet button-small button-icon" onClick={() => openEdit(category)} aria-label={`${category.name} kategorisini düzenle`} title="Düzenle">✎</button>
                        <button type="button" className="button button-danger button-small button-icon" onClick={() => deleteCategory(category)} aria-label={`${category.name} kategorisini sil`} title="Sil">×</button>
                    </div>
                </div>
                {kids.length > 0 && isOpen && (
                    <ul role="group">{kids.map((child) => renderNode(child, depth + 1, nextTrail))}</ul>
                )}
            </li>
        );
    };

    const editingCategory = panel.mode === "edit" ? index.byId.get(panel.id) : null;

    return (
        <>
            <div className="page-heading">
                <div>
                    <div className="eyebrow">KATALOG MİMARİSİ</div>
                    <h1 className="page-title">Kategoriler</h1>
                    <p className="page-subtitle">Kategori ağacını düzenleyin; bir kategorinin altına tek tıkla alt kategori ekleyin.</p>
                </div>
                <button className="button button-primary" type="button" onClick={() => openCreate()}>+ Yeni ana kategori</button>
            </div>

            <section className="stat-grid">
                <article className="surface stat-card"><span className="stat-icon">◈</span><span><span className="stat-value">{stats.total}</span><span className="stat-label">Toplam kategori</span></span></article>
                <article className="surface stat-card"><span className="stat-icon">⌂</span><span><span className="stat-value">{stats.roots}</span><span className="stat-label">Ana kategori</span></span></article>
                <article className="surface stat-card"><span className="stat-icon">↳</span><span><span className="stat-value">{stats.children}</span><span className="stat-label">Alt kategori</span></span></article>
                <article className="surface stat-card"><span className="stat-icon" style={{ color: "var(--amber)", background: "#fff4e3" }}>∅</span><span><span className="stat-value">{stats.empty}</span><span className="stat-label">Ürünü olmayan</span></span></article>
            </section>

            {error && <p className="error-banner" role="alert">{error}</p>}

            <section className="category-workspace">
                <div className="surface surface-pad">
                    <div className="toolbar">
                        <div>
                            <h2 className="section-title">Kategori ağacı</h2>
                            <p className="section-caption">Düzenlemek için bir kategoriye tıklayın.</p>
                        </div>
                        <div className="toolbar-right">
                            <div className="search-field">
                                <span className="search-glyph">⌕</span>
                                <input className="input" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Kategori ara..." />
                            </div>
                            <button type="button" className="button button-quiet button-small" onClick={expandAll} disabled={Boolean(term)}>Tümünü aç</button>
                            <button type="button" className="button button-quiet button-small" onClick={collapseAll} disabled={Boolean(term)}>Kapat</button>
                        </div>
                    </div>
                    {loading ? <div className="loading-state">Kategoriler yükleniyor…</div> : categories.length === 0 ? (
                        <div className="empty-state">
                            <div className="empty-icon">◈</div>
                            <h3>İlk kategorinizi oluşturun</h3>
                            <p>Sağdaki panelden bir ana kategori ekleyerek başlayın.</p>
                        </div>
                    ) : visibleIds && visibleIds.size === 0 ? (
                        <div className="empty-state"><div className="empty-icon">⌕</div><h3>Eşleşen kategori yok</h3><p>Başka bir arama deneyin.</p></div>
                    ) : (
                        <ul className="category-tree" role="tree">{index.roots.map((root) => renderNode(root, 0, new Set()))}</ul>
                    )}
                </div>

                <form className="surface surface-pad category-panel" onSubmit={(event) => submit(event)}>
                    <div className="category-panel-head">
                        <span className="stat-icon">{panel.mode === "edit" ? "✎" : "+"}</span>
                        <div>
                            <h2 className="section-title">{panel.mode === "edit" ? "Kategoriyi düzenle" : "Yeni kategori"}</h2>
                            <p className="section-caption">
                                {panel.mode === "edit"
                                    ? `#${panel.id} · ${productCount.get(panel.id) ?? 0} ürün bu kategoride`
                                    : "Üst kategori seçmezseniz ana kategori olarak eklenir."}
                            </p>
                        </div>
                    </div>

                    <label className="field">
                        <span className="field-label">Kategori adı</span>
                        <input ref={nameRef} className="input" value={panel.name} maxLength={100} placeholder="Örn. Taze meyveler"
                            onChange={(event) => setPanel((current) => ({ ...current, name: event.target.value }))} />
                        {duplicate && <span className="field-warning">"{duplicate.name}" adında bir kategori zaten var ({index.pathOf(duplicate.id).join(" › ")}).</span>}
                    </label>

                    <div className="field">
                        <span className="field-label">Üst kategoriler <span className="field-optional">· isteğe bağlı, birden fazla seçilebilir</span></span>
                        <CategoryParentSelect
                            index={index}
                            categories={categories}
                            value={panel.parentIds}
                            excludedIds={excludedParents}
                            onChange={(parentIds) => setPanel((current) => ({ ...current, parentIds }))}
                        />
                        {panel.mode === "edit" && excludedParents.size > 1 && (
                            <span className="section-caption">Kendi alt kategorileri döngü oluşturacağı için listede gösterilmez.</span>
                        )}
                    </div>

                    <div className="category-preview">
                        <span className="field-label">Ağaçtaki yeri</span>
                        {previewPaths.map((path, position) => (
                            <div className="category-breadcrumb" key={position}>
                                {path.map((part, step) => (
                                    <span key={step} className={step === path.length - 1 ? "current" : ""}>{part}</span>
                                ))}
                            </div>
                        ))}
                    </div>

                    <div className="category-panel-actions">
                        {panel.mode === "edit" ? (
                            <>
                                <button className="button button-quiet" type="button" onClick={() => setPanel(emptyPanel)}>Vazgeç</button>
                                <button className="button button-primary" type="submit" disabled={saving || !panel.name.trim()}>{saving ? "Kaydediliyor…" : "Kaydet"}</button>
                            </>
                        ) : (
                            <>
                                <button className="button button-quiet" type="button" disabled={saving || !panel.name.trim()} onClick={(event) => submit(event, true)}>Oluştur ve yenisini ekle</button>
                                <button className="button button-primary" type="submit" disabled={saving || !panel.name.trim()}>{saving ? "Oluşturuluyor…" : "Oluştur"}</button>
                            </>
                        )}
                    </div>
                    {editingCategory && (
                        <button type="button" className="link-danger" onClick={() => deleteCategory(editingCategory)}>Bu kategoriyi sil</button>
                    )}
                </form>
            </section>

            {toast && <div className="toast" role="status">{toast}</div>}
        </>
    );
}
