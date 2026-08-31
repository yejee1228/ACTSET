import { FormEvent, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { api, ApiError, Account } from '../lib/api';

export default function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const account = await api.post<Account>('/auth/login', { email, password });
      queryClient.setQueryData(['auth', 'me'], account);
      navigate('/home');
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '로그인에 실패했습니다.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="page" style={{ maxWidth: 420 }}>
      <h1 className="h1" style={{ marginBottom: 'var(--sp-6)' }}>로그인</h1>
      <form onSubmit={onSubmit} className="card" style={{ padding: 'var(--sp-6)', display: 'grid', gap: 'var(--sp-4)' }}>
        <div>
          <label className="field-label" htmlFor="email">이메일</label>
          <input id="email" className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </div>
        <div>
          <label className="field-label" htmlFor="password">비밀번호</label>
          <input id="password" className="input" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </div>
        {error && <p className="body-sm" style={{ color: 'var(--error)' }}>{error}</p>}
        <button className="btn btn-primary" type="submit" disabled={submitting}>로그인</button>
        <p className="body-sm">
          계정이 없으신가요? <Link to="/signup" style={{ color: 'var(--orange-text)' }}>회원가입</Link>
        </p>
        <p className="body-sm">
          <Link to="/forgot-password" style={{ color: 'var(--gray-warm)' }}>비밀번호를 잊으셨나요?</Link>
        </p>
      </form>
    </div>
  );
}
