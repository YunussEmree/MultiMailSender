import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MailSenderService } from './mail-sender';

describe('MailSenderService', () => {
  let service: MailSenderService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(MailSenderService);
    http = TestBed.inject(HttpTestingController);
  });

  it('builds the report url', () => {
    expect(service.exportUrl('abc')).toBe('/api/jobs/abc/export.xlsx');
  });

  it('posts the job as multipart form data', () => {
    service
      .startMailJob(
        { username: 'a@b.c', password: 'x', fromName: '', subject: 's', bodydraft: 'b', html: false, skipAlreadySent: true, companyData: [] },
        [],
      )
      .subscribe();
    const req = http.expectOne('/api/send-mails-with-attachment/start');
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBeTrue();
    req.flush({ message: 'Job started', data: 'id' });
  });
});
