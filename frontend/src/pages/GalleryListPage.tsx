import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, GalleryItem } from '../lib/api';

const GENRES = ['클래식', '무용', '연극', '뮤지컬', '어린이공연', '인디밴드', '대중음악'];

/** G-1 Gallery 목록(7-3, Stage 17). 인증 불필요 — 공개(public)·확정(active) 프로젝트만 노출. */
export default function GalleryListPage() {
  const navigate = useNavigate();
  const [genre, setGenre] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['gallery', genre],
    queryFn: () => api.get<{ items: GalleryItem[] }>(`/gallery${genre ? `?genre=${encodeURIComponent(genre)}` : ''}`),
  });

  const items = data?.items ?? [];

  return (
    <div>
      <Header />
      <div className="page">
        <h1 className="h1" style={{ marginBottom: 'var(--sp-2)' }}>Gallery</h1>
        <p className="body-sm" style={{ marginBottom: 'var(--sp-5)' }}>공개된 공연을 둘러보고, 마음에 드는 스타일을 참고해 내 포스터를 만들어보세요.</p>

        <div style={{ display: 'flex', gap: 'var(--sp-2)', marginBottom: 'var(--sp-6)', flexWrap: 'wrap' }}>
          <button className={genre === null ? 'btn btn-primary btn-sm' : 'btn btn-secondary btn-sm'} onClick={() => setGenre(null)}>전체</button>
          {GENRES.map((g) => (
            <button key={g} className={genre === g ? 'btn btn-primary btn-sm' : 'btn btn-secondary btn-sm'} onClick={() => setGenre(g)}>
              {g}
            </button>
          ))}
        </div>

        {isLoading && <p className="body-sm">불러오는 중…</p>}

        {!isLoading && items.length === 0 && (
          <div className="card" style={{ padding: 'var(--sp-16)', textAlign: 'center' }}>
            <p className="body-sm">아직 공개된 공연이 없어요</p>
          </div>
        )}

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 'var(--sp-4)' }}>
          {items.map((p) => (
            <div key={p.project_id} className="card" style={{ overflow: 'hidden', cursor: 'pointer', position: 'relative' }}
                 onClick={() => navigate(`/gallery/${p.project_id}`)}>
              {p.source === 'kopis' && (
                <span className="badge badge-neutral" style={{ position: 'absolute', top: 8, left: 8, zIndex: 1 }}>
                  KOPIS 제공
                </span>
              )}
              <div style={{
                aspectRatio: '3 / 4', background: p.thumbnail_url ? `url(${p.thumbnail_url}) center/cover` : 'var(--bg-hover)',
              }} />
              <div style={{ padding: 'var(--sp-3)' }}>
                <h3 className="h3" style={{ marginBottom: 'var(--sp-1)' }}>{p.main_title || '(제목 없음)'}</h3>
                <p className="body-sm" style={{ color: 'var(--gray-warm)' }}>
                  {p.genre ?? '-'} · {p.primary_date ?? '-'}
                </p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
