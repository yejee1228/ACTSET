import { Link } from 'react-router-dom';

/** 404(1-24). 타인 프로젝트 접근도 서버가 404를 주므로 같은 화면을 재사용한다(docs/09). */
export default function NotFoundPage() {
  return (
    <div className="page" style={{ textAlign: 'center', paddingTop: 'var(--sp-16)' }}>
      <h1 className="h1" style={{ marginBottom: 'var(--sp-3)' }}>페이지를 찾을 수 없어요</h1>
      <p className="body-sm" style={{ marginBottom: 'var(--sp-6)' }}>
        주소가 잘못됐거나, 접근 권한이 없는 페이지일 수 있어요.
      </p>
      <Link to="/home" className="btn btn-primary">홈으로 돌아가기</Link>
    </div>
  );
}
