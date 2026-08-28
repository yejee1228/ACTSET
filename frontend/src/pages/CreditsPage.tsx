import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api } from '../lib/api';
import { trackFunnelStep } from '../lib/funnel';

interface CreditTransactionDto {
  type: string;
  amount: number;
  balance_after: number;
  description: string | null;
  created_at: string;
}

const TYPE_LABELS: Record<string, string> = {
  grant: '지급',
  consume: '차감',
  refund: '환불',
};

/** 6-3 크레딧 잔액·이력 화면(docs/13). 생성 전 예상 소비량은 각 생성 화면에서 /credits/estimate로 보여준다. */
export default function CreditsPage() {
  useEffect(() => { trackFunnelStep('credits'); }, []);

  const { data } = useQuery({
    queryKey: ['credits'],
    queryFn: () => api.get<{ balance: number; recent: CreditTransactionDto[] }>('/credits'),
  });

  return (
    <div>
      <Header />
      <div className="page" style={{ maxWidth: 640 }}>
        <h1 className="h1" style={{ marginBottom: 'var(--sp-2)' }}>크레딧</h1>
        <p className="h1 tabular" style={{ color: 'var(--orange-text)', marginBottom: 'var(--sp-6)' }}>
          {data?.balance?.toLocaleString() ?? '-'} <span className="body-sm" style={{ color: 'var(--gray-warm)' }}>보유</span>
        </p>

        <h2 className="h2" style={{ marginBottom: 'var(--sp-3)' }}>이용 내역</h2>
        <div className="card">
          {(data?.recent ?? []).length === 0 && (
            <p className="body-sm" style={{ padding: 'var(--sp-4)' }}>아직 내역이 없어요.</p>
          )}
          {(data?.recent ?? []).map((t, i) => (
            <div key={i} style={{
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              padding: 'var(--sp-3) var(--sp-4)', borderBottom: '1px solid var(--border)',
            }}>
              <div>
                <p className="body-sm">{TYPE_LABELS[t.type] ?? t.type} {t.description ? `· ${t.description}` : ''}</p>
                <p className="caption" style={{ color: 'var(--gray-warm)' }}>{new Date(t.created_at).toLocaleString()}</p>
              </div>
              <p className="body-strong tabular" style={{ color: t.amount >= 0 ? 'var(--success)' : 'var(--error)' }}>
                {t.amount >= 0 ? '+' : ''}{t.amount.toLocaleString()}
              </p>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
