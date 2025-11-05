import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClientModule } from '@angular/common/http';
import { CommonModule } from '@angular/common';
import { NgbModule } from '@ng-bootstrap/ng-bootstrap';
import { AddOnePipe } from '../../pipe/add-one-pipe';
import { MailSenderService } from '../../service/mail-sender';

interface CompanyData {
  id: number;
  companyMail: string;
  parameters: Record<string, string>;
}

interface RequestData {
  username: string;
  password: string;
  subject: string;
  bodydraft: string;
  companyData: CompanyData[];
}

@Component({
  selector: 'app-main',
  standalone: true,
  imports: [FormsModule, HttpClientModule, CommonModule, NgbModule, AddOnePipe],
  providers: [MailSenderService],
  templateUrl: './main.html',
  styleUrls: ['./main.css'],
})
export class MainComponent {
  request: RequestData = {
    username: '',
    password: '',
    subject: '',
    bodydraft: '',
    companyData: [
      {
        id: 0,
        companyMail: '',
        parameters: { companyName: '', companyNumber: '' },
      },
    ],
  };

  files: File[] = [];
  responseMessage = '';
  isSuccess = 0; // 0: sending, 1: success, -1: error
  serverStatus = 'Server Down';
  progress: { index: number; companyMail: string; status: string; message: string }[] = [];
  etaMinSeconds = 0;
  etaMaxSeconds = 0;
  private avgSendMs = 0;
  private avgCooldownMs = 0;
  private samples = 0;
  private cfgMinMs = 0;
  private cfgMaxMs = 0;
  private currentEventSource: EventSource | null = null;

  private nextCompanyId = 1;

  constructor(private mailService: MailSenderService) { this.checkServer(); }

  trackByIndex(_i: number, _item: any) {
    return _i;
  }

  addCompany() {
    this.request.companyData.push({
      id: this.nextCompanyId++,
      companyMail: '',
      parameters: { companyName: '', companyNumber: '' },
    });
  }

  removeCompany(i: number) {
    this.request.companyData.splice(i, 1);
  }

  addParameter(company: CompanyData) {
    const key = window.prompt('Add new parameter key (e.g. companyName):');
    if (!key) {
      return;
    }
    if (company.parameters.hasOwnProperty(key)) {
      window.alert('Parameter already exists.');
      return;
    }
    company.parameters[key] = '';
  }

  removeParameter(company: CompanyData, key: string) {
    delete company.parameters[key];
  }

  onFileSelected(evt: Event) {
    const input = evt.target as HTMLInputElement;
    if (input.files) {
      this.files = Array.from(input.files);
    }
  }

  private buildExportObject() {
    const { subject, bodydraft, companyData } = this.request;
    return { subject, bodydraft, companyData };
  }

  trackByParamKey(_i: number, item: { key: string; value: any }) {
    return item.key;
  }

  kvNoSort = () => 0;

  exportJson() {
    const safe = this.buildExportObject();
    const pretty = JSON.stringify(safe, null, 2);
    const blob = new Blob([pretty], { type: 'application/json;charset=utf-8' });
    const ts = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19);
    const filename = `multi-mail-sender-${ts}.json`;

    const navAny: any = window.navigator as any;
    if (navAny && navAny.msSaveOrOpenBlob) {
      navAny.msSaveOrOpenBlob(blob, filename);
      return;
    }

    const a: HTMLAnchorElement = document.createElement('a');
    const url = (window.URL || (window as any).webkitURL).createObjectURL(blob);
    a.href = url;
    a.download = filename;
    a.rel = 'noopener';
    a.style.display = 'none';
    document.body.appendChild(a);

    const supportsDownload = typeof a.download !== 'undefined';

    const revokeAndRemove = () => {
      (window.URL || (window as any).webkitURL).revokeObjectURL(url);
      if (a && a.parentNode) a.parentNode.removeChild(a);
    };

    if (supportsDownload) {
      a.click();
      setTimeout(revokeAndRemove, 1200);
      return;
    }

    let win: Window | null = null;
    try {
      win = window.open(url, '_blank');
    } catch {}
    if (win) {
      setTimeout(revokeAndRemove, 3000);
      return;
    }

