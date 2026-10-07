import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiResponse, JobSummary, RequestData } from '../models/models';

@Injectable({ providedIn: 'root' })
export class MailSenderService {
  /** Proxied to the backend by `ng serve` (proxy.conf.json) and by nginx in Docker. */
  readonly baseUrl = '/api';

  constructor(private http: HttpClient) {}

  checkServer(): Observable<ApiResponse> {
    return this.http.get<ApiResponse>(`${this.baseUrl}/health`);
  }

  startMailJob(request: RequestData, files: File[]): Observable<ApiResponse<string>> {
    const form = new FormData();
    form.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }));
    files.forEach((f) => form.append('files', f, f.name));
    return this.http.post<ApiResponse<string>>(`${this.baseUrl}/send-mails-with-attachment/start`, form);
  }

  openJobEventSource(jobId: string): EventSource {
    return new EventSource(`${this.baseUrl}/send-mails-with-attachment/stream/${jobId}`);
  }

  cancelJob(jobId: string): Observable<ApiResponse> {
    return this.http.post<ApiResponse>(`${this.baseUrl}/jobs/${jobId}/cancel`, {});
  }

  listJobs(): Observable<JobSummary[]> {
    return this.http.get<JobSummary[]>(`${this.baseUrl}/jobs`);
  }

  exportUrl(jobId: string): string {
    return `${this.baseUrl}/jobs/${jobId}/export.xlsx`;
  }
}
