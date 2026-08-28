import { Link, useNavigate } from 'react-router-dom';
import { api } from '../lib/api';
import { useAuth } from '../lib/useAuth';
import { useQueryClient } from '@tanstack/react-query';

/** docs/16 7-1: 로고 미정 — 헤더는 "ACTSET" 워드텍스트(Pretendard 700, Charcoal). */
export function Header() {
  const { account } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  async function logout() {
    await api.post('/auth/logout');
    queryClient.setQueryData(['auth', 'me'], null);
    navigate('/');
  }

  return (
    <header
      style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '0 var(--sp-6)', height: 64, borderBottom: '1px solid var(--border)',
        background: 'var(--surface)',
      }}
    >
      <Link to="/home" style={{ fontWeight: 700, fontSize: 18, color: 'var(--charcoal)', textDecoration: 'none' }}>
        ACTSET
      </Link>
      {account && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--sp-4)' }}>
          <span className="body-sm tabular">
            크레딧 <strong className="body-strong" style={{ color: 'var(--orange-text)' }}>{account.credit_balance.toLocaleString()}</strong>
          </span>
          <Link to="/account" className="body-sm" style={{ color: 'var(--gray-warm)', textDecoration: 'none' }}>
            계정 설정
          </Link>
          <button className="btn btn-secondary btn-sm" onClick={logout}>
            로그아웃
          </button>
        </div>
      )}
    </header>
  );
}
