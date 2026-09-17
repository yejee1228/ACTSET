import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, ApiError, AssetItem, JobStatus } from '../lib/api';
import { trackFunnelStep } from '../lib/funnel';

type DraftMode = 'initial' | 'regenerate' | 'more_like';

/** ③ 시안 선택 화면(1-12·1-13). 시안 3장이 화면의 대부분을 차지한다(docs/16).
 * "재생성"과 "다른 방향 보기"는 하나의 버튼(재생성)으로 합쳤다 — 사용자 피드백:
 * 둘의 차이가 화면에서 구분되지 않았다. 이제 재생성은 항상 새로운 스타일을 시도한다. */
export default function Step3DraftSelectionPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [jobStatus, setJobStatus] = useState<'idle' | 'running' | 'done' | 'failed'>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const startedRef = useRef(false);

  const { data: estimate } = useQuery({
    queryKey: ['creditsEstimate', 'draft_generate', 'initial'],
    queryFn: () => api.get<{ estimated_cost: number; balance: number; sufficient: boolean }>('/credits/estimate?kind=draft_generate&mode=initial'),
  });

  // 재생성·더보기는 initial과 단가가 달라서(docs/06) 별도로 조회한다 — 버튼 비활성화 판단 기준.
  const { data: regenEstimate } = useQuery({
    queryKey: ['creditsEstimate', 'draft_generate', 'regenerate'],
    queryFn: () => api.get<{ estimated_cost: number; balance: number; sufficient: boolean }>('/credits/estimate?kind=draft_generate&mode=regenerate'),
  });

  const { data: assetData, isLoading: assetsLoading, refetch: refetchAssets } = useQuery({
    queryKey: ['assets', id, '시안후보'],
    queryFn: () => api.get<{ items: AssetItem[] }>(`/projects/${id}/assets?category=시안후보`),
    enabled: !!id,
  });

  const candidates = (assetData?.items ?? []).filter((a) => a.status !== '보관').sort((a, b) => (a.variant_index ?? 0) - (b.variant_index ?? 0));

  const [expandedPromptId, setExpandedPromptId] = useState<string | null>(null);
  const [favoriteError, setFavoriteError] = useState<string | null>(null);
  const toggleFavorite = useMutation({
    mutationFn: ({ assetId, favorited }: { assetId: string; favorited: boolean }) =>
      favorited ? api.post(`/assets/${assetId}/favorite`) : api.del(`/assets/${assetId}/favorite`),
    onSuccess: () => {
      setFavoriteError(null);
      queryClient.invalidateQueries({ queryKey: ['assets', id, '시안후보'] });
    },
    onError: (err) => setFavoriteError(err instanceof ApiError ? err.message : '요청에 실패했습니다.'),
  });

  async function pollJob(jobId: string) {
    setJobStatus('running');
    for (let i = 0; i < 60; i++) {
      await new Promise((r) => setTimeout(r, 2500));
      const job = await api.get<JobStatus>(`/jobs/${jobId}`);
      if (job.status === 'succeeded') {
        setJobStatus('done');
        await refetchAssets();
        queryClient.invalidateQueries({ queryKey: ['auth', 'me'] });
        queryClient.invalidateQueries({ queryKey: ['creditsEstimate'] });
        return;
      }
      if (job.status === 'failed') {
        setJobStatus('failed');
        return;
      }
    }
    setJobStatus('failed');
  }

  async function generate(mode: DraftMode, referenceId?: string) {
    setErrorMessage(null);
    try {
      // 액션 발생 시점에 화면에 표시 중이던 후보 전체를 shown_candidates로 함께 기록한다(docs/02 핵심).
      if (mode !== 'initial' && id) {
        await api.post(`/projects/${id}/selection-events`, {
          screen: '시안선택',
          action: mode === 'regenerate' ? 'regenerate' : 'more_like_this',
          shown_candidates: candidates.map((c) => ({ candidate_id: c.id, generation_params: {} })),
          selected_candidate_id: null,
        });
      }
      const { job_id } = await api.post<{ job_id: string }>(`/projects/${id}/drafts`, {
        mode, count: 3, reference_candidate_id: referenceId,
      });
      await pollJob(job_id);
    } catch (err) {
      setJobStatus('failed');
      setErrorMessage(err instanceof ApiError ? err.message : '요청에 실패했습니다.');
    }
  }

  const [showResumeConfirm, setShowResumeConfirm] = useState(false);

  useEffect(() => { trackFunnelStep('step_3_drafts'); }, []);

  useEffect(() => {
    if (startedRef.current) return;
    // 기존 시안 유무 조회가 끝나기 전에 판단하면, 재방문 시 있던 시안을 못 보고
    // 새로 생성해버려 크레딧이 중복 소비될 수 있다 — 조회가 끝날 때까지 기다린다.
    if (assetsLoading) return;
    startedRef.current = true;
    if (candidates.length === 0) {
      generate('initial');
    } else {
      // 페이지를 열자마자 이미 시안이 있다 = 이전에 만들어두고 떠났다가 돌아온 것.
      // 조용히 보여주지 않고 이어서 볼지 물어본다.
      setShowResumeConfirm(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, assetsLoading]);

  async function select(candidateId: string) {
    await api.post(`/projects/${id}/selection-events`, {
      screen: '시안선택',
      action: 'select',
      shown_candidates: candidates.map((c) => ({ candidate_id: c.id, generation_params: {} })),
      selected_candidate_id: candidateId,
    });
    navigate(`/projects/${id}/confirm?candidate=${candidateId}`);
  }

  const regenDisabled = jobStatus === 'running' || (regenEstimate ? !regenEstimate.sufficient : false);
  const showLoadingBoxes = !showResumeConfirm && jobStatus === 'running';

  return (
    <div>
      <Header />
      <div className="page">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--sp-6)' }}>
          <h1 className="h1">마음에 드는 시안을 골라주세요</h1>
        </div>

        {showResumeConfirm && (
          <div className="card" style={{ padding: 'var(--sp-5)', marginBottom: 'var(--sp-5)' }}>
            <p className="body-sm" style={{ marginBottom: 'var(--sp-3)' }}>
              이전에 만들어둔 시안이 있어요. 이어서 선택하시겠어요?
            </p>
            <div style={{ display: 'flex', gap: 'var(--sp-2)' }}>
              <button className="btn btn-primary btn-sm" onClick={() => { setShowResumeConfirm(false); setJobStatus('done'); }}>
                이어서 보기
              </button>
              <button className="btn btn-secondary btn-sm" onClick={() => { setShowResumeConfirm(false); generate('initial'); }}>
                새로 만들기
              </button>
            </div>
          </div>
        )}

        {showLoadingBoxes && (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 'var(--sp-4)' }}>
            {[0, 1, 2].map((i) => (
              <div key={i} style={{
                aspectRatio: '3/4', background: 'var(--bg-hover)', borderRadius: 'var(--r-lg)',
                display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 'var(--sp-3)',
              }}>
                <div className="spinner" />
                <span className="caption" style={{ color: 'var(--gray-warm)' }}>생성 중…</span>
              </div>
            ))}
          </div>
        )}
        {jobStatus === 'running' && (
          <p className="body-sm" style={{ marginTop: 'var(--sp-3)' }}>시안을 만들고 있어요 · 보통 20~40초</p>
        )}
        {jobStatus === 'failed' && (
          <div className="card" style={{ padding: 'var(--sp-5)', borderColor: 'var(--error)' }}>
            <p className="body-sm" style={{ color: 'var(--error)', marginBottom: 'var(--sp-3)' }}>
              {errorMessage ?? '시안 생성에 실패했습니다. 사용된 크레딧은 자동으로 환불되었습니다.'}
            </p>
            <button className="btn btn-secondary" onClick={() => generate(candidates.length === 0 ? 'initial' : 'regenerate')}>다시 시도</button>
          </div>
        )}
        {estimate && candidates.length === 0 && jobStatus !== 'failed' && (
          <p className="caption" style={{ marginTop: 'var(--sp-2)', color: estimate.sufficient ? 'var(--gray-warm)' : 'var(--error)' }}>
            시안 3장 생성 시 예상 소비 {estimate.estimated_cost.toLocaleString()} 크레딧 · 보유 {estimate.balance.toLocaleString()}
            {!estimate.sufficient && ' — 크레딧이 부족합니다.'}
          </p>
        )}
        {favoriteError && (
          <p className="caption" style={{ marginTop: 'var(--sp-2)', color: 'var(--error)' }}>{favoriteError}</p>
        )}

        {!showResumeConfirm && !showLoadingBoxes && candidates.length > 0 && (
          <>
            <p className="caption" style={{ marginBottom: 'var(--sp-3)', color: 'var(--gray-warm)' }}>
              생성된 시안은 24시간 동안 보관돼요. 페이지를 나갔다 돌아와도 이어서 선택할 수 있어요.
            </p>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 'var(--sp-4)' }}>
              {candidates.map((c) => (
                <div key={c.id} className="card" style={{ overflow: 'hidden', position: 'relative' }}>
                  <button
                    className="btn btn-tertiary btn-sm"
                    style={{ position: 'absolute', top: 8, right: 8, zIndex: 1, background: 'var(--surface)' }}
                    onClick={() => toggleFavorite.mutate({ assetId: c.id, favorited: !c.is_favorited })}
                    aria-label="후보함 즐겨찾기"
                  >
                    {c.is_favorited ? '★' : '☆'}
                  </button>
                  <img src={c.preview_image_url ?? undefined} alt="시안" style={{ width: '100%', display: 'block' }} />
                  <div style={{ padding: 'var(--sp-3)' }}>
                    <button className="btn btn-primary" style={{ width: '100%' }} onClick={() => select(c.id)}>
                      이 시안으로 확정
                    </button>
                    <button className="btn btn-tertiary btn-sm" style={{ width: '100%', marginTop: 'var(--sp-2)' }}
                            disabled={regenDisabled} onClick={() => generate('more_like', c.id)}>
                      이 방향으로 더 보기
                    </button>
                    {c.prompt && (
                      <button className="btn btn-tertiary btn-sm" style={{ width: '100%', marginTop: 'var(--sp-2)' }}
                              onClick={() => setExpandedPromptId(expandedPromptId === c.id ? null : c.id)}>
                        {expandedPromptId === c.id ? '프롬프트 숨기기' : '프롬프트 보기'}
                      </button>
                    )}
                    {expandedPromptId === c.id && (
                      <div className="caption" style={{ marginTop: 'var(--sp-2)', padding: 'var(--sp-2)', background: 'var(--bg-hover)', borderRadius: 'var(--r-sm)', wordBreak: 'break-word' }}>
                        <p><strong>프롬프트:</strong> {c.prompt}</p>
                        {c.negative_prompt && <p style={{ marginTop: 'var(--sp-1)' }}><strong>제외 요소:</strong> {c.negative_prompt}</p>}
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </div>
            <div style={{ marginTop: 'var(--sp-5)' }}>
              <div style={{ display: 'flex', gap: 'var(--sp-3)', alignItems: 'center' }}>
                <button className="btn btn-secondary" disabled={regenDisabled} onClick={() => generate('regenerate')}>
                  재생성
                </button>
                {regenEstimate && (
                  <span className="caption" style={{ color: regenEstimate.sufficient ? 'var(--gray-warm)' : 'var(--error)' }}>
                    {regenEstimate.estimated_cost.toLocaleString()} 크레딧 소비 · 보유 {regenEstimate.balance.toLocaleString()}
                  </span>
                )}
              </div>
              {regenEstimate && !regenEstimate.sufficient && (
                <p className="caption" style={{ marginTop: 'var(--sp-2)', color: 'var(--error)' }}>
                  크레딧이 부족해서 재생성할 수 없어요.{' '}
                  <button className="btn btn-tertiary btn-sm" style={{ padding: 0, textDecoration: 'underline' }}
                          onClick={() => navigate('/credits')}>
                    보유 크레딧 확인하기
                  </button>
                </p>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
