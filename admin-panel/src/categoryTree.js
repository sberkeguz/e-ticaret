export const parentIdsOf = (category) => {
    if (Array.isArray(category.parentIds)) return category.parentIds.map(Number);
    if (category.parentId == null) return [];
    return [Number(category.parentId)];
};

const byName = (a, b) => a.name.localeCompare(b.name, "tr");

export function buildCategoryIndex(categories) {
    const byId = new Map();
    const children = new Map();
    for (const category of categories) {
        byId.set(Number(category.id), category);
        children.set(Number(category.id), []);
    }
    const roots = [];
    for (const category of categories) {
        const parents = parentIdsOf(category).filter((id) => byId.has(id));
        if (parents.length === 0) roots.push(category);
        for (const parentId of parents) children.get(parentId).push(category);
    }
    for (const list of children.values()) list.sort(byName);
    roots.sort(byName);

    const ancestorsOf = (id) => {
        const result = new Set();
        const stack = [...parentIdsOf(byId.get(Number(id)) ?? {})];
        while (stack.length) {
            const current = stack.pop();
            if (result.has(current) || !byId.has(current)) continue;
            result.add(current);
            stack.push(...parentIdsOf(byId.get(current)));
        }
        return result;
    };

    const descendantsOf = (id) => {
        const result = new Set();
        const stack = [...(children.get(Number(id)) ?? []).map((child) => Number(child.id))];
        while (stack.length) {
            const current = stack.pop();
            if (result.has(current)) continue;
            result.add(current);
            stack.push(...(children.get(current) ?? []).map((child) => Number(child.id)));
        }
        return result;
    };

    const pathOf = (id) => {
        const names = [];
        const seen = new Set();
        let current = byId.get(Number(id));
        while (current && !seen.has(Number(current.id))) {
            seen.add(Number(current.id));
            names.unshift(current.name);
            const [firstParent] = parentIdsOf(current);
            current = firstParent == null ? null : byId.get(firstParent);
        }
        return names;
    };

    return { byId, children, roots, ancestorsOf, descendantsOf, pathOf };
}

export const normalizeName = (value) => value.trim().toLocaleLowerCase("tr").replace(/\s+/g, " ");
