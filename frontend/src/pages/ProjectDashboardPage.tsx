import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, AssetItem, ProjectDetail } from '../lib/api';

interface ProjectDetailWithCounts extends ProjectDetail {
  flags: ProjectDetail['flags'] & { stale_info_count?: number; stale_design_count?: number };
}

const JOB_KIND_LABELS: Record<string, string> = {
  draft_generate: '시안 생성',
  decompose_layers: '레이어 분해',
  recompose: '규격 일괄변환',
  resync: '포스터 정보 반영',
  zip_download: '압축 다운로드 준비',
  render_print: '인쇄용 렌더링',
};

/** ⑦ 프로젝트 대시보드(4-1). 대표 포스터 + 규격변환 그리드, 선택/일괄 다운로드(4-2), 개별 삭제(4-6). */
export default function ProjectDashboardPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [downloading, setDownloading] = useState(false);

  const { data: project } = useQuery({
    queryKey: ['project', id],
    queryFn: () => api.get<ProjectDetailWithCounts>(`/projects/${id}`),
    enabled: !!id,
  });

  const { data: posterData } = useQuery({
    queryKey: ['assets', id, '포스터'],
    queryFn: () => api.get<{ items: AssetItem[] }>(`/projects/${id}/assets?category=포스터`),
    enabled: !!id,
  });

  const { data: gridData } = useQuery({
    queryKey: ['assets', id, '규격변환'],
    queryFn: () => api.get<{ items: AssetItem[] }>(`/projects/${id}/assets?category=규격변환`),
    enabled: !!id,
  });

  const { data: activeJobsData } = useQuery({
    queryKey: ['projectJobs', id],
    queryFn: () => api.get<{ items: { id: string; kind: string; status: string }[] }>(`/projects/${id}/jobs`),
    enabled: !!id,
    refetchInterval: 4000,
  });
  const activeJobs = activeJobsData?.items ?? [];

  const deleteAsset = useMutation({
    mutationFn: (assetId: string) => api.del(`/assets/${assetId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['assets', id, '규격변환'] }),
  });

  const poster = posterData?.items[0];
  const gridItems = (gridData?.items ?? []).filter((a) => a.status !== '삭제됨');

  function toggle(assetId: string) {
    const next = new Set(selected);
    next.has(assetId) ? next.delete(assetId) : next.add(assetId);
    setSelected(next);
  }

  async function downloadSelected(ids: string[]) {
    if (ids.length === 0) return;
    setDownloading(true);
    try {
      const { job_id } = await api.post<{ job_id: string }>(`/projects/${id}/assets/download`, { asset_ids: ids });
      for (let i = 0; i < 30; i++) {
        await new Promise((r) => setTimeout(r, 2000));
        const job = await api.get<{ status: string; result: { zip_url?: string } | null }>(`/jobs/${job_id}`);
        if (job.status === 'succeeded' && job.result?.zip_url) {
          window.open(job.result.zip_url, '_blank');
          return;
        }
        if (job.status === 'failed') break;
      }
    } finally {
      setDownloading(false);
    }
  }

  const staleInfo = (project?.flags.stale_info_count ?? 0) > 0;
  const staleDesign = (project?.flags.stale_design_count ?? 0) > 0;

  return (
    <div>
      <Header />
      <div className="page">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 'var(--sp-6)' }}>
          <div>
            <h1 className="h1">{project?.main_title || '(제목 없음)'}</h1>
            {(project?.flags.date_undetermined || project?.flags.venue_undetermined) && (
              <span className="badge badge-neutral" style={{ marginTop: 'var(--sp-2)' }}>일정/장소 미정</span>
            )}
          </div>
          <div style={{ display: 'flex', gap: 'var(--sp-2)' }}>
            <Link to={`/projects/${id}/print`} className="btn btn-secondary">인쇄</Link>
            <Link to={`/projects/${id}/edit`} className="btn btn-secondary">정보 수정</Link>
          </div>
        </div>

        {activeJobs.length > 0 && (
          <div className="card" style={{ padding: 'var(--sp-4)', marginBottom: 'var(--sp-5)', background: 'var(--bg-hover)' }}>
            <p className="body-sm">
              진행 중인 작업이 있어요: {activeJobs.map((j) => JOB_KIND_LABELS[j.kind] ?? j.kind).join(', ')}
            </p>
          </div>
        )}

        <div style={{ display: 'grid', gridTemplateColumns: '320px 1fr', gap: 'var(--sp-8)' }}>
          <div>
            <p className="body-sm" style={{ marginBottom: 'var(--sp-2)' }}>대표 포스터</p>
            {poster?.preview_image_url ? (
              <img src={poster.preview_image_url} alt="대표 포스터" style={{ width: '100%', borderRadius: 'var(--r-xl)', boxShadow: 'var(--shadow-lg)' }} />
            ) : (
              <div style={{ aspectRatio: '3/4', background: 'var(--bg-hover)', borderRadius: 'var(--r-xl)' }} />
            )}
            {staleDesign && <span className="badge badge-info" style={{ marginTop: 'var(--sp-3)' }}>원본 변경됨</span>}
            {!staleDesign && staleInfo && <span className="badge badge-info" style={{ marginTop: 'var(--sp-3)' }}>정보 변경됨</span>}
            <div style={{ marginTop: 'var(--sp-4)', display: 'flex', gap: 'var(--sp-2)' }}>
              {poster?.image_url && (
                <a href={poster.image_url} target="_blank" rel="noreferrer" className="btn btn-secondary btn-sm">다운로드</a>
              )}
              <button className="btn btn-tertiary btn-sm" onClick={() => navigate(`/projects/${id}/drafts`)}>다시 만들기</button>
            </div>
          </div>

          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--sp-4)' }}>
              <h2 className="h2">규격별 결과물</h2>
              <div style={{ display: 'flex', gap: 'var(--sp-2)' }}>
                <button className="btn btn-secondary btn-sm" onClick={() => navigate(`/projects/${id}/formats`)}>+ 규격 추가</button>
                <button className="btn btn-primary btn-sm" disabled={selected.size === 0 || downloading}
                        onClick={() => downloadSelected(Array.from(selected))}>
                  선택 다운로드 ({selected.size})
                </button>
              </div>
            </div>

            {gridItems.length === 0 && <p className="body-sm">아직 만든 규격변환 결과물이 없어요.</p>}

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 'var(--sp-4)' }}>
              {gridItems.map((a) => (
                <div key={a.id} className="card" style={{ overflow: 'hidden', position: 'relative' }}>
                  <input type="checkbox" checked={selected.has(a.id)} onChange={() => toggle(a.id)}
                         style={{ position: 'absolute', top: 8, left: 8, zIndex: 1 }} />
                  <img src={a.preview_image_url ?? undefined} alt={a.format_code} style={{ width: '100%', display: 'block' }} />
                  <div style={{ padding: 'var(--sp-2)' }}>
                    <p className="body-sm">{a.format_code}</p>
                    <p className="caption tabular">{a.width} × {a.height}</p>
                    <div style={{ display: 'flex', gap: 'var(--sp-1)', marginTop: 'var(--sp-1)' }}>
                      {a.stale.info && <span className="badge badge-info">정보 변경됨</span>}
                      {!a.downloadable && <span className="badge badge-neutral">다운로드 만료</span>}
                    </div>
                    <button className="btn btn-destructive btn-sm" style={{ width: '100%', marginTop: 'var(--sp-2)' }}
                            onClick={() => deleteAsset.mutate(a.id)}>삭제</button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