    const reader = new FileReader();
    reader.onloadend = () => {
      const w = window.open('about:blank');
      if (w && w.document) {
        const safe = String(reader.result || '').replace(
          /[&<>]/g,
          (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[ch]!)
        );
        w.document.write(`<pre>${safe}</pre>`);
      } else {
        alert('Popup blocked. Please allow popups for this page.');
      }
    };
    reader.readAsText(blob);
  }

  onImportFileChange(evt: Event) {
    const input = evt.target as HTMLInputElement;
    const file = (input.files && input.files[0]) || null;
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const json = JSON.parse(String(reader.result || ''));
        this.importJsonObject(json);
      } catch (err) {
        console.error(err);
        window.alert('Failed to import JSON. Check file format.');
      } finally {
        input.value = '';
      }
    };
    reader.readAsText(file);
  }

  importJsonObject(obj: any) {
    if (!obj || typeof obj !== 'object') throw new Error('Invalid JSON.');
    if (
      !(
        'subject' in obj &&
        'bodydraft' in obj &&
        Array.isArray(obj.companyData)
      )
    ) {
      throw new Error(
        'JSON must contain subject, bodydraft, and companyData[].'
      );
    }

    if ('username' in obj) this.request.username = obj.username || '';
    if ('password' in obj) this.request.password = obj.password || '';

    this.request.subject = obj.subject || '';
    this.request.bodydraft = obj.bodydraft || '';

    const used: Record<number, true> = {};
    let maxId = -1;
    this.request.companyData = [];
    for (const c of obj.companyData as any[]) {
      let idNum = parseInt(String(c.id), 10);
      if (!isFinite(idNum)) idNum = maxId + 1;
      if (used[idNum]) idNum = maxId + 1;
      used[idNum] = true;
      if (idNum > maxId) maxId = idNum;

      this.request.companyData.push({
        id: idNum,
        companyMail: c.companyMail || '',
        parameters:
          c.parameters && typeof c.parameters === 'object' ? c.parameters : {},
      });
    }
    this.nextCompanyId = maxId + 1;
  }

  clearDatas() {
    const ok = window.confirm('Tüm verileri temizlemek istediğinize emin misiniz?');
    if (!ok) {
      return;
    }
    const keepUsername = this.request.username;
    const keepPassword = this.request.password;
    this.request.subject = '';
    this.request.bodydraft = '';
    this.request.companyData = [
      { id: 0, companyMail: '', parameters: { companyName: '', companyNumber: '' } },
    ];
    this.nextCompanyId = 1;
    this.files = [];
    this.progress = [];
    this.responseMessage = '';
    this.isSuccess = 0;
    this.etaMinSeconds = 0;
    this.etaMaxSeconds = 0;
    this.avgSendMs = 0;
    this.avgCooldownMs = 0;
    this.samples = 0;
    this.cfgMinMs = 0;
    this.cfgMaxMs = 0;
    if (this.currentEventSource) {
      this.currentEventSource.close();
      this.currentEventSource = null;
    }
    this.request.username = keepUsername;
    this.request.password = keepPassword;
  }

  sendMails() {
    if (this.currentEventSource) {
      this.currentEventSource.close();
      this.currentEventSource = null;
    }
    this.responseMessage = 'Sending mails...';
    this.isSuccess = 0;
    this.progress = [];
    // ETA will be set on 'started' event using backend-configured cooldowns
    const count = this.request.companyData.length;
    this.etaMinSeconds = 0;
    this.etaMaxSeconds = 0;

    this.mailService.startMailJob(this.request, this.files).subscribe({
      next: (res) => {
        const jobId = String(res.data);
        const es = this.mailService.openJobEventSource(jobId);
        this.currentEventSource = es;

        es.addEventListener('started', (evt: MessageEvent) => {
          this.responseMessage = 'Job started...';
          try {
            const cfg = JSON.parse(evt.data || '{}');
            const minMs = Number(cfg.minMs) || 0;
            const maxMs = Number(cfg.maxMs) || 0;
            const total = this.request.companyData.length;
            this.cfgMinMs = minMs;
            this.cfgMaxMs = maxMs;
            this.etaMinSeconds = Math.round((total * minMs) / 1000);
            this.etaMaxSeconds = Math.round((total * maxMs) / 1000);
          } catch {}
        });

        es.addEventListener('progress', (evt: MessageEvent) => {
          try {
            const data = JSON.parse(evt.data);
            this.progress = [
              ...this.progress,
              {
                index: data.index,
                companyMail: data.companyMail,
                status: data.status,
                message: data.message,
              },
            ];
            // update running averages for dynamic ETA
            const sMs = Number(data.sendMs) || 0;
            const cMs = Number(data.cooldownMs) || 0;
            this.samples += 1;
            this.avgSendMs = this.avgSendMs + (sMs - this.avgSendMs) / this.samples;
            this.avgCooldownMs = this.avgCooldownMs + (cMs - this.avgCooldownMs) / this.samples;
            const remaining = Math.max(0, this.request.companyData.length - this.progress.length);
            const minPerItem = this.avgSendMs + (this.cfgMinMs || 0);
            const maxPerItem = this.avgSendMs + (this.cfgMaxMs || 0);
            this.etaMinSeconds = Math.round((remaining * minPerItem) / 1000);
            this.etaMaxSeconds = Math.round((remaining * maxPerItem) / 1000);
          } catch {}
        });

        es.addEventListener('finished', () => {
          this.responseMessage = 'Mails sent successfully';
          this.isSuccess = 1;
          es.close();
          this.currentEventSource = null;
        });

        es.addEventListener('error', (evt: MessageEvent) => {
          this.responseMessage = (evt && (evt as any).data) || 'An error occurred while sending emails.';
          this.isSuccess = -1;
          es.close();
          this.currentEventSource = null;
        });
      },
      error: (err) => {
        this.responseMessage =
          (err?.error && err.error.message) || 'Failed to start job.';
        this.isSuccess = -1;
      },
    });
  }

  checkServer() {
    this.mailService.checkServer().subscribe({
      next: (res) => (this.serverStatus = (res as any).message),
      error: () => (this.serverStatus = 'Server Down'),
    });
  }
}
