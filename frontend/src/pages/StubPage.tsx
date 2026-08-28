import { Link, useParams } from 'react-router-dom';
import { Header } from '../components/Header';

/** 아직 구현 전인 화면의 자리표시자(⑤·⑦ 등 이후 티켓에서 채운다). */
export default function StubPage({ title }: { title: string }) {
  const { id } = useParams<{ id: string }>();
  return (
    <div>
      <Header />
      <div className="page" style={{ textAlign: 'center' }}>
        <h1 className="h1" style={{ marginBottom: 'var(--sp-3)' }}>{title}</h1>
        <p className="body-sm" style={{ marginBottom: 'var(--sp-5)' }}>이 화면은 다음 작업에서 이어서 만듭니다.</p>
        <Link to="/home" className="btn btn-secondary">홈으로</Link>
        {id && <span style={{ marginLeft: 8 }} className="caption">project: {id}</span>}
      </div>
    </div>
  );
}
