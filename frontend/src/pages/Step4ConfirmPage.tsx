import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, AssetItem } from '../lib/api';

/** ④ 시안 확정 인터스티셜(1-14, docs/04 3-1). */
export default function Step4ConfirmPage() {
  const { id } = useParams<{ id: string }>();
  const [params] = useSearchParams();
  const candidateId = params.get('candidate') ?? '';
  const navigate = useNavigate();

  const { data } = useQuery({
    queryKey: ['assets', id, '시안후보'],
    queryFn: () => api.get<{ items: AssetItem[] }>(`/projects/${id}/assets?category=시안후보`),
    enabled: !!id,
  });
  const candidate = data?.items.find((a) => a.id === candidateId);

  const confirm = useMutation({
    mutationFn: () => api.post<{ poster_asset_id: string }>(`/projects/${id}/confirm`, { selected_candidate_id: candidateId }),
    onSuccess: () => navigate(`/projects/${id}/next`),
  });

  return (
    <div>
      <Header />
      <div className="page" style={{ maxWidth: 480, textAlign: 'center' }}>
        <h1 className="h1" style={{ marginBottom: 'var(--sp-5)' }}>이 디자인으로 프로젝트를 시작할까요?</h1>
        {candidate?.preview_image_url && (
          <img src={candidate.preview_image_url} alt="선택한 시안" className="card"
               style={{ width: '100%', maxWidth: 320, margin: '0 auto var(--sp-6)', display: 'block' }} />
        )}
        {confirm.isError && <p className="body-sm" style={{ color: 'var(--error)' }}>확정에 실패했습니다. 필수 정보를 다시 확인해주세요.</p>}
        <div style={{ display: 'flex', gap: 'var(--sp-3)', justifyContent: 'center' }}>
          <button className="btn btn-secondary" onClick={() => navigate(`/projects/${id}/drafts`)}>취소</button>
          <button className="btn btn-primary" onClick={() => confirm.mutate()} disabled={confirm.isPending}>
            확정
          </button>
        </div>
      </div>
    </div>
  );
}
