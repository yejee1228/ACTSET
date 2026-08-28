import { FormEvent, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../lib/api';

/** 비밀번호 재설정 요청(1-17). 계정 존재 여부를 노출하지 않도록 항상 같은 안내를 보여준다. */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    await api.post('/auth/password-reset/request', { email });
    setSent(true);
  }

  return (
    <div className="page" style={{ maxWidth: 420 }}>
      <h1 className="h1" style={{ marginBottom: 'var(--sp-6)' }}>비밀번호 찾기</h1>
      <div className="card" style={{ padding: 'var(--sp-6)' }}>
        {sent ? (
          <p className="body-sm">입력하신 이메일이 가입되어 있다면 재설정 링크를 보냈습니다.</p>
        ) : (
          <form onSubmit={onSubmit} style={{ display: 'grid', gap: 'var(--sp-4)' }}>
            <div>
              <label className="field-label" htmlFor="email">가입한 이메일</label>
              <input id="email" className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </div>
            <button className="btn btn-primary" type="submit">재설정 링크 받기</button>
          </form>
        )}
        <p className="body-sm" style={{ marginTop: 'var(--sp-4)' }}>
          <Link to="/login" style={{ color: 'var(--orange-text)' }}>로그인으로 돌아가기</Link>
        </p>
      </div>
    </div>
  );
}
