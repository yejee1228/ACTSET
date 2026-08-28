import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, AssetItem, JobStatus } from '../lib/api';

/** ③ 시안 선택 화면(1-12·1-13). 시안 3장이 화면의 대부분을 차지한다(docs/16). */
export default function Step3DraftSelectionPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [jobStatus, setJobStatus] = useState<'idle' | 'running' | 'done' | 'failed'>('idle');
  const startedRef = useRef(false);

  const { data: assetData, refetch: refetchAssets } = useQuery({
    queryKey: ['assets', id, '시안후보'],
    queryFn: () => api.get<{ items: AssetItem[] }>(`/projects/${id}/assets?category=시안후보`),
    enabled: !!id,
  });

  const candidates = (assetData?.items ?? []).filter((a) => a.status !== '보관').sort((a, b) => (a.variant_index ?? 0) - (b.variant_index ?? 0));

  async function pollJob(jobId: string) {
    setJobStatus('running');
    for (let i = 0; i < 60; i++) {
      await new Promise((r) => setTimeout(r, 2500));
      const job = await api.get<JobStatus>(`/jobs/${jobId}`);
      if (job.status === 'succeeded') {
        setJobStatus('done');
        await refetchAssets();
        return;
      }
      if (job.status === 'failed') {
        setJobStatus('failed');
        return;
      }
    }
    setJobStatus('failed');
  }

  async function generate(mode: 'initial' | 'regenerate' | 'new_direction' | 'more_like', referenceId?: string) {
    // 액션 발생 시점에 화면에 표시 중이던 후보 전체를 shown_candidates로 함께 기록한다(docs/02 핵심).
    if (mode !== 'initial' && id) {
      await api.post(`/projects/${id}/selection-events`, {
        screen: '시안선택',
        action: mode === 'regenerate' ? 'regenerate' : mode === 'new_direction' ? 'view_more_direction' : 'more_like_this',
        shown_candidates: candidates.map((c) => ({ candidate_id: c.id, generation_params: {} })),
        selected_candidate_id: null,
      });
    }
    const { job_id } = await api.post<{ job_id: string }>(`/projects/${id}/drafts`, {
      mode, count: 3, reference_candidate_id: referenceId,
    });
    await pollJob(job_id);
  }

  useEffect(() => {
    if (startedRef.current) return;
    startedRef.current = true;
    if (candidates.length === 0) {
      generate('initial');
    } else {
      setJobStatus('done');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  async function select(candidateId: string) {
    await api.post(`/projects/${id}/selection-events`, {
      screen: '시안선택',
      action: 'select',
      shown_candidates: candidates.map((c) => ({ candidate_id: c.id, generation_params: {} })),
      selected_candidate_id: candidateId,
    });
    navigate(`/projects/${id}/confirm?candidate=${candidateId}`);
  }

  return (
    <div>
      <Header />
      <div className="page">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--sp-6)' }}>
          <h1 className="h1">마음에 드는 시안을 골라주세요</h1>
        </div>

        {(jobStatus === 'idle' || jobStatus === 'running') && candidates.length === 0 && (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 'var(--sp-4)' }}>
            {[0, 1, 2].map((i) => (
              <div key={i} style={{ aspectRatio: '3/4', background: 'var(--bg-hover)', borderRadius: 'var(--r-lg)' }} />
            ))}
          </div>
        )}
        {jobStatus === 'running' && (
          <p className="body-sm" style={{ marginTop: 'var(--sp-3)' }}>시안을 만들고 있어요 · 보통 20~40초</p>
        )}
        {jobStatus === 'failed' && (
          <div className="card" style={{ padding: 'var(--sp-5)', borderColor: 'var(--error)' }}>
            <p className="body-sm" style={{ color: 'var(--error)', marginBottom: 'var(--sp-3)' }}>
              시안 생성에 실패했습니다. 사용된 크레딧은 자동으로 환불되었습니다.
            </p>
            <button className="btn btn-secondary" onClick={() => generate('initial')}>다시 시도</button>
          </div>
        )}

        {candidates.length > 0 && (
          <>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 'var(--sp-4)' }}>
              {candidates.map((c) => (
                <div key={c.id} className="card" style={{ overflow: 'hidden' }}>
                  <img src={c.preview_image_url ?? undefined} alt="시안" style={{ width: '100%', display: 'block' }} />
                  <div style={{ padding: 'var(--sp-3)' }}>
                    <button className="btn btn-primary" style={{ width: '100%' }} onClick={() => select(c.id)}>
                      이 시안으로 확정
                    </button>
                    <button className="btn btn-tertiary btn-sm" style={{ width: '100%', marginTop: 'var(--sp-2)' }}
                            onClick={() => generate('more_like', c.id)}>
                      이 방향으로 더 보기
                    </button>
                  </div>
                </div>
              ))}
            </div>
            <div style={{ display: 'flex', gap: 'var(--sp-3)', marginTop: 'var(--sp-5)' }}>
              <button className="btn btn-secondary" onClick={() => generate('regenerate')}>재생성</button>
              <button className="btn btn-secondary" onClick={() => generate('new_direction')}>다른 방향 보기</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
