import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useInfiniteQuery } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, GalleryItem, GalleryPage } from '../lib/api';

const GENRES = ['클래식', '무용', '연극', '뮤지컬', '어린이공연', '인디밴드', '대중음악'];
const PAGE = 24;

/** 공연 기간 표시 — 하루짜리는 날짜 하나만. */
function period(p: GalleryItem) {
  if (!p.primary_date) return '-';
  if (!p.end_date || p.end_date === p.primary_date) return p.primary_date;
  return `${p.primary_date} ~ ${p.end_date}`;
}

/**
 * G-1 Gallery 목록(7-3, Stage 17). 인증 불필요. 자체 공개 프로젝트 다음에 KOPIS 공연(공연중 → 공연예정)이 이어진다.
 * KOPIS 항목은 출처를 표시한다(docs/14 1-4·15 출처 표기).
 */
export default function GalleryListPage() {
  const navigate = useNavigate();
  const [genre, setGenre] = useState<string | null>(null);

  const query = useInfiniteQuery({
    queryKey: ['gallery', genre],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: String(PAGE), offset: String(pageParam) });
      if (genre) params.set('genre', genre);
      return api.get<GalleryPage>(`/gallery?${params}`);
    },
    getNextPageParam: (last) => last.next_offset ?? undefined,
  });

  const items = query.data?.pages.flatMap((p) => p.items) ?? [];
  const hasKopis = items.some((p) => p.source === 'kopis');

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

        {query.isLoading && <p className="body-sm">불러오는 중…</p>}

        {!query.isLoading && items.length === 0 && (
          <div className="card" style={{ padding: 'var(--sp-16)', textAlign: 'center' }}>
            <p className="body-sm">{genre ? '이 장르의 공연이 아직 없어요' : '아직 공개된 공연이 없어요'}</p>
          </div>
        )}

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 'var(--sp-4)' }}>
          {items.map((p) => (
            <div key={`${p.source}-${p.project_id}`} className="card" style={{ overflow: 'hidden', cursor: 'pointer', position: 'relative' }}
                 onClick={() => navigate(`/gallery/${p.project_id}`)}>
              {p.source === 'kopis' && (
                <span className="badge badge-neutral" style={{ position: 'absolute', top: 8, left: 8, zIndex: 1 }}>
                  KOPIS 제공
                </span>
              )}
              <div style={{ aspectRatio: '3 / 4', background: 'var(--bg-hover)' }}>
                {p.thumbnail_url && (
                  <img src={p.thumbnail_url} alt={p.main_title} loading="lazy" referrerPolicy="no-referrer"
                       style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
                       onError={(e) => { e.currentTarget.style.display = 'none'; }} />
                )}
              </div>
              <div style={{ padding: 'var(--sp-3)' }}>
                <h3 className="h3" style={{ marginBottom: 'var(--sp-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {p.main_title || '(제목 없음)'}
                </h3>
                <p className="body-sm" style={{ color: 'var(--gray-warm)' }}>{p.genre ?? '-'} · {period(p)}</p>
                {p.venue_name && (
                  <p className="caption" style={{ color: 'var(--gray-warm)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {p.venue_name}
                  </p>
                )}
              </div>
            </div>
          ))}
        </div>

        {query.hasNextPage && (
          <div style={{ textAlign: 'center', marginTop: 'var(--sp-6)' }}>
            <button className="btn btn-secondary" disabled={query.isFetchingNextPage} onClick={() => query.fetchNextPage()}>
              {query.isFetchingNextPage ? '불러오는 중…' : '더 보기'}
            </button>
          </div>
        )}

        {hasKopis && (
          <p className="caption" style={{ marginTop: 'var(--sp-8)', color: 'var(--gray-warm)' }}>
            공연 정보 출처: KOPIS(공연예술통합전산망, www.kopis.or.kr)
          </p>
        )}
      </div>
    </div>
  );
}
