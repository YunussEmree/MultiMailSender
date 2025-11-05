import { Injectable } from '@angular/core';
import { HttpClient }   from '@angular/common/http';
import { Observable }   from 'rxjs';

interface ApiResponse {
  message: string;
  data: any;
}

@Injectable({
  providedIn: 'root'
})
export class MailSenderService {
  private readonly baseUrl = 'http://localhost:8080';

  constructor(private http: HttpClient) {}

  sendMailsWithAttachment(request: any, files: File[]): Observable<ApiResponse> {
    const form = new FormData();
    form.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }));
    files.forEach(f => form.append('files', f, f.name));
    return this.http.post<ApiResponse>(`${this.baseUrl}/send-mails-with-attachment`, form);
  }

  sendTestMail(request: any, files: File[]): Observable<ApiResponse> {
    const form = new FormData();
    form.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }));
    files.forEach(f => form.append('files', f, f.name));
    return this.http.post<ApiResponse>(`${this.baseUrl}/test-mail`, form);
  }

  checkServer(): Observable<ApiResponse> {
    return this.http.get<ApiResponse>(`${this.baseUrl}/health`);
  }

  startMailJob(request: any, files: File[]): Observable<ApiResponse> {
    const form = new FormData();
    form.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }));
    files.forEach(f => form.append('files', f, f.name));
    return this.http.post<ApiResponse>(`${this.baseUrl}/send-mails-with-attachment/start`, form);
  }

  openJobEventSource(jobId: string): EventSource {
    return new EventSource(`${this.baseUrl}/send-mails-with-attachment/stream/${jobId}`);
  }
}
