import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, ApiError, GalleryDetail } from '../lib/api';

/** G-2 Gallery 상세(7-4, Stage 17). "참고해서 만들기" → 스타일 힌트만 반영된 새 draft 생성(7-6). */
export default function GalleryDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const { data, isLoading } = useQuery({
    queryKey: ['gallery', 'detail', id],
    queryFn: () => api.get<GalleryDetail>(`/gallery/${id}`),
    enabled: !!id,
  });

  const createFromReference = useMutation({
    mutationFn: () => api.post<{ id: string }>(`/projects/from-reference/${id}`),
    onSuccess: (project) => navigate(`/projects/${project.id}/info`),
    onError: (err) => {
      if (err instanceof ApiError && err.status === 401) navigate('/login');
    },
  });

  if (isLoading) {
    return (
      <div>
        <Header />
        <div className="page"><p className="body-sm">불러오는 중…</p></div>
      </div>
    );
  }

  if (!data) {
    return (
      <div>
        <Header />
        <div className="page"><p className="body-sm">찾을 수 없는 공연입니다.</p></div>
      </div>
    );
  }

  return (
    <div>
      <Header />
      <div className="page" style={{ maxWidth: 640 }}>
        <button className="btn btn-tertiary btn-sm" style={{ marginBottom: 'var(--sp-4)' }} onClick={() => navigate('/gallery')}>
          ← Gallery로
        </button>

        {data.poster_preview_url ? (
          <img src={data.poster_preview_url} alt={data.main_title} style={{ width: '100%', borderRadius: 'var(--r-xl)', marginBottom: 'var(--sp-5)' }} />
        ) : (
          <div style={{ aspectRatio: '3/4', background: 'var(--bg-hover)', borderRadius: 'var(--r-xl)', marginBottom: 'var(--sp-5)' }} />
        )}

        {data.source === 'kopis' && (
          <span className="badge badge-neutral" style={{ marginBottom: 'var(--sp-2)', display: 'inline-block' }}>
            KOPIS(공연예술통합전산망) 제공
          </span>
        )}
        <h1 className="h1" style={{ marginBottom: 'var(--sp-2)' }}>{data.main_title}</h1>
        <p className="body-sm" style={{ marginBottom: 'var(--sp-6)', color: 'var(--gray-warm)' }}>
          {data.genre ?? '-'} · {data.primary_date ?? '-'} · {data.venue_name ?? '장소 미정'}
        </p>

        {data.source === 'self' ? (
          <>
            <button className="btn btn-primary" disabled={createFromReference.isPending} onClick={() => createFromReference.mutate()}>
              참고해서 만들기
            </button>
            <p className="caption" style={{ marginTop: 'var(--sp-2)', color: 'var(--gray-warm)' }}>
              이 공연의 색조·분위기 등 스타일만 참고하며, 공연정보는 복사되지 않습니다.
            </p>
            {createFromReference.isError && (
              <p className="body-sm" style={{ color: 'var(--error)', marginTop: 'var(--sp-2)' }}>
                {createFromReference.error instanceof ApiError ? createFromReference.error.message : '요청에 실패했습니다.'}
              </p>
            )}
          </>
        ) : (
          <p className="caption" style={{ color: 'var(--gray-warm)' }}>
            KOPIS 제공 공연은 공연 정보만 있어 디자인 스타일을 참고할 수 없습니다.
          </p>
        )}
      </div>
    </div>
  );
}
