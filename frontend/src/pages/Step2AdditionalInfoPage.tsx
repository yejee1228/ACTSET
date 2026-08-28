import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { FileUploadField } from '../components/FileUploadField';
import { api, ProjectDetail } from '../lib/api';
import { useDebouncedCallback } from '../lib/useDebouncedCallback';

interface CastRow {
  cast_id: string;
  name: string;
  part: string;
  photo_file_id?: string;
}

const GROUPS = [
  '일정 상세', '부가 정보', '출연진', '가격', '주최 정보', '안내', '소개', '이미지·색상', '문구·이미지 방향',
] as const;

/** ② 추가정보 입력(1-8). 9개 그룹, 전부 스킵 가능, 필드 단위 자동저장(docs/04). */
export default function Step2AdditionalInfoPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [openGroup, setOpenGroup] = useState<string>(GROUPS[0]);

  const { data: project } = useQuery({
    queryKey: ['project', id],
    queryFn: () => api.get<ProjectDetail>(`/projects/${id}`),
    enabled: !!id,
  });

  const [runningTime, setRunningTime] = useState('');
  const [subtitle, setSubtitle] = useState('');
  const [age, setAge] = useState('');
  const [cast, setCast] = useState<CastRow[]>([]);
  const [priceLabel, setPriceLabel] = useState('');
  const [pricePrice, setPricePrice] = useState('');
  const [presenter, setPresenter] = useState('');
  const [organizer, setOrganizer] = useState('');
  const [phone, setPhone] = useState('');
  const [introduction, setIntroduction] = useState('');
  const [mandatoryNotices, setMandatoryNotices] = useState('');
  const [imageDirectionNote, setImageDirectionNote] = useState('');

  useEffect(() => {
    if (!project) return;
    const info = project.performance_info as Record<string, any>;
    setRunningTime(info.running_time ?? '');
    setSubtitle(info.subtitle ?? '');
    setAge(info.age ?? '');
    setCast(info.cast ?? []);
    setPriceLabel(info.price_items?.[0]?.label ?? '');
    setPricePrice(info.price_items?.[0]?.price?.toString() ?? '');
    setPresenter((info.organizer_group?.presenter ?? []).join(', '));
    setOrganizer((info.organizer_group?.organizer ?? []).join(', '));
    setPhone(info.inquiry?.전화 ?? '');
    setIntroduction(info.introduction ?? '');
    setMandatoryNotices((info.mandatory_notices ?? []).join(', '));
    setImageDirectionNote(info.image_direction_note ?? '');
    // eslint-disable-next-line react-hooks/exhaustive-deps
    // project 참조 전체에 의존한다(InfoEditPage에서 발견한 stale 캐시 버그와 동일 원인).
  }, [project]);

  const save = useMutation({ mutationFn: (partial: Record<string, unknown>) => api.patch(`/projects/${id}/info`, partial) });
  const debouncedSave = useDebouncedCallback((partial: Record<string, unknown>) => save.mutate(partial), 600);

  function goNext() {
    navigate(`/projects/${id}/drafts`);
  }

  return (
    <div>
      <Header />
      <div className="page" style={{ maxWidth: 720, display: 'grid', gridTemplateColumns: '200px 1fr', gap: 'var(--sp-6)' }}>
        <nav style={{ display: 'flex', flexDirection: 'column', gap: 'var(--sp-1)' }}>
          {GROUPS.map((g) => (
            <button
              key={g}
              className={g === openGroup ? 'btn btn-tertiary btn-sm' : 'btn btn-secondary btn-sm'}
              style={{ justifyContent: 'flex-start' }}
              onClick={() => setOpenGroup(g)}
            >
              {g}
            </button>
          ))}
        </nav>

        <div className="card" style={{ padding: 'var(--sp-6)' }}>
          <p className="caption" style={{ marginBottom: 'var(--sp-2)' }}>② · 추가정보 입력 (전부 건너뛰기 가능)</p>

          {openGroup === '일정 상세' && (
            <div>
              <label className="field-label">러닝타임</label>
              <input className="input" value={runningTime}
                     onChange={(e) => { setRunningTime(e.target.value); debouncedSave({ running_time: e.target.value }); }}
                     placeholder="예: 90분" />
            </div>
          )}

          {openGroup === '부가 정보' && (
            <div style={{ display: 'grid', gap: 'var(--sp-4)' }}>
              <div>
                <label className="field-label">부제</label>
                <input className="input" value={subtitle}
                       onChange={(e) => { setSubtitle(e.target.value); debouncedSave({ subtitle: e.target.value }); }} />
              </div>
              <div>
                <label className="field-label">관람연령</label>
                <input className="input" value={age}
                       onChange={(e) => { setAge(e.target.value); debouncedSave({ age: e.target.value }); }}
                       placeholder="예: 8세 이상 관람" />
              </div>
            </div>
          )}

          {openGroup === '출연진' && (
            <div style={{ display: 'grid', gap: 'var(--sp-4)' }}>
              {cast.map((c, i) => (
                <div key={c.cast_id} className="card" style={{ padding: 'var(--sp-4)', display: 'grid', gap: 'var(--sp-3)' }}>
                  <div style={{ display: 'flex', gap: 'var(--sp-3)' }}>
                    <input className="input" placeholder="이름" value={c.name}
                           onChange={(e) => {
                             const next = cast.map((r, idx) => idx === i ? { ...r, name: e.target.value } : r);
                             setCast(next); debouncedSave({ cast: next });
                           }} />
                    <input className="input" placeholder="역할(예: 바리톤)" value={c.part}
                           onChange={(e) => {
                             const next = cast.map((r, idx) => idx === i ? { ...r, part: e.target.value } : r);
                             setCast(next); debouncedSave({ cast: next });
                           }} />
                    <button className="btn btn-destructive btn-sm" onClick={() => {
                      const next = cast.filter((_, idx) => idx !== i);
                      setCast(next); save.mutate({ cast: next });
                    }}>삭제</button>
                  </div>
                  {id && (
                    <FileUploadField projectId={id} kind="cast_photo" label="출연진 사진"
                      onUploaded={(fileId) => {
                        const next = cast.map((r, idx) => idx === i ? { ...r, photo_file_id: fileId } : r);
                        setCast(next); save.mutate({ cast: next });
                      }} />
                  )}
                </div>
              ))}
              <button className="btn btn-secondary btn-sm" onClick={() => {
                const next = [...cast, { cast_id: crypto.randomUUID(), name: '', part: '' }];
                setCast(next);
              }}>+ 출연진 추가</button>
            </div>
          )}

          {openGroup === '가격' && (
            <div style={{ display: 'flex', gap: 'var(--sp-3)' }}>
              <input className="input" placeholder="등급(예: R석)" value={priceLabel}
                     onChange={(e) => { setPriceLabel(e.target.value); }}
                     onBlur={() => debouncedSave({ price_items: [{ item_id: '1', category: 'seat_tier', label: priceLabel, price: Number(pricePrice) || 0, order: 0 }] })} />
              <input className="input" type="number" placeholder="가격" value={pricePrice}
                     onChange={(e) => { setPricePrice(e.target.value); }}
                     onBlur={() => debouncedSave({ price_items: [{ item_id: '1', category: 'seat_tier', label: priceLabel, price: Number(pricePrice) || 0, order: 0 }] })} />
            </div>
          )}

          {openGroup === '주최 정보' && (
            <div style={{ display: 'grid', gap: 'var(--sp-4)' }}>
              <div>
                <label className="field-label">주최 (쉼표로 구분)</label>
                <input className="input" value={presenter}
                       onChange={(e) => setPresenter(e.target.value)}
                       onBlur={() => debouncedSave({ organizer_group: { presenter: presenter.split(',').map(s => s.trim()).filter(Boolean), organizer: organizer.split(',').map(s => s.trim()).filter(Boolean), sponsor: [] } })} />
              </div>
              <div>
                <label className="field-label">주관 (쉼표로 구분)</label>
                <input className="input" value={organizer}
                       onChange={(e) => setOrganizer(e.target.value)}
                       onBlur={() => debouncedSave({ organizer_group: { presenter: presenter.split(',').map(s => s.trim()).filter(Boolean), organizer: organizer.split(',').map(s => s.trim()).filter(Boolean), sponsor: [] } })} />
              </div>
            </div>
          )}

          {openGroup === '안내' && (
            <div>
              <label className="field-label">문의 전화</label>
              <input className="input" value={phone}
                     onChange={(e) => { setPhone(e.target.value); debouncedSave({ inquiry: { 전화: e.target.value } }); }} />
            </div>
          )}

          {openGroup === '소개' && (
            <div>
              <label className="field-label">한 줄 소개</label>
              <textarea className="textarea" value={introduction}
                        onChange={(e) => { setIntroduction(e.target.value); debouncedSave({ introduction: e.target.value }); }} />
            </div>
          )}

          {openGroup === '이미지·색상' && id && (
            <div style={{ display: 'grid', gap: 'var(--sp-5)' }}>
              <FileUploadField projectId={id} kind="performance_photo" label="공연 사진" onUploaded={() => {}} />
              <FileUploadField projectId={id} kind="logo" label="로고" onUploaded={() => {}} />
              <FileUploadField projectId={id} kind="reference_image" label="참고 이미지" onUploaded={() => {}} />
            </div>
          )}

          {openGroup === '문구·이미지 방향' && (
            <div style={{ display: 'grid', gap: 'var(--sp-4)' }}>
              <div>
                <label className="field-label">필수 안내문구 (쉼표로 구분)</label>
                <input className="input" value={mandatoryNotices}
                       onChange={(e) => setMandatoryNotices(e.target.value)}
                       onBlur={() => debouncedSave({ mandatory_notices: mandatoryNotices.split(',').map(s => s.trim()).filter(Boolean) })} />
              </div>
              <div>
                <label className="field-label">원하는 이미지 방향</label>
                <textarea className="textarea" value={imageDirectionNote}
                          placeholder="예: 차분한 블루톤, 인물 사진은 크게"
                          onChange={(e) => { setImageDirectionNote(e.target.value); debouncedSave({ image_direction_note: e.target.value }); }} />
              </div>
            </div>
          )}
        </div>
      </div>

      <div className="page" style={{ maxWidth: 720, display: 'flex', justifyContent: 'space-between' }}>
        <button className="btn btn-secondary" onClick={() => navigate(`/projects/${id}/info`)}>이전</button>
        <button className="btn btn-primary" onClick={goNext}>다음 — 시안 만들기</button>
      </div>
    </div>
  );
}
