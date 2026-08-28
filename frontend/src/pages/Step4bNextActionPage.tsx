import { useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, AssetItem } from '../lib/api';

/** ④-1 프로젝트 생성 완료 — 다음 행동 선택(1-14, docs/04 3-2). */
export default function Step4bNextActionPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const { data } = useQuery({
    queryKey: ['assets', id, '포스터'],
    queryFn: () => api.get<{ items: AssetItem[] }>(`/projects/${id}/assets?category=포스터`),
    enabled: !!id,
  });
  const poster = data?.items[0];

  return (
    <div>
      <Header />
      <div className="page" style={{ maxWidth: 480, textAlign: 'center' }}>
        <h1 className="h1" style={{ marginBottom: 'var(--sp-3)' }}>프로젝트가 생성되었습니다</h1>
        {poster?.preview_image_url && (
          <img src={poster.preview_image_url} alt="확정된 포스터" className="card" style={{ boxShadow: 'var(--shadow-lg)', width: '100%', maxWidth: 320, margin: '0 auto var(--sp-6)', display: 'block' }} />
        )}
        <div style={{ display: 'flex', gap: 'var(--sp-3)', justifyContent: 'center' }}>
          <button className="btn btn-secondary" onClick={() => navigate(`/projects/${id}/dashboard`)}>대시보드로 가기</button>
          <button className="btn btn-primary" onClick={() => navigate(`/projects/${id}/formats`)}>지금 바로 홍보물 만들기</button>
        </div>
      </div>
    </div>
  );
}
