import { csvToCompanies, parseCsv } from './csv';
import { missingParams, placeholdersOf, renderTemplate } from './template';

describe('template utils', () => {
  it('finds placeholders once each', () => {
    expect(placeholdersOf('{a} {b} {a}')).toEqual(['a', 'b']);
  });

  it('reports blank or absent values as missing', () => {
    expect(missingParams('{a} {b} {c}', { a: 'x', b: ' ' })).toEqual(['b', 'c']);
  });

  it('renders known values and keeps unknown placeholders', () => {
    expect(renderTemplate('Hi {n} {x}', { n: 'Ada' })).toBe('Hi Ada {x}');
  });
});

describe('csv utils', () => {
  it('parses quoted fields and semicolons', () => {
    expect(parseCsv('a;b\n"x;1";"he said ""hi"""\n')).toEqual([['a', 'b'], ['x;1', 'he said "hi"']]);
  });

  it('maps rows to companies using the mail column', () => {
    const rows = parseCsv('companyName,companyMail\nAcme,info@acme.com\n');
    expect(csvToCompanies(rows)).toEqual([{ companyMail: 'info@acme.com', parameters: { companyName: 'Acme' } }]);
  });

  it('throws without a mail column', () => {
    expect(() => csvToCompanies([['a'], ['b']])).toThrow();
  });
});
