// docs/11 공통 규약: /api/v1, 세션 쿠키 인증, 오류는 { error: { code, message, details } }

export class ApiError extends Error {
  code: string;
  status: number;
  details?: Record<string, unknown>;

  constructor(status: number, code: string, message: string, details?: Record<string, unknown>) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

const BASE = '/api/v1';

/** 1-22: 세션 쿠키 인증에 CSRF 토큰을 함께 보낸다(Spring Security 쿠키-헤더 SPA 패턴). */
function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return match ? decodeURIComponent(match[1]) : null;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = init?.body ? { 'Content-Type': 'application/json' } : {};
  const method = (init?.method ?? 'GET').toUpperCase();
  if (method !== 'GET' && method !== 'HEAD') {
    const token = readCookie('XSRF-TOKEN');
    if (token) headers['X-XSRF-TOKEN'] = token;
  }

  const res = await fetch(BASE + path, {
    credentials: 'include',
    headers,
    ...init,
  });

  if (res.status === 204) {
    return undefined as T;
  }

  const isJson = res.headers.get('content-type')?.includes('application/json');
  const body = isJson ? await res.json().catch(() => null) : null;

  if (!res.ok) {
    const err = body?.error;
    throw new ApiError(res.status, err?.code ?? 'UNKNOWN', err?.message ?? res.statusText, err?.details);
  }
  return body as T;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, data?: unknown) =>
    request<T>(path, { method: 'POST', body: data !== undefined ? JSON.stringify(data) : undefined }),
  patch: <T>(path: string, data?: unknown) =>
    request<T>(path, { method: 'PATCH', body: data !== undefined ? JSON.stringify(data) : undefined }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
};

// ---- 타입 (docs/11 응답 형태를 최소 필드만 옮김) ----

export interface Account {
  id: string;
  email: string;
  role: string;
  credit_balance: number;
}

export interface ProjectSummary {
  id: string;
  main_title: string;
  genre: string | null;
  primary_date: string | null;
  date_undetermined: boolean;
  thumbnail_url: string | null;
  updated_at: string;
}

export interface ProjectDetail {
  id: string;
  status: 'draft' | 'active' | 'deleted';
  main_title: string;
  genre: string | null;
  performance_info: Record<string, unknown>;
  design_assets: Record<string, unknown> | null;
  flags: { date_undetermined: boolean; venue_undetermined: boolean };
}

export interface JobStatus {
  id: string;
  kind: string;
  status: 'pending' | 'running' | 'succeeded' | 'failed' | 'canceled';
  error: string | null;
  result: { asset_ids?: string[] } | null;
}

export interface AssetItem {
  id: string;
  category: string;
  format_code: string;
  width: number;
  height: number;
  variant_index: number | null;
  preview_image_url: string | null;
  image_url: string | null;
  downloadable: boolean;
  status: string;
  stale: { info: boolean; design: boolean };
}

export interface FormatPresetDto {
  code: string;
  label: string;
  width: number;
  height: number;
  group: string;
  ratio_bucket: string;
}
