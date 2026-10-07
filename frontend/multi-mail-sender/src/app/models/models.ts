export interface CompanyData {
  id: number;
  companyMail: string;
  parameters: Record<string, string>;
}

export interface RequestData {
  username: string;
  password: string;
  fromName: string;
  subject: string;
  bodydraft: string;
  html: boolean;
  skipAlreadySent: boolean;
  companyData: CompanyData[];
}

export type RowStatus = 'pending' | 'sent' | 'error' | 'skipped';

export interface ProgressRow {
  index: number;
  companyMail: string;
  companyName?: string;
  status: RowStatus;
  message?: string;
  sendMs?: number | null;
}

export type JobState = 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED' | 'INTERRUPTED';

export interface JobSummary {
  id: string;
  sender: string;
  subject: string;
  createdAt: string;
  finishedAt?: string | null;
  status: JobState;
  error?: string | null;
  total: number;
  sent: number;
  failed: number;
  skipped: number;
}

export interface ApiResponse<T = any> {
  message: string;
  data: T;
}
