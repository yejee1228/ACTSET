import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, AssetItem, ProjectDetail } from '../lib/api';
import { trackFunnelStep } from '../lib/funnel';
import { useDebouncedCallback } from '../lib/useDebouncedCallback';

interface Warning { code: string; message: string }
interface DraftResult { id: string; estimated_price: number; warnings: Warning[] }

const PRESETS = [
  { label: 'A4', w: 210, h: 297 },
  { label: 'A3', w: 297, h: 420 },
  { label: '현수막(5m)', w: 5000, h: 700 },
];

/** ⑧ 인쇄 페이지(5-1·5-2). 결제·주문 접수는 MVP 범위 밖 — 버튼은 항상 비활성(docs/03). */
export default function PrintPage() {
  const { id } = useParams<{ id: string }>();
  const [acknowledged, setAcknowledged] = useState(false);

  useEffect(() => { trackFunnelStep('print'); }, []);

  const { data: project } = useQuery({
    queryKey: ['project', id],
    queryFn: () => api.get<ProjectDetail>(`/projects/${id}`),
    enabled: !!id,
  });

  const { data: assetData } = useQuery({
    queryKey: ['assets', id, 'all'],
    queryFn: () => api.get<{ items: AssetItem[] }>(`/projects/${id}/assets`),
    enabled: !!id,
  });

  const items = (assetData?.items ?? []).filter((a) => a.status !== '삭제됨' && a.status !== '보관');

  const [assetId, setAssetId] = useState('');
  const [widthMm, setWidthMm] = useState(297);
  const [heightMm, setHeightMm] = useState(420);
  const [quantity, setQuantity] = useState(1);
  const [paper, setPaper] = useState('광택');
  const [recipient, setRecipient] = useState('');
  const [phone, setPhone] = useState('');
  const [address, setAddress] = useState('');
  const [draft, setDraft] = useState<DraftResult | null>(null);

  useEffect(() => {
    if (!assetId && items.length > 0) setAssetId(items[0].id);
  }, [items, assetId]);

  const upsertDraft = useMutation({
    mutationFn: async () => {
      const body = {
        generated_asset_id: assetId,
        print_spec: { width_mm: widthMm, height_mm: heightMm, quantity, paper },
        shipping_address: { recipient, phone, address },
      };
      return draft
        ? api.patch<DraftResult>(`/print-drafts/${draft.id}`, body)
        : api.post<DraftResult>(`/projects/${id}/print-drafts`, body);
    },
    onSuccess: (res) => setDraft(res),
  });

  const debouncedRecalc = useDebouncedCallback(() => {
    if (assetId) upsertDraft.mutate();
  }, 500);

  useEffect(() => {
    debouncedRecalc();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assetId, widthMm, heightMm, quantity, paper]);

  const needsScheduleWarning = project?.flags.date_undetermined || project?.flags.venue_undetermined;

  if (needsScheduleWarning && !acknowledged) {
    return (
      <div>
        <Header />
        <div className="page" style={{ maxWidth: 420, textAlign: 'center' }}>
          <div className="card" style={{ padding: 'var(--sp-6)' }}>
            <h2 className="h2" style={{ marginBottom: 'var(--sp-3)' }}>일정이 아직 미정입니다</h2>
            <p className="body-sm" style={{ marginBottom: 'var(--sp-5)' }}>계속 진행하시겠습니까?</p>
            <button className="btn btn-primary" onClick={() => setAcknowledged(true)}>계속 진행</button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div>
      <Header />
      <div className="page" style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 'var(--sp-8)' }}>
        <div>
          <h1 className="h1" style={{ marginBottom: 'var(--sp-6)' }}>인쇄 주문 (준비 중)</h1>

          <div className="card" style={{ padding: 'var(--sp-6)', marginBottom: 'var(--sp-5)' }}>
            <label className="field-label">인쇄할 결과물</label>
            <select className="input" value={assetId} onChange={(e) => setAssetId(e.target.value)}>
              {items.map((a) => (
                <option key={a.id} value={a.id}>{a.category} · {a.format_code} ({a.width}×{a.height})</option>
              ))}
            </select>
          </div>

          <div className="card" style={{ padding: 'var(--sp-6)', marginBottom: 'var(--sp-5)' }}>
            <label className="field-label">인쇄 크기</label>
            <div style={{ display: 'flex', gap: 'var(--sp-2)', marginBottom: 'var(--sp-3)' }}>
              {PRESETS.map((p) => (
                <button key={p.label} className="btn btn-secondary btn-sm" onClick={() => { setWidthMm(p.w); setHeightMm(p.h); }}>
                  {p.label}
                </button>
              ))}
            </div>
            <div style={{ display: 'flex', gap: 'var(--sp-3)' }}>
              <input className="input" type="number" value={widthMm} onChange={(e) => setWidthMm(Number(e.target.value))} placeholder="가로(mm)" />
              <input className="input" type="number" value={heightMm} onChange={(e) => setHeightMm(Number(e.target.value))} placeholder="세로(mm)" />
            </div>
            <div style={{ display: 'flex', gap: 'var(--sp-3)', marginTop: 'var(--sp-3)' }}>
              <input className="input" type="number" min={1} value={quantity} onChange={(e) => setQuantity(Number(e.target.value))} placeholder="수량" />
              <select className="input" value={paper} onChange={(e) => setPaper(e.target.value)}>
                <option>광택</option><option>무광</option><option>패브릭</option>
              </select>
            </div>
          </div>

          <div className="card" style={{ padding: 'var(--sp-6)' }}>
            <label className="field-label">배송지</label>
            <div style={{ display: 'grid', gap: 'var(--sp-3)' }}>
              <input className="input" placeholder="수령인" value={recipient} onChange={(e) => setRecipient(e.target.value)} />
              <input className="input" placeholder="연락처" value={phone} onChange={(e) => setPhone(e.target.value)} />
              <input className="input" placeholder="주소" value={address} onChange={(e) => setAddress(e.target.value)} />
            </div>
          </div>
        </div>

        <div className="card" style={{ padding: 'var(--sp-6)', alignSelf: 'flex-start' }}>
          <h2 className="h2" style={{ marginBottom: 'var(--sp-4)' }}>예상금액</h2>
          <p className="h1 tabular" style={{ marginBottom: 'var(--sp-4)' }}>
            {draft ? draft.estimated_price.toLocaleString() : '-'}원
          </p>
          {draft?.warnings.map((w) => (
            <p key={w.code} className="body-sm" style={{ color: 'var(--warning)', marginBottom: 'var(--sp-2)' }}>⚠ {w.message}</p>
          ))}
          <button className="btn btn-primary" style={{ width: '100%', marginTop: 'var(--sp-4)' }} disabled title="곧 지원 예정">
            주문하기
          </button>
        </div>
      </div>
    </div>
  );
}
