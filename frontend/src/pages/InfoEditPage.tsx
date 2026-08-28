import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, ProjectDetail } from '../lib/api';

const GENRES = ['클래식', '무용', '연극', '뮤지컬', '어린이공연', '인디밴드', '대중음악'];

/**
 * 6-1 정보 수정 화면(4-3). ①+②를 한 화면에 통합하고, ②와 달리 자동저장이 아니라
 * 명시적 "저장" 버튼을 쓴다(docs/04 — 저장 시 포스터 자동 재렌더링되므로 자동저장이면
 * 입력할 때마다 이미지 생성이 반복된다).
 */
export default function InfoEditPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const { data: project } = useQuery({
    queryKey: ['project', id],
    queryFn: () => api.get<ProjectDetail>(`/projects/${id}`),
    enabled: !!id,
  });

  const [genre, setGenre] = useState('');
  const [mainTitle, setMainTitle] = useState('');
  const [venueName, setVenueName] = useState('');
  const [venueUndetermined, setVenueUndetermined] = useState(false);
  const [date, setDate] = useState('');
  const [dateUndetermined, setDateUndetermined] = useState(false);
  const [subtitle, setSubtitle] = useState('');
  const [runningTime, setRunningTime] = useState('');
  const [age, setAge] = useState('');
  const [imageDirectionNote, setImageDirectionNote] = useState('');
  const [savedAt, setSavedAt] = useState<string | null>(null);

  useEffect(() => {
    if (!project) return;
    const info = project.performance_info as Record<string, any>;
    setGenre(project.genre ?? '');
    setMainTitle(project.main_title ?? '');
    setVenueName(info?.venue?.name ?? '');
    setVenueUndetermined(project.flags?.venue_undetermined ?? false);
    setDate(info?.sessions?.[0]?.date ?? '');
    setDateUndetermined(project.flags?.date_undetermined ?? false);
    setSubtitle(info?.subtitle ?? '');
    setRunningTime(info?.running_time ?? '');
    setAge(info?.age ?? '');
    setImageDirectionNote(info?.image_direction_note ?? '');
    // eslint-disable-next-line react-hooks/exhaustive-deps
    // project?.id가 아니라 project 참조 전체에 의존해야 한다 — 캐시된 값(stale)으로 먼저
    // 렌더된 뒤 리페치로 최신 performance_info가 들어와도 id는 그대로라 이 effect가 다시
    // 돌지 않으면 화면에 옛 값이 남는 버그가 있었다(4-3 e2e 테스트로 발견).
  }, [project]);

  const save = useMutation({
    mutationFn: () => api.patch<{ poster_resync?: { job_id?: string } }>(`/projects/${id}/info`, {
      genre,
      main_title: mainTitle,
      venue: { name: venueUndetermined ? '' : venueName, is_undetermined: venueUndetermined },
      sessions: [{ date: dateUndetermined ? null : date, is_undetermined: dateUndetermined }],
      subtitle, running_time: runningTime, age, image_direction_note: imageDirectionNote,
    }),
    onSuccess: () => setSavedAt(new Date().toLocaleTimeString('ko-KR')),
  });

  return (
    <div>
      <Header />
      <div className="page" style={{ maxWidth: 640 }}>
        <h1 className="h1" style={{ marginBottom: 'var(--sp-2)' }}>정보 수정</h1>
        <p className="body-sm" style={{ marginBottom: 'var(--sp-6)' }}>
          포스터는 자동으로 반영돼요. 다른 홍보물은 대시보드에서 [최신 반영]을 눌러야 새 정보로 바뀌어요.
        </p>

        <div className="card" style={{ padding: 'var(--sp-6)', display: 'grid', gap: 'var(--sp-5)' }}>
          <div>
            <label className="field-label">장르</label>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 'var(--sp-2)' }}>
              {GENRES.map((g) => (
                <button key={g} type="button" className={g === genre ? 'btn btn-primary btn-sm' : 'btn btn-secondary btn-sm'}
                        onClick={() => setGenre(g)}>{g}</button>
              ))}
            </div>
          </div>

          <div>
            <label className="field-label" htmlFor="title">공연명</label>
            <input id="title" className="input" value={mainTitle} onChange={(e) => setMainTitle(e.target.value)} />
          </div>

          <div>
            <label className="field-label" htmlFor="venue">장소</label>
            <div style={{ display: 'flex', gap: 'var(--sp-3)', alignItems: 'center' }}>
              <input id="venue" className="input" value={venueName} disabled={venueUndetermined}
                     onChange={(e) => setVenueName(e.target.value)} />
              <label style={{ display: 'flex', gap: 4, alignItems: 'center', whiteSpace: 'nowrap' }}>
                <input type="checkbox" checked={venueUndetermined} onChange={(e) => setVenueUndetermined(e.target.checked)} />
                <span className="body-sm">장소 미정</span>
              </label>
            </div>
          </div>

          <div>
            <label className="field-label" htmlFor="date">날짜</label>
            <div style={{ display: 'flex', gap: 'var(--sp-3)', alignItems: 'center' }}>
              <input id="date" type="date" className="input" value={date ?? ''} disabled={dateUndetermined}
                     onChange={(e) => setDate(e.target.value)} />
              <label style={{ display: 'flex', gap: 4, alignItems: 'center', whiteSpace: 'nowrap' }}>
                <input type="checkbox" checked={dateUndetermined} onChange={(e) => setDateUndetermined(e.target.checked)} />
                <span className="body-sm">날짜 미정</span>
              </label>
            </div>
          </div>

          <div>
            <label className="field-label">부제</label>
            <input className="input" value={subtitle} onChange={(e) => setSubtitle(e.target.value)} />
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--sp-3)' }}>
            <div>
              <label className="field-label">러닝타임</label>
              <input className="input" value={runningTime} onChange={(e) => setRunningTime(e.target.value)} />
            </div>
            <div>
              <label className="field-label">관람연령</label>
              <input className="input" value={age} onChange={(e) => setAge(e.target.value)} />
            </div>
          </div>
          <div>
            <label className="field-label">원하는 이미지 방향</label>
            <textarea className="textarea" value={imageDirectionNote} onChange={(e) => setImageDirectionNote(e.target.value)} />
          </div>
        </div>

        <div style={{ marginTop: 'var(--sp-6)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <button className="btn btn-secondary" onClick={() => navigate(`/projects/${id}/dashboard`)}>취소</button>
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--sp-3)' }}>
            {savedAt && <span className="caption">저장됨 · {savedAt}</span>}
            <button className="btn btn-primary" disabled={save.isPending} onClick={() => save.mutate()}>저장</button>
          </div>
        </div>
      </div>
    </div>
  );
}
