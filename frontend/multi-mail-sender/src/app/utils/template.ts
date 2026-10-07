const PLACEHOLDER = /\{([A-Za-z0-9_.-]+)\}/g;

/** Names of the {placeholders} used in a template. */
export function placeholdersOf(template: string): string[] {
  const found = new Set<string>();
  for (const m of (template || '').matchAll(PLACEHOLDER)) found.add(m[1]);
  return [...found];
}

/** Placeholders of `template` that have no non-blank value in `params`. */
export function missingParams(template: string, params: Record<string, string>): string[] {
  return placeholdersOf(template).filter((k) => !(params?.[k] ?? '').trim());
}

export function renderTemplate(template: string, params: Record<string, string>): string {
  return (template || '').replace(PLACEHOLDER, (whole, key) => {
    const v = params?.[key];
    return v === undefined || v === null ? whole : v;
  });
}
