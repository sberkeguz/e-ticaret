/** Ağaç hesabı için gereken alanlar (eski kayıtlarda tek üst kategori: parentId). */
export interface TreeCategory {
    id: number | string;
    name: string;
    parentIds?: (number | string)[] | null;
    parentId?: number | string | null;
}

export const parentIdsOf = (category: Partial<TreeCategory>): number[] => {
    if (Array.isArray(category.parentIds)) return category.parentIds.map(Number);
    if (category.parentId == null) return [];
    return [Number(category.parentId)];
};

const byName = (a: TreeCategory, b: TreeCategory) => a.name.localeCompare(b.name, "tr");

export interface CategoryIndex<T extends TreeCategory> {
    byId: Map<number, T>;
    children: Map<number, T[]>;
    roots: T[];
    ancestorsOf: (id: number | string) => Set<number>;
    descendantsOf: (id: number | string) => Set<number>;
    pathOf: (id: number | string) => string[];
}

export function buildCategoryIndex<T extends TreeCategory>(categories: T[]): CategoryIndex<T> {
    const byId = new Map<number, T>();
    const children = new Map<number, T[]>();
    for (const category of categories) {
        byId.set(Number(category.id), category);
        children.set(Number(category.id), []);
    }
    const roots: T[] = [];
    for (const category of categories) {
        const parents = parentIdsOf(category).filter((id) => byId.has(id));
        if (parents.length === 0) roots.push(category);
        for (const parentId of parents) children.get(parentId)?.push(category);
    }
    for (const list of children.values()) list.sort(byName);
    roots.sort(byName);

    const ancestorsOf = (id: number | string) => {
        const result = new Set<number>();
        const stack = [...parentIdsOf(byId.get(Number(id)) ?? {})];
        while (stack.length) {
            const current = stack.pop()!;
            if (result.has(current) || !byId.has(current)) continue;
            result.add(current);
            stack.push(...parentIdsOf(byId.get(current) ?? {}));
        }
        return result;
    };

    const descendantsOf = (id: number | string) => {
        const result = new Set<number>();
        const stack = [...(children.get(Number(id)) ?? []).map((child) => Number(child.id))];
        while (stack.length) {
            const current = stack.pop()!;
            if (result.has(current)) continue;
            result.add(current);
            stack.push(...(children.get(current) ?? []).map((child) => Number(child.id)));
        }
        return result;
    };

    const pathOf = (id: number | string) => {
        const names: string[] = [];
        const seen = new Set<number>();
        let current = byId.get(Number(id));
        while (current && !seen.has(Number(current.id))) {
            seen.add(Number(current.id));
            names.unshift(current.name);
            const [firstParent] = parentIdsOf(current);
            current = firstParent == null ? undefined : byId.get(firstParent);
        }
        return names;
    };

    return { byId, children, roots, ancestorsOf, descendantsOf, pathOf };
}

export const normalizeName = (value: string): string => value.trim().toLocaleLowerCase("tr").replace(/\s+/g, " ");
