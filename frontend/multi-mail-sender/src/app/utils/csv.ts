/** Parses CSV text (comma or semicolon separated, quoted fields supported) into rows. */
export function parseCsv(text: string): string[][] {
  const clean = text.replace(/^﻿/, '');
  const firstLine = clean.split(/\r?\n/, 1)[0] || '';
  const delimiter = (firstLine.match(/;/g) || []).length > (firstLine.match(/,/g) || []).length ? ';' : ',';

  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let quoted = false;

  for (let i = 0; i < clean.length; i++) {
    const ch = clean[i];
    if (quoted) {
      if (ch === '"' && clean[i + 1] === '"') {
        field += '"';
        i++;
      } else if (ch === '"') {
        quoted = false;
      } else {
        field += ch;
      }
    } else if (ch === '"') {
      quoted = true;
    } else if (ch === delimiter) {
      row.push(field);
      field = '';
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && clean[i + 1] === '\n') i++;
      row.push(field);
      field = '';
      rows.push(row);
      row = [];
    } else {
      field += ch;
    }
  }
  if (field !== '' || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows.filter((r) => r.some((c) => c.trim() !== ''));
}

/**
 * Turns CSV rows into company entries. The first row is the header; the column named
 * companyMail / email / mail / e-posta becomes the address, every other column a parameter.
 */
export function csvToCompanies(rows: string[][]): { companyMail: string; parameters: Record<string, string> }[] {
  if (rows.length < 2) return [];
  const header = rows[0].map((h) => h.trim());
  const mailAliases = ['companymail', 'email', 'e-mail', 'mail', 'eposta', 'e-posta'];
  const mailCol = header.findIndex((h) => mailAliases.includes(h.toLowerCase()));
  if (mailCol < 0) throw new Error('CSV başlığında e-posta sütunu (companyMail / email) bulunamadı.');

  return rows.slice(1).map((r) => {
    const parameters: Record<string, string> = {};
    header.forEach((h, i) => {
      if (i !== mailCol && h) parameters[h] = (r[i] ?? '').trim();
    });
    return { companyMail: (r[mailCol] ?? '').trim(), parameters };
  });
}
