import { useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api } from '../lib/api';

/**
 * 새 프로젝트 — 시작 방법 선택(docs/03 신규 생성 플로우 진입).
 * AI로 포스터를 만들거나(① 정보 입력), 가지고 있는 포스터를 올려 규격 변환부터 시작한다(U-1).
 */
export default function NewProjectPage() {
  const navigate = useNavigate();

  const createAi = useMutation({
    mutationFn: () => api.post<{ id: string }>('/projects'),
    onSuccess: (p) => navigate(`/projects/${p.id}/info`),
  });
  const createUpload = useMutation({
    mutationFn: () => api.post<{ id: string }>('/projects?mode=upload'),
    onSuccess: (p) => navigate(`/projects/${p.id}/upload-poster`),
  });
  const pending = createAi.isPending || createUpload.isPending;

  return (
    <div>
      <Header />
      <div className="page" style={{ maxWidth: 760 }}>
        <h1 className="h1" style={{ marginBottom: 'var(--sp-2)' }}>새 프로젝트 만들기</h1>
        <p className="body-sm" style={{ marginBottom: 'var(--sp-6)' }}>어떻게 시작할지 골라주세요.</p>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 'var(--sp-4)' }}>
          <button className="card" disabled={pending} onClick={() => createAi.mutate()}
                  style={{ padding: 'var(--sp-6)', textAlign: 'left', cursor: 'pointer' }}>
            <h2 className="h2" style={{ marginBottom: 'var(--sp-2)' }}>AI로 포스터 만들기</h2>
            <p className="body-sm">공연 정보를 입력하면 포스터 시안을 만들어 드려요. 확정한 포스터로 여러 규격을 만들 수 있어요.</p>
          </button>
          <button className="card" disabled={pending} onClick={() => createUpload.mutate()}
                  style={{ padding: 'var(--sp-6)', textAlign: 'left', cursor: 'pointer' }}>
            <h2 className="h2" style={{ marginBottom: 'var(--sp-2)' }}>가지고 있는 포스터로 시작</h2>
            <p className="body-sm">이미 만든 포스터를 올리면 요소를 나눠서 SNS·배너·현수막 등 여러 규격으로 바꿔 드려요.</p>
          </button>
        </div>
      </div>
    </div>
  );
}
