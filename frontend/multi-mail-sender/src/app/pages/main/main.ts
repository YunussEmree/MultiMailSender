import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  CompanyData,
  JobState,
  JobSummary,
  ProgressRow,
  RequestData,
  RowStatus,
} from '../../models/models';
import { MailSenderService } from '../../service/mail-sender';
import { csvToCompanies, parseCsv } from '../../utils/csv';
import { missingParams, placeholdersOf, renderTemplate } from '../../utils/template';

type Notice = { type: 'success' | 'danger' | 'info' | 'warning'; text: string } | null;

const DEFAULT_KEYS = ['companyName', 'companyNumber', 'companyWebsite'];
const DRAFT_KEY = 'mms-draft-v2';

@Component({
  selector: 'app-main',
  standalone: true,
  imports: [FormsModule, CommonModule],
  templateUrl: './main.html',
  styleUrls: ['./main.css'],
})
export class MainComponent implements OnInit, OnDestroy {
  readonly bodyPlaceholder = 'Merhaba {companyName} ekibi,\n\n...';

  request: RequestData = this.emptyRequest();
  files: File[] = [];

  search = '';
  previewIndex = 0;
  showPassword = false;
  showPreview = true;

  serverUp = false;
  notice: Notice = null;

  jobId: string | null = null;
  jobState: JobState | null = null;
  rows: Record<number, ProgressRow> = {};
  history: JobSummary[] = [];
  starting = false;

  private nextId = 1;
  private es: EventSource | null = null;
  private avgSendMs = 0;
  private samples = 0;
  private cfgMinMs = 0;
  private cfgMaxMs = 0;
  private serverTimer: ReturnType<typeof setInterval> | null = null;

  constructor(private api: MailSenderService) {}

  // ------------------------------------------------------------------ lifecycle

  ngOnInit() {
    this.restoreDraft();
    this.checkServer();
    this.loadHistory();
    this.serverTimer = setInterval(() => this.checkServer(), 15000);
  }

  ngOnDestroy() {
    this.es?.close();
    if (this.serverTimer) clearInterval(this.serverTimer);
  }

  // ------------------------------------------------------------------ derived state

  get running(): boolean {
    return this.jobState === 'RUNNING';
  }

  get paramKeys(): string[] {
    const keys = new Set<string>();
    for (const c of this.request.companyData) Object.keys(c.parameters).forEach((k) => keys.add(k));
    keys.delete('verifiedAt'); // internal data-freshness stamp, not a template field
    if (!keys.size) return [...DEFAULT_KEYS];
    // keep the well-known columns first, in a stable order
    return [...DEFAULT_KEYS.filter((k) => keys.has(k)), ...[...keys].filter((k) => !DEFAULT_KEYS.includes(k))];
  }

  get visible(): { c: CompanyData; i: number }[] {
    const q = this.search.trim().toLowerCase();
    const all = this.request.companyData.map((c, i) => ({ c, i }));
    if (!q) return all;
    return all.filter(({ c }) =>
      (c.companyMail + ' ' + Object.values(c.parameters).join(' ')).toLowerCase().includes(q),
    );
  }

  get usedPlaceholders(): string[] {
    return [...new Set([...placeholdersOf(this.request.subject), ...placeholdersOf(this.request.bodydraft)])];
  }

  /** Recipients whose data lacks a value for a placeholder the template uses (they will be skipped). */
  get incompleteCount(): number {
    return this.request.companyData.filter((c) => this.missingFor(c).length > 0).length;
  }

  missingFor(c: CompanyData): string[] {
    return [
      ...new Set([
        ...missingParams(this.request.subject, c.parameters),
        ...missingParams(this.request.bodydraft, c.parameters),
      ]),
    ];
  }

  get validRecipients(): number {
    return this.request.companyData.filter((c) => /^\S+@\S+\.\S+$/.test(c.companyMail.trim())).length;
  }

  get canSend(): boolean {
    const r = this.request;
    return (
      !this.running &&
      !this.starting &&
      this.serverUp &&
      /^\S+@\S+\.\S+$/.test(r.username.trim()) &&
      !!r.password.trim() &&
      !!r.subject.trim() &&
      !!r.bodydraft.trim() &&
      this.validRecipients > 0
    );
  }

