import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api } from '../lib/api';

interface AdminAccount {
  id: string; email: string; role: string; status: string; credit_balance: number; created_at: string;
}
interface AdminJob {
  id: string; kind: string; status: string; error: string | null; attempts: number; created_at: string;
}

/** 관리자 백오피스(1-20). role=admin만 실제로 데이터를 볼 수 있다(서버가 403/404로 막음). */
export default function AdminPage() {
  const queryClient = useQueryClient();
  const [q, setQ] = useState('');
  const [grantAmount, setGrantAmount] = useState<Record<string, string>>({});

  const { data: accounts } = useQuery({
    queryKey: ['admin', 'accounts', q],
    queryFn: () => api.get<{ items: AdminAccount[] }>(`/admin/accounts${q ? `?q=${encodeURIComponent(q)}` : ''}`),
  });
  const { data: jobs } = useQuery({
    queryKey: ['admin', 'jobs'],
    queryFn: () => api.get<{ items: AdminJob[] }>('/admin/jobs'),
  });

  const grant = useMutation({
    mutationFn: ({ id, amount }: { id: string; amount: number }) =>
      api.post(`/admin/accounts/${id}/credits`, { amount, reason: '관리자 지급' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'accounts'] }),
  });

  const retry = useMutation({
    mutationFn: (id: string) => api.post(`/admin/jobs/${id}/retry`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'jobs'] }),
  });

  return (
    <div>
      <Header />
      <div className="page">
        <h1 className="h1" style={{ marginBottom: 'var(--sp-6)' }}>관리자 백오피스</h1>

        <section style={{ marginBottom: 'var(--sp-8)' }}>
          <h2 className="h2" style={{ marginBottom: 'var(--sp-3)' }}>계정</h2>
          <input className="input" placeholder="이메일 검색" style={{ maxWidth: 280, marginBottom: 'var(--sp-3)' }}
                 value={q} onChange={(e) => setQ(e.target.value)} />
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr className="body-sm">
                <th style={{ textAlign: 'left', padding: 8 }}>이메일</th>
                <th style={{ textAlign: 'left', padding: 8 }}>역할</th>
                <th style={{ textAlign: 'left', padding: 8 }}>상태</th>
                <th style={{ textAlign: 'right', padding: 8 }}>크레딧</th>
                <th style={{ padding: 8 }}></th>
              </tr>
            </thead>
            <tbody>
              {accounts?.items.map((a) => (
                <tr key={a.id} className="body-sm" style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={{ padding: 8 }}>{a.email}</td>
                  <td style={{ padding: 8 }}>{a.role}</td>
                  <td style={{ padding: 8 }}>{a.status}</td>
                  <td style={{ padding: 8, textAlign: 'right' }} className="tabular">{a.credit_balance.toLocaleString()}</td>
                  <td style={{ padding: 8, display: 'flex', gap: 4 }}>
                    <input className="input" style={{ width: 90, height: 30 }} placeholder="+금액"
                           value={grantAmount[a.id] ?? ''}
                           onChange={(e) => setGrantAmount({ ...grantAmount, [a.id]: e.target.value })} />
                    <button className="btn btn-secondary btn-sm" onClick={() => {
                      const amount = Number(grantAmount[a.id]);
                      if (amount) grant.mutate({ id: a.id, amount });
                    }}>지급</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>

        <section>
          <h2 className="h2" style={{ marginBottom: 'var(--sp-3)' }}>실패·대기 작업</h2>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr className="body-sm">
                <th style={{ textAlign: 'left', padding: 8 }}>종류</th>
                <th style={{ textAlign: 'left', padding: 8 }}>상태</th>
                <th style={{ textAlign: 'left', padding: 8 }}>오류</th>
                <th style={{ padding: 8 }}></th>
              </tr>
            </thead>
            <tbody>
              {jobs?.items.map((j) => (
                <tr key={j.id} className="body-sm" style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={{ padding: 8 }}>{j.kind}</td>
                  <td style={{ padding: 8 }}>
                    <span className={j.status === 'failed' ? 'badge badge-error' : 'badge badge-neutral'}>{j.status}</span>
                  </td>
                  <td style={{ padding: 8 }}>{j.error}</td>
                  <td style={{ padding: 8 }}>
                    {j.status === 'failed' && (
                      <button className="btn btn-tertiary btn-sm" onClick={() => retry.mutate(j.id)}>재시도</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </div>
    </div>
  );
}
