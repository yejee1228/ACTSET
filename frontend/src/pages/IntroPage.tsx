import { useEffect } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '../lib/useAuth';
import { trackFunnelStep } from '../lib/funnel';

/** 0-A 소개 페이지(docs/03·04). 로그인 사용자는 0-B로 자동 이동. */
export default function IntroPage() {
  const { account, isLoading } = useAuth();
  useEffect(() => { trackFunnelStep('visit'); }, []);
  if (!isLoading && account) return <Navigate to="/home" replace />;

  return (
    <div>
      <header style={{ padding: '0 var(--sp-6)', height: 64, display: 'flex', alignItems: 'center' }}>
        <span style={{ fontWeight: 700, fontSize: 18 }}>ACTSET</span>
      </header>
      <section
        style={{
          background: 'var(--orange-soft)',
          padding: 'var(--sp-16) var(--sp-6)',
          textAlign: 'center',
        }}
      >
        <h1 className="h1" style={{ fontSize: 32, marginBottom: 'var(--sp-4)' }}>
          공연 정보 하나로, 12종 홍보물을 한 번에
        </h1>
        <p className="body-sm" style={{ fontSize: 16, color: 'var(--charcoal)', maxWidth: 560, margin: '0 auto var(--sp-8)' }}>
          포스터 시안을 생성하고, 확정된 시안을 예매처·SNS·현수막까지 규격별로 일괄 변환합니다.
        </p>
        <div style={{ display: 'flex', gap: 'var(--sp-3)', justifyContent: 'center' }}>
          <Link to="/signup" className="btn btn-primary">
            무료로 시작하기
          </Link>
          <Link to="/login" className="btn btn-secondary">
            로그인
          </Link>
        </div>
      </section>
      <section className="page" style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 'var(--sp-6)' }}>
        {[
          { t: '① 공연정보 입력', d: '장르·공연명·일정·장소만 있으면 시작할 수 있어요.' },
          { t: '③ 시안 선택', d: 'AI가 만든 후보 중 마음에 드는 방향을 고르세요.' },
          { t: '⑤ 규격 일괄변환', d: 'SNS·예매처·현수막까지 12종을 한 번에 만들어요.' },
        ].map((f) => (
          <div key={f.t} className="card" style={{ padding: 'var(--sp-5)' }}>
            <h3 className="h3" style={{ marginBottom: 'var(--sp-2)' }}>{f.t}</h3>
            <p className="body-sm">{f.d}</p>
          </div>
        ))}
      </section>
    </div>
  );
}