  get sendHint(): string {
    const r = this.request;
    if (!this.serverUp) return 'Sunucuya ulaşılamıyor.';
    if (!/^\S+@\S+\.\S+$/.test(r.username.trim())) return 'Geçerli bir Gmail adresi girin.';
    if (!r.password.trim()) return 'Uygulama şifresini girin.';
    if (!r.subject.trim()) return 'Konu boş olamaz.';
    if (!r.bodydraft.trim()) return 'Mesaj boş olamaz.';
    if (!this.validRecipients) return 'En az bir geçerli alıcı ekleyin.';
    return '';
  }

  get counts() {
    const list = Object.values(this.rows);
    return {
      sent: list.filter((r) => r.status === 'sent').length,
      error: list.filter((r) => r.status === 'error').length,
      skipped: list.filter((r) => r.status === 'skipped').length,
      done: list.length,
      total: this.request.companyData.length,
    };
  }

  get percent(): number {
    const { done, total } = this.counts;
    return total ? Math.round((done / total) * 100) : 0;
  }

  get eta(): string {
    if (!this.running) return '';
    const remaining = Math.max(0, this.counts.total - this.counts.done);
    const perItem = this.avgSendMs + (this.cfgMinMs + this.cfgMaxMs) / 2;
    const secs = Math.round((remaining * perItem) / 1000);
    if (secs < 60) return `~${secs} sn`;
    return `~${Math.floor(secs / 60)} dk ${secs % 60} sn`;
  }

  get previewData(): { to: string; subject: string; body: string } | null {
    const c = this.request.companyData[Math.min(this.previewIndex, this.request.companyData.length - 1)];
    if (!c) return null;
    return {
      to: c.companyMail,
      subject: renderTemplate(this.request.subject, c.parameters),
      body: renderTemplate(this.request.bodydraft, c.parameters),
    };
  }

  statusOf(i: number): RowStatus | null {
    return this.rows[i]?.status ?? null;
  }

  statusLabel(s: RowStatus | null): string {
    switch (s) {
      case 'sent':
        return 'Gönderildi';
      case 'error':
        return 'Hata';
      case 'skipped':
        return 'Atlandı';
      case 'pending':
        return 'Bekliyor';
      default:
        return this.running ? 'Bekliyor' : '—';
    }
  }

  jobLabel(s: JobState): string {
    return {
      RUNNING: 'Devam ediyor',
      COMPLETED: 'Tamamlandı',
      CANCELLED: 'İptal edildi',
      FAILED: 'Başarısız',
      INTERRUPTED: 'Yarıda kesildi',
    }[s];
  }

  // ------------------------------------------------------------------ recipients

  addCompany() {
    const parameters: Record<string, string> = {};
    this.paramKeys.forEach((k) => (parameters[k] = ''));
    this.request.companyData.push({ id: this.nextId++, companyMail: '', parameters });
    this.search = '';
  }

  removeCompany(c: CompanyData) {
    this.request.companyData = this.request.companyData.filter((x) => x !== c);
  }

  setParam(c: CompanyData, key: string, value: string) {
    c.parameters[key] = value;
  }

  addColumn() {
    const key = window.prompt('Yeni alan adı (örn. companyWebsite):')?.trim();
    if (!key) return;
    if (!/^[A-Za-z0-9_.-]+$/.test(key)) {
      this.notify('warning', 'Alan adı yalnızca harf, rakam, _, - ve . içerebilir.');
      return;
    }
    if (this.paramKeys.includes(key)) {
      this.notify('warning', 'Bu alan zaten var.');
      return;
    }
    this.request.companyData.forEach((c) => (c.parameters[key] = ''));
  }

  removeInvalid() {
    const before = this.request.companyData.length;
    this.request.companyData = this.request.companyData.filter((c) => /^\S+@\S+\.\S+$/.test(c.companyMail.trim()));
    this.notify('info', `${before - this.request.companyData.length} geçersiz adres silindi.`);
  }

  removeDuplicates() {
    const seen = new Set<string>();
    const before = this.request.companyData.length;
    this.request.companyData = this.request.companyData.filter((c) => {
      const k = c.companyMail.trim().toLowerCase();
      if (!k || seen.has(k)) return false;
      seen.add(k);
      return true;
    });
    this.notify('info', `${before - this.request.companyData.length} tekrar eden adres silindi.`);
  }

  clearRecipients() {
    if (!window.confirm('Tüm alıcılar silinsin mi?')) return;
    this.request.companyData = [];
    this.rows = {};
  }

  // ------------------------------------------------------------------ import / export

  onFileSelected(evt: Event) {
    const input = evt.target as HTMLInputElement;
    if (input.files) this.files = [...this.files, ...Array.from(input.files)];
    input.value = '';
  }

