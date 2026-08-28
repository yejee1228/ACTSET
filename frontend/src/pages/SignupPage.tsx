import { FormEvent, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { api, ApiError, Account } from '../lib/api';

const TERMS_VERSION = '2026-08-28';

/** 가입 화면 — 필수·선택 동의 분리, 동의 이력 저장(1-4·1-18). */
export default function SignupPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [terms, setTerms] = useState(false);
  const [privacy, setPrivacy] = useState(false);
  const [marketing, setMarketing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const canSubmit = email.trim() !== '' && password.length >= 8 && terms && privacy && !submitting;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      const account = await api.post<Account>('/auth/signup', {
        email,
        password,
        agreements: { terms, privacy, marketing },
        terms_version: TERMS_VERSION,
      });
      queryClient.setQueryData(['auth', 'me'], account);
      navigate('/home');
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '가입에 실패했습니다.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="page" style={{ maxWidth: 420 }}>
      <h1 className="h1" style={{ marginBottom: 'var(--sp-6)' }}>회원가입</h1>
      <form onSubmit={onSubmit} className="card" style={{ padding: 'var(--sp-6)', display: 'grid', gap: 'var(--sp-4)' }}>
        <div>
          <label className="field-label" htmlFor="email">이메일</label>
          <input id="email" className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </div>
        <div>
          <label className="field-label" htmlFor="password">비밀번호</label>
          <input id="password" className="input" type="password" minLength={8} value={password}
                 onChange={(e) => setPassword(e.target.value)} required />
          <p className="caption">8자 이상</p>
        </div>
        <label style={{ display: 'flex', gap: 'var(--sp-2)', alignItems: 'center' }}>
          <input type="checkbox" checked={terms} onChange={(e) => setTerms(e.target.checked)} />
          <span className="body-sm">(필수) 이용약관에 동의합니다</span>
        </label>
        <label style={{ display: 'flex', gap: 'var(--sp-2)', alignItems: 'center' }}>
          <input type="checkbox" checked={privacy} onChange={(e) => setPrivacy(e.target.checked)} />
          <span className="body-sm">(필수) 개인정보 수집·이용에 동의합니다</span>
        </label>
        <label style={{ display: 'flex', gap: 'var(--sp-2)', alignItems: 'center' }}>
          <input type="checkbox" checked={marketing} onChange={(e) => setMarketing(e.target.checked)} />
          <span className="body-sm">(선택) 광고성 정보 수신에 동의합니다</span>
        </label>
        {error && <p className="body-sm" style={{ color: 'var(--error)' }}>{error}</p>}
        <button className="btn btn-primary" type="submit" disabled={!canSubmit}>
          가입하고 시작하기
        </button>
        <p className="body-sm">
          이미 계정이 있으신가요? <Link to="/login" style={{ color: 'var(--orange-text)' }}>로그인</Link>
        </p>
      </form>
    </div>
  );
}
