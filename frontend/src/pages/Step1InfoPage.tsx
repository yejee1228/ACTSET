import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, ProjectDetail } from '../lib/api';

const GENRES = ['클래식', '무용', '연극', '뮤지컬', '어린이공연', '인디밴드', '대중음악'];

/** ① 공연 정보 입력(1-7). 필수 4항목 — 장르·공연명·장소·날짜(또는 각 미정). */
export default function Step1InfoPage() {
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

  useEffect(() => {
    if (!project) return;
    const info = project.performance_info as Record<string, any>;
    setGenre(project.genre ?? '');
    setMainTitle(project.main_title ?? '');
    setVenueName(info?.venue?.name ?? '');
    setVenueUndetermined(project.flags?.venue_undetermined ?? false);
    setDate(info?.sessions?.[0]?.date ?? '');
    setDateUndetermined(project.flags?.date_undetermined ?? false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project?.id]);

  const save = useMutation({
    mutationFn: (partial: Record<string, unknown>) => api.patch(`/projects/${id}/info`, partial),
  });

  function patch(partial: Record<string, unknown>) {
    save.mutate(partial);
  }

  const canProceed =
    genre !== '' &&
    mainTitle.trim() !== '' &&
    (venueUndetermined || venueName.trim() !== '') &&
    (dateUndetermined || date !== '');

  function next() {
    // 필드별 자동저장을 이미 해왔으므로 여기서는 최신값만 한 번 더 보낸다(이탈 대비 안전망).
    patch({
      genre,
      main_title: mainTitle,
      venue: { name: venueUndetermined ? '' : venueName, is_undetermined: venueUndetermined },
      sessions: [{ date: dateUndetermined ? null : date, is_undetermined: dateUndetermined }],
    });
    navigate(`/projects/${id}/additional`);
  }

  return (
    <div>
      <Header />
      <div className="page" style={{ maxWidth: 640 }}>
        <p className="caption" style={{ marginBottom: 'var(--sp-2)' }}>① / ④ · 공연 정보 입력</p>
        <h1 className="h1" style={{ marginBottom: 'var(--sp-6)' }}>어떤 공연인가요?</h1>

        <div className="card" style={{ padding: 'var(--sp-6)', display: 'grid', gap: 'var(--sp-5)' }}>
          <div>
            <label className="field-label">장르</label>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 'var(--sp-2)' }}>
              {GENRES.map((g) => (
                <button
                  key={g}
                  type="button"
                  className={g === genre ? 'btn btn-primary btn-sm' : 'btn btn-secondary btn-sm'}
                  onClick={() => {
                    setGenre(g);
                    patch({ genre: g });
                  }}
                >
                  {g}
                </button>
              ))}
            </div>
          </div>

          <div>
            <label className="field-label" htmlFor="title">공연명</label>
            <input
              id="title" className="input" value={mainTitle}
              onChange={(e) => setMainTitle(e.target.value)}
              onBlur={() => patch({ main_title: mainTitle })}
              placeholder="예: 겨울 나그네"
            />
          </div>

          <div>
            <label className="field-label" htmlFor="venue">장소</label>
            <div style={{ display: 'flex', gap: 'var(--sp-3)', alignItems: 'center' }}>
              <input
                id="venue" className="input" value={venueName} disabled={venueUndetermined}
                onChange={(e) => setVenueName(e.target.value)}
                onBlur={() => patch({ venue: { name: venueName, is_undetermined: venueUndetermined } })}
                placeholder="예: 예술의전당 리사이틀홀"
              />
              <label style={{ display: 'flex', gap: 'var(--sp-1)', alignItems: 'center', whiteSpace: 'nowrap' }}>
                <input
                  type="checkbox" checked={venueUndetermined}
                  onChange={(e) => {
                    setVenueUndetermined(e.target.checked);
                    patch({ venue: { name: venueName, is_undetermined: e.target.checked } });
                  }}
                />
                <span className="body-sm">장소 미정</span>
              </label>
            </div>
            {venueUndetermined && <span className="badge badge-neutral" style={{ marginTop: 'var(--sp-2)' }}>장소 추후 공지</span>}
          </div>

          <div>
            <label className="field-label" htmlFor="date">날짜</label>
            <div style={{ display: 'flex', gap: 'var(--sp-3)', alignItems: 'center' }}>
              <input
                id="date" type="date" className="input" value={date ?? ''} disabled={dateUndetermined}
                onChange={(e) => setDate(e.target.value)}
                onBlur={() => patch({ sessions: [{ date, is_undetermined: dateUndetermined }] })}
              />
              <label style={{ display: 'flex', gap: 'var(--sp-1)', alignItems: 'center', whiteSpace: 'nowrap' }}>
                <input
                  type="checkbox" checked={dateUndetermined}
                  onChange={(e) => {
                    setDateUndetermined(e.target.checked);
                    patch({ sessions: [{ date, is_undetermined: e.target.checked }] });
                  }}
                />
                <span className="body-sm">날짜 미정</span>
              </label>
            </div>
            {dateUndetermined && <span className="badge badge-neutral" style={{ marginTop: 'var(--sp-2)' }}>일정 추후 공지</span>}
          </div>
        </div>

        <div style={{ marginTop: 'var(--sp-6)', display: 'flex', justifyContent: 'flex-end' }}>
          <button className="btn btn-primary" disabled={!canProceed} onClick={next} title={!canProceed ? '필수 4항목을 채워주세요' : undefined}>
            다음
          </button>
        </div>
      </div>
    </div>
  );
}
