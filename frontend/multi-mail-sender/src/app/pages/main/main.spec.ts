import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MainComponent } from './main';

describe('MainComponent', () => {
  let component: MainComponent;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [MainComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    component = TestBed.createComponent(MainComponent).componentInstance;
  });

  it('imports companies from json and detects incomplete rows', () => {
    component.request.bodydraft = 'Hi {companyName}';
    component.importJson({
      companyData: [
        { companyMail: 'a@x.com', parameters: { companyName: 'A' } },
        { companyMail: 'b@x.com', parameters: { companyName: '' } },
      ],
    });
    expect(component.request.companyData.length).toBe(2);
    expect(component.incompleteCount).toBe(1);
  });

  it('removes duplicate and blank addresses', () => {
    component.importJson({
      companyData: [{ companyMail: 'a@x.com' }, { companyMail: 'A@x.com' }, { companyMail: '' }],
    });
    component.removeDuplicates();
    expect(component.request.companyData.length).toBe(1);
  });

  it('cannot send without credentials', () => {
    expect(component.canSend).toBeFalse();
  });
});
