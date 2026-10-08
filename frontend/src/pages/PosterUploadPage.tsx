import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { JobProgressBar } from '../components/JobProgressBar';
import { api, JobStatus, ProjectDetail } from '../lib/api';

/**
 * U-1 포스터 업로드(docs/03·04) — "가지고 있는 포스터로 시작" 경로의 첫 화면이자, 대시보드의 "다른 포스터 올리기".
 * 올리면 대표 포스터로 등록되고 포스터 분석(요소 분리, 1회)이 돈다. 분석이 끝나면 ⑤ 규격 선택으로 이어간다.
 */
export default function PosterUploadPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [title, setTitle] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);

  const { data: project } = useQuery({
    queryKey: ['project', id],
    queryFn: () => api.get<ProjectDetail>(`/projects/${id}`),
    enabled: !!id,
  });
  const replacing = project?.status === 'active';

  useEffect(() => {
    if (project?.main_title && !title) setTitle(project.main_title);
  }, [project?.main_title]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!file) { setPreview(null); return; }
    const url = URL.createObjectURL(file);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  const { data: job } = useQuery({
    queryKey: ['job', jobId],
    queryFn: () => api.get<JobStatus>(`/jobs/${jobId}`),
    enabled: !!jobId,
    refetchInterval: (q) => (q.state.data && ['succeeded', 'failed', 'canceled'].includes(q.state.data.status) ? false : 3000),
  });

  async function submit() {
    if (!file || !title.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      const form = new FormData();
      form.append('poster', file);
      form.append('main_title', title.trim());
      const csrfToken = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)?.[1];
      const res = await fetch(`/api/v1/projects/${id}/poster`, {
        method: 'POST',
        credentials: 'include',
        headers: csrfToken ? { 'X-XSRF-TOKEN': decodeURIComponent(csrfToken) } : undefined,
        body: form,
      });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error(body?.error?.message ?? '업로드에 실패했습니다.');
      setJobId(body.job_id);
      queryClient.invalidateQueries({ queryKey: ['project', id] });
      queryClient.invalidateQueries({ queryKey: ['auth', 'me'] });
    } catch (err) {
      setError(err instanceof Error ? err.message : '업로드에 실패했습니다.');
    } finally {
      setSubmitting(false);
    }
  }

  const analyzing = !!jobId && (!job || job.status === 'pending' || job.status === 'running');

  return (
    <div>
      <Header />
      <div className="page" style={{ maxWidth: 640 }}>
        <h1 className="h1" style={{ marginBottom: 'var(--sp-2)' }}>{replacing ? '다른 포스터 올리기' : '포스터 올리기'}</h1>
        <p className="body-sm" style={{ marginBottom: 'var(--sp-6)' }}>
          {replacing
            ? '새 포스터로 바꾸면 이미 만든 규격 결과물에 "원본 변경됨"이 표시돼요.'
            : '가지고 계신 포스터를 올리면 요소를 나눠 여러 규격으로 바꿀 수 있게 준비해 드려요.'}
        </p>

        {!jobId && (
          <div className="card" style={{ padding: 'var(--sp-5)' }}>
            <label className="field-label">공연명</label>
            <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="예: 반짝반짝 빛나는 마술사의 방"
                   style={{ marginBottom: 'var(--sp-4)' }} />
            <label className="field-label">포스터 (JPG·PNG, 20MB 이하)</label>
            <input type="file" accept="image/jpeg,image/png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            <p className="caption" style={{ color: 'var(--warning)', marginTop: 'var(--sp-2)' }}>
              요소 분리와 배경 정리를 위해 포스터가 외부 AI로 전송됩니다. 포스터 안에 들어 있는 인물 사진·로고도 함께 전송됩니다.
            </p>
            {preview && (
              <img src={preview} alt="올릴 포스터" style={{ marginTop: 'var(--sp-4)', maxHeight: 360, borderRadius: 'var(--r-md)' }} />
            )}
            {error && <p className="body-sm" style={{ marginTop: 'var(--sp-3)', color: 'var(--error)' }}>{error}</p>}
            <div style={{ marginTop: 'var(--sp-5)' }}>
              <button className="btn btn-primary" disabled={!file || !title.trim() || submitting} onClick={submit}>
                {submitting ? '올리는 중…' : '포스터 올리기'}
              </button>
            </div>
          </div>
        )}

        {jobId && (
          <div className="card" style={{ padding: 'var(--sp-5)' }}>
            {preview && (
              <img src={preview} alt="올린 포스터" style={{ maxHeight: 320, borderRadius: 'var(--r-md)', marginBottom: 'var(--sp-4)' }} />
            )}
            {(analyzing || job?.status === 'failed') && (
              <div style={{ marginBottom: 'var(--sp-3)' }}>
                <JobProgressBar status={job?.status} stage={job?.stage} waitingLabel="분석 순서를 기다리는 중" />
              </div>
            )}
            {analyzing && (
              <p className="caption" style={{ color: 'var(--gray-warm)' }}>보통 2~3분 걸려요. 이 화면을 떠나도 계속 진행돼요.</p>
            )}
            {job?.status === 'failed' && (
              <>
                <p className="body-sm" style={{ color: 'var(--error)', marginBottom: 'var(--sp-3)' }}>
                  포스터 분석에 실패했어요. 다른 파일로 다시 시도해 주세요.
                </p>
                <button className="btn btn-secondary" onClick={() => { setJobId(null); setFile(null); }}>다시 올리기</button>
              </>
            )}
            {job?.status === 'succeeded' && (
              <>
                <p className="body-strong" style={{ marginBottom: 'var(--sp-4)' }}>분석이 끝났어요. 필요한 규격을 골라 주세요.</p>
                <div style={{ display: 'flex', gap: 'var(--sp-3)' }}>
                  <button className="btn btn-primary" onClick={() => navigate(`/projects/${id}/formats`)}>규격 고르기</button>
                  <button className="btn btn-secondary" onClick={() => navigate(`/projects/${id}/dashboard`)}>대시보드로 가기</button>
                </div>
              </>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
