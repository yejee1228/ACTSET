import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, AssetItem, JobStatus } from '../lib/api';

interface FormatClassDto { code: string; label: string; width: number; height: number; example: string }

/**
 * 포스터 업로드형 규격변환(① 입력 → 결과). 고객이 가진 포스터 한 장과 필요한 규격을 받아
 * 요소 분리 → 배경 검증·재생성 → 규격별 재배치까지 한 번에 돌린다(FORMAT-CONVERSION-REPORT.md).
 */
export default function ConvertPage() {
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [started, setStarted] = useState<{ projectId: string; jobId: string } | null>(null);

  const { data: classes } = useQuery({
    queryKey: ['format-classes'],
    queryFn: () => api.get<{ items: FormatClassDto[]; cost_per_format: number }>('/format-classes'),
  });

  useEffect(() => {
    if (!file) { setPreview(null); return; }
    const url = URL.createObjectURL(file);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  const { data: job } = useQuery({
    queryKey: ['job', started?.jobId],
    queryFn: () => api.get<JobStatus>(`/jobs/${started!.jobId}`),
    enabled: !!started,
    refetchInterval: (q) => (q.state.data && ['succeeded', 'failed', 'canceled'].includes(q.state.data.status) ? false : 3000),
  });

  const { data: assets } = useQuery({
    queryKey: ['assets', started?.projectId, '규격변환'],
    queryFn: () => api.get<{ items: AssetItem[] }>(`/projects/${started!.projectId}/assets?category=규격변환`),
    enabled: !!started && job?.status === 'succeeded',
  });

  function toggle(code: string) {
    const next = new Set(selected);
    next.has(code) ? next.delete(code) : next.add(code);
    setSelected(next);
  }

  async function submit() {
    if (!file) return;
    setSubmitting(true);
    setError(null);
    try {
      const form = new FormData();
      form.append('poster', file);
      selected.forEach((c) => form.append('format_classes', c));
      const csrfToken = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)?.[1];
      const res = await fetch('/api/v1/conversions', {
        method: 'POST',
        credentials: 'include',
        headers: csrfToken ? { 'X-XSRF-TOKEN': decodeURIComponent(csrfToken) } : undefined,
        body: form,
      });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error(body?.error?.message ?? '요청에 실패했습니다.');
      setStarted({ projectId: body.project_id, jobId: body.job_id });
      queryClient.invalidateQueries({ queryKey: ['auth', 'me'] });
    } catch (err) {
      setError(err instanceof Error ? err.message : '요청에 실패했습니다.');
    } finally {
      setSubmitting(false);
    }
  }

  const cost = (classes?.cost_per_format ?? 0) * selected.size;
  const running = !!started && (!job || job.status === 'pending' || job.status === 'running');

  return (
    <div>
      <Header />
      <div className="page">
        <h1 className="h1" style={{ marginBottom: 'var(--sp-2)' }}>포스터를 여러 규격으로 바꾸기</h1>
        <p className="body-sm" style={{ marginBottom: 'var(--sp-6)' }}>
          가지고 계신 포스터 한 장을 올리고 필요한 규격을 고르세요. 요소를 나눠 규격마다 다시 배치합니다.
        </p>

        {!started && (
          <>
            <div className="card" style={{ padding: 'var(--sp-5)', marginBottom: 'var(--sp-6)' }}>
              <label className="field-label">포스터 (JPG·PNG, 20MB 이하)</label>
              <input type="file" accept="image/jpeg,image/png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
              <p className="caption" style={{ color: 'var(--warning)', marginTop: 'var(--sp-2)' }}>
                요소 분리와 배경 정리를 위해 포스터가 외부 AI로 전송됩니다. 포스터 안에 들어 있는 인물 사진·로고도 함께 전송됩니다.
              </p>
              {preview && (
                <img src={preview} alt="올린 포스터" style={{ marginTop: 'var(--sp-4)', maxHeight: 320, borderRadius: 'var(--r-md)' }} />
              )}
            </div>

            <h2 className="h2" style={{ marginBottom: 'var(--sp-3)' }}>필요한 규격</h2>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 'var(--sp-3)', marginBottom: 'var(--sp-6)' }}>
              {(classes?.items ?? []).map((c) => (
                <label key={c.code} className="card" style={{
                  padding: 'var(--sp-4)', cursor: 'pointer',
                  borderColor: selected.has(c.code) ? 'var(--orange)' : 'var(--border)',
                  borderWidth: selected.has(c.code) ? 2 : 1,
                }}>
                  <input type="checkbox" checked={selected.has(c.code)} onChange={() => toggle(c.code)} style={{ marginRight: 8 }} />
                  <span className="body-strong">{c.label}</span>
                  <p className="caption tabular">{c.width} × {c.height}px · 예: {c.example}</p>
                </label>
              ))}
            </div>

            {selected.size > 0 && (
              <p className="body-sm" style={{ marginBottom: 'var(--sp-3)' }}>예상 소비 크레딧 {cost.toLocaleString()}</p>
            )}
            {error && <p className="body-sm" style={{ marginBottom: 'var(--sp-3)', color: 'var(--error)' }}>{error}</p>}
            <button className="btn btn-primary" disabled={!file || selected.size === 0 || submitting} onClick={submit}>
              {submitting ? '올리는 중…' : `변환 시작 (${selected.size}종)`}
            </button>
          </>
        )}

        {started && (
          <div>
            {running && (
              <p className="body-sm">요소를 나누고 규격별로 배치하는 중이에요. 보통 2~4분 걸립니다.</p>
            )}
            {job?.status === 'failed' && (
              <p className="body-sm" style={{ color: 'var(--error)' }}>
                변환에 실패했습니다. 사용한 크레딧은 돌려드렸어요. ({job.error})
              </p>
            )}
            {job?.status === 'succeeded' && (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 'var(--sp-4)' }}>
                {(assets?.items ?? []).map((a) => (
                  <div key={a.id} className="card" style={{ padding: 'var(--sp-3)' }}>
                    {a.preview_image_url && (
                      <img src={a.preview_image_url} alt={a.format_code} style={{ width: '100%', borderRadius: 'var(--r-md)' }} />
                    )}
                    <p className="caption tabular" style={{ marginTop: 'var(--sp-2)' }}>{a.format_code} · {a.width} × {a.height}px</p>
                    {a.image_url && <a className="btn btn-secondary" href={a.image_url} download>다운로드</a>}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