  removeFile(f: File) {
    this.files = this.files.filter((x) => x !== f);
  }

  onImportFileChange(evt: Event) {
    const input = evt.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const text = String(reader.result || '');
        if (file.name.toLowerCase().endsWith('.csv')) this.importCsv(text);
        else this.importJson(JSON.parse(text));
      } catch (err: any) {
        this.notify('danger', 'İçe aktarma başarısız: ' + (err?.message || 'dosya biçimi hatalı'));
      } finally {
        input.value = '';
      }
    };
    reader.readAsText(file);
  }

  private importCsv(text: string) {
    const list = csvToCompanies(parseCsv(text));
    this.setCompanies(list);
    this.notify('success', `${list.length} alıcı CSV'den yüklendi.`);
  }

  importJson(obj: any) {
    if (!obj || typeof obj !== 'object' || !Array.isArray(obj.companyData)) {
      throw new Error('JSON içinde companyData[] bulunmalı.');
    }
    if (typeof obj.subject === 'string') this.request.subject = obj.subject;
    if (typeof obj.bodydraft === 'string') this.request.bodydraft = obj.bodydraft;
    if (typeof obj.fromName === 'string') this.request.fromName = obj.fromName;
    if (typeof obj.html === 'boolean') this.request.html = obj.html;
    // credentials inside shared files are only used to prefill empty fields
    if (!this.request.username && typeof obj.username === 'string') this.request.username = obj.username;
    if (!this.request.password && typeof obj.password === 'string') this.request.password = obj.password;
    this.setCompanies(obj.companyData);
    this.notify('success', `${obj.companyData.length} alıcı yüklendi.`);
  }

  private setCompanies(list: any[]) {
    this.nextId = 0;
    this.rows = {};
    this.request.companyData = list.map((c) => ({
      id: this.nextId++,
      companyMail: String(c.companyMail ?? '').trim(),
      parameters: Object.fromEntries(
        Object.entries(c.parameters && typeof c.parameters === 'object' ? c.parameters : {}).map(([k, v]) => [
          k,
          String(v ?? ''),
        ]),
      ),
    }));
  }

  exportJson() {
    const { subject, bodydraft, fromName, html, companyData } = this.request;
    const blob = new Blob([JSON.stringify({ subject, bodydraft, fromName, html, companyData }, null, 2)], {
      type: 'application/json;charset=utf-8',
    });
    const stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19);
    this.download(blob, `multi-mail-sender-${stamp}.json`);
  }

  downloadReport(jobId: string) {
    const a = document.createElement('a');
    a.href = this.api.exportUrl(jobId);
    a.download = '';
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  private download(blob: Blob, filename: string) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  // ------------------------------------------------------------------ sending

  sendMails() {
    if (!this.canSend) return;
    this.es?.close();
    this.rows = {};
    this.samples = 0;
    this.avgSendMs = 0;
    this.notice = null;
    this.starting = true;
    this.saveDraft();

    const payload: RequestData = {
      ...this.request,
      companyData: this.request.companyData
        .filter((c) => c.companyMail.trim())
        .map((c, i) => ({ ...c, id: i, companyMail: c.companyMail.trim() })),
    };
    // rows are keyed by the index inside the sent list, so remember the mapping
    this.sentOrder = this.request.companyData.filter((c) => c.companyMail.trim());

    this.api.startMailJob(payload, this.files).subscribe({
      next: (res) => {
        this.starting = false;
        this.jobId = String(res.data);
        this.jobState = 'RUNNING';
        this.follow(this.jobId);
      },
      error: (err) => {
        this.starting = false;
        this.notify('danger', err?.error?.message ? this.describe(err.error) : 'Gönderim başlatılamadı.');
      },
    });
  }

  private sentOrder: CompanyData[] = [];

  private describe(body: { message?: string; data?: any }): string {
    return body.data && typeof body.data === 'string' ? `${body.message}: ${body.data}` : body.message || 'Hata';
  }

  private follow(jobId: string) {
    const es = this.api.openJobEventSource(jobId);
    this.es = es;

    es.addEventListener('started', (e) => {
      try {
        const cfg = JSON.parse((e as MessageEvent).data);
        this.cfgMinMs = Number(cfg.minMs) || 0;
        this.cfgMaxMs = Number(cfg.maxMs) || 0;
      } catch {}
    });

    es.addEventListener('progress', (e) => {
      try {
        const d = JSON.parse((e as MessageEvent).data);
        this.rows = { ...this.rows, [d.index]: d as ProgressRow };
        if (d.sendMs) {
          this.samples += 1;
          this.avgSendMs += (Number(d.sendMs) - this.avgSendMs) / this.samples;
        }
      } catch {}
    });

    es.addEventListener('finished', (e) => {
      es.close();
      this.es = null;
      let d: any = {};
      try {
        d = JSON.parse((e as MessageEvent).data);
      } catch {}
      this.jobState = (d.status as JobState) || 'COMPLETED';
      const summary = `${d.sent ?? 0} gönderildi, ${d.failed ?? 0} hata, ${d.skipped ?? 0} atlandı.`;
      if (this.jobState === 'COMPLETED') this.notify('success', 'Gönderim tamamlandı: ' + summary);
      else if (this.jobState === 'CANCELLED') this.notify('warning', 'Gönderim iptal edildi: ' + summary);
      else this.notify('danger', (d.error || 'Gönderim başarısız.') + ' ' + summary);
      this.loadHistory();
    });

    // fires on connection problems; EventSource reconnects and the backend replays past events
    es.onerror = () => {
      if (es.readyState === EventSource.CLOSED && this.running) {
        this.notify('warning', 'Sunucu bağlantısı koptu. Rapor "Geçmiş" bölümünden indirilebilir.');
        this.jobState = null;
        this.loadHistory();
      }
    };
  }

  cancel() {
    if (!this.jobId) return;
    this.api.cancelJob(this.jobId).subscribe({
      next: () => this.notify('info', 'İptal isteği gönderildi, mevcut mail bittikten sonra durur.'),
      error: () => this.notify('danger', 'İptal edilemedi.'),
    });
  }

  /** Row status for the recipients table (rows are indexed by position in the sent list). */
  rowFor(c: CompanyData): ProgressRow | undefined {
    const idx = this.sentOrder.indexOf(c);
    return idx >= 0 ? this.rows[idx] : undefined;
  }

  // ------------------------------------------------------------------ misc

  clearAll() {
    if (!window.confirm('Konu, mesaj ve tüm alıcılar temizlensin mi? (Hesap bilgileri kalır)')) return;
    this.request = { ...this.emptyRequest(), username: this.request.username, password: this.request.password };
    this.files = [];
    this.rows = {};
    this.jobId = null;
    this.jobState = null;
    this.notice = null;
    this.es?.close();
    this.es = null;
  }

  insertPlaceholder(key: string, area: HTMLTextAreaElement) {
    const token = `{${key}}`;
    const start = area.selectionStart ?? this.request.bodydraft.length;
    const end = area.selectionEnd ?? start;
    this.request.bodydraft = this.request.bodydraft.slice(0, start) + token + this.request.bodydraft.slice(end);
    setTimeout(() => {
      area.focus();
      area.setSelectionRange(start + token.length, start + token.length);
    });
  }

  checkServer() {
    this.api.checkServer().subscribe({
      next: () => (this.serverUp = true),
      error: () => (this.serverUp = false),
    });
  }

  loadHistory() {
    this.api.listJobs().subscribe({
      next: (jobs) => (this.history = jobs.slice(0, 10)),
      error: () => {},
    });
  }

  notify(type: 'success' | 'danger' | 'info' | 'warning', text: string) {
    this.notice = { type, text };
  }

  saveDraft() {
    try {
      const { subject, bodydraft, fromName, html, skipAlreadySent, username, companyData } = this.request;
      localStorage.setItem(
        DRAFT_KEY,
        JSON.stringify({ subject, bodydraft, fromName, html, skipAlreadySent, username, companyData }),
      );
    } catch {}
  }

  private restoreDraft() {
    try {
      const raw = localStorage.getItem(DRAFT_KEY);
      if (!raw) return;
      const d = JSON.parse(raw);
      this.request = { ...this.emptyRequest(), ...d, password: '' };
      this.nextId = Math.max(-1, ...(d.companyData || []).map((c: CompanyData) => c.id)) + 1;
    } catch {}
  }

  private draftTimer: ReturnType<typeof setTimeout> | null = null;

  /** Debounced autosave of the form (password is never stored). */
  onFormChange() {
    if (this.draftTimer) clearTimeout(this.draftTimer);
    this.draftTimer = setTimeout(() => this.saveDraft(), 600);
  }

  private emptyRequest(): RequestData {
    return {
      username: '',
      password: '',
      fromName: '',
      subject: '',
      bodydraft: '',
      html: false,
      skipAlreadySent: true,
      companyData: [],
    };
  }

  trackCompany = (_: number, item: { c: CompanyData }) => item.c;
}
