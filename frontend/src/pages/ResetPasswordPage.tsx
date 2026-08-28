import { FormEvent, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../lib/api';

/** 메일 링크로 도착하는 재설정 확정 화면(1-17). 토큰은 1회용이며 재사용 시 오류를 보여준다. */
export default function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const navigate = useNavigate();

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      await api.post('/auth/password-reset/confirm', { token, new_password: password });
      setDone(true);
      setTimeout(() => navigate('/login'), 1500);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '재설정에 실패했습니다.');
    }
  }

  return (
    <div className="page" style={{ maxWidth: 420 }}>
      <h1 className="h1" style={{ marginBottom: 'var(--sp-6)' }}>새 비밀번호 설정</h1>
      <div className="card" style={{ padding: 'var(--sp-6)' }}>
        {done ? (
          <p className="body-sm">비밀번호가 변경됐어요. 로그인 화면으로 이동합니다…</p>
        ) : (
          <form onSubmit={onSubmit} style={{ display: 'grid', gap: 'var(--sp-4)' }}>
            <input className="input" type="password" placeholder="새 비밀번호(8자 이상)" minLength={8}
                   value={password} onChange={(e) => setPassword(e.target.value)} required />
            {error && <p className="body-sm" style={{ color: 'var(--error)' }}>{error}</p>}
            <button className="btn btn-primary" type="submit">비밀번호 변경</button>
          </form>
        )}
      </div>
    </div>
  );
}
