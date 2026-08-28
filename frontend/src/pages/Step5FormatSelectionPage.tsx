import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, FormatPresetDto } from '../lib/api';

const GROUP_LABELS: Record<string, string> = {
  예매처: '예매처', 온라인: '온라인', 오프라인: '오프라인',
};

/** ⑤ 규격 선택 화면(3-1). 포스터 "원본 다시 만들기"는 3-7(재생성 경로) 준비 중이라 비활성. */
export default function Step5FormatSelectionPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const { data } = useQuery({
    queryKey: ['formats'],
    queryFn: () => api.get<{ items: FormatPresetDto[] }>('/formats'),
  });

  const requestRecompose = useMutation({
    mutationFn: () => api.post<{ job_id: string }>(`/projects/${id}/recompose`, {
      format_codes: Array.from(selected), variants_per_format: 3,
    }),
    onSuccess: (res) => navigate(`/projects/${id}/recompose-results?job=${res.job_id}`),
  });

  const items = (data?.items ?? []).filter((f) => f.code !== 'POSTER');
  const groups = ['예매처', '온라인', '오프라인'];

  function toggle(code: string) {
    const next = new Set(selected);
    next.has(code) ? next.delete(code) : next.add(code);
    setSelected(next);
  }

  return (
    <div>
      <Header />
      <div className="page">
        <h1 className="h1" style={{ marginBottom: 'var(--sp-2)' }}>어떤 규격이 필요하세요?</h1>
        <p className="body-sm" style={{ marginBottom: 'var(--sp-6)' }}>필요한 만큼 골라서 한 번에 만들 수 있어요.</p>

        <div className="card" style={{ padding: 'var(--sp-5)', marginBottom: 'var(--sp-6)', opacity: 0.6 }}>
          <h3 className="h3">원본 — 포스터 다시 만들기</h3>
          <p className="body-sm">이 기능은 곧 지원 예정입니다.</p>
        </div>

        {groups.map((group) => (
          <div key={group} style={{ marginBottom: 'var(--sp-6)' }}>
            <h2 className="h2" style={{ marginBottom: 'var(--sp-3)' }}>{GROUP_LABELS[group]}</h2>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 'var(--sp-3)' }}>
              {items.filter((f) => f.group === group).map((f) => (
                <label key={f.code} className="card" style={{
                  padding: 'var(--sp-4)', cursor: 'pointer',
                  borderColor: selected.has(f.code) ? 'var(--orange)' : 'var(--border)',
                  borderWidth: selected.has(f.code) ? 2 : 1,
                }}>
                  <input type="checkbox" checked={selected.has(f.code)} onChange={() => toggle(f.code)} style={{ marginRight: 8 }} />
                  <span className="body-strong">{f.label}</span>
                  <p className="caption tabular">{f.width} × {f.height}px</p>
                </label>
              ))}
            </div>
          </div>
        ))}

        <button className="btn btn-primary" disabled={selected.size === 0 || requestRecompose.isPending}
                onClick={() => requestRecompose.mutate()}>
          일괄변환 생성 ({selected.size}종)
        </button>
      </div>
    </div>
  );
}
