import { useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, AssetItem } from '../lib/api';

interface ChildJob { job_id: string; status: string; error: string | null; format_code: string | null }
interface JobStatusDetail { status: string; progress?: { done: number; total: number }; children?: ChildJob[] }

/** ⑥ 일괄변환 결과 화면(3-5). 규격별 탭 + 3안 카드, 선택 시 확정된다(docs/04). */
export default function Step6RecomposeResultsPage() {
  const { id } = useParams<{ id: string }>();
  const [params] = useSearchParams();
  const jobId = params.get('job');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [activeFormat, setActiveFormat] = useState<string | null>(null);

  const { data: job } = useQuery({
    queryKey: ['job', jobId],
    queryFn: () => api.get<JobStatusDetail>(`/jobs/${jobId}`),
    enabled: !!jobId,
    refetchInterval: (query) => {
      const d = query.state.data;
      if (!d || !d.progress) return 2500;
      return d.progress.done < d.progress.total ? 2500 : false;
    },
  });

  const { data: assetData } = useQuery({
    queryKey: ['assets', id, '규격변환'],
    queryFn: () => api.get<{ items: AssetItem[] }>(`/projects/${id}/assets?category=규격변환`),
    enabled: !!id,
    refetchInterval: job?.progress && job.progress.done < job.progress.total ? 2500 : false,
  });

  const select = useMutation({
    mutationFn: (assetId: string) => api.post(`/assets/${assetId}/select`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['assets', id, '규격변환'] }),
  });

  const children = job?.children ?? [];
  const formatCodes = Array.from(new Set(children.map((c) => c.format_code).filter(Boolean))) as string[];

  useEffect(() => {
    if (!activeFormat && formatCodes.length > 0) setActiveFormat(formatCodes[0]);
  }, [formatCodes, activeFormat]);

  const assetsForActive = (assetData?.items ?? []).filter((a) => a.format_code === activeFormat && a.status !== '보관');
  const confirmedCount = formatCodes.filter((code) =>
    (assetData?.items ?? []).some((a) => a.format_code === code && a.status === '선택됨')).length;

  return (
    <div>
      <Header />
      <div className="page">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--sp-5)' }}>
          <h1 className="h1">규격별 결과물</h1>
          <span className="body-sm tabular">{confirmedCount}/{formatCodes.length} 규격 확정</span>
        </div>

        <div style={{ display: 'flex', gap: 'var(--sp-2)', marginBottom: 'var(--sp-5)', flexWrap: 'wrap' }}>
          {formatCodes.map((code) => {
            const child = children.find((c) => c.format_code === code);
            const done = (assetData?.items ?? []).some((a) => a.format_code === code && a.status === '선택됨');
            return (
              <button key={code} className={code === activeFormat ? 'btn btn-primary btn-sm' : 'btn btn-secondary btn-sm'}
                      onClick={() => setActiveFormat(code)}>
                {code} {child?.status === 'failed' ? '⚠' : done ? '✓' : ''}
              </button>
            );
          })}
        </div>

        {children.find((c) => c.format_code === activeFormat)?.status === 'failed' && (
          <p className="body-sm" style={{ color: 'var(--error)', marginBottom: 'var(--sp-4)' }}>
            이 규격은 생성에 실패했습니다. 크레딧은 자동 환불되었습니다.
          </p>
        )}

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 'var(--sp-4)' }}>
          {assetsForActive.map((a) => (
            <div key={a.id} className="card" style={{ overflow: 'hidden', position: 'relative' }}>
              {a.status === '선택됨' && (
                <div style={{ position: 'absolute', top: 8, right: 8, zIndex: 1 }} className="badge badge-success">선택됨</div>
              )}
              <img src={a.preview_image_url ?? undefined} alt={a.format_code} style={{ width: '100%', display: 'block' }} />
              <div style={{ padding: 'var(--sp-3)' }}>
                <button className="btn btn-primary btn-sm" style={{ width: '100%' }}
                        disabled={a.status === '선택됨'} onClick={() => select.mutate(a.id)}>
                  {a.status === '선택됨' ? '선택됨' : '이 안으로 확정'}
                </button>
              </div>
            </div>
          ))}
          {assetsForActive.length === 0 && <p className="body-sm">생성 중이거나 아직 결과가 없어요…</p>}
        </div>

        <div style={{ marginTop: 'var(--sp-8)' }}>
          <button className="btn btn-secondary" onClick={() => navigate(`/projects/${id}/dashboard`)}>대시보드로 가기</button>
        </div>
      </div>
    </div>
  );
}
