import { Component, ErrorInfo, ReactNode } from 'react';

interface Props { children: ReactNode }
interface State { error: Error | null }

/** 화면 렌더링 중 예외가 나도 흰 화면 대신 재시도 경로를 보여준다(1-24). */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('ErrorBoundary caught', error, info);
  }

  render() {
    if (this.state.error) {
      return (
        <div className="page" style={{ textAlign: 'center', paddingTop: 'var(--sp-16)' }}>
          <h1 className="h1" style={{ marginBottom: 'var(--sp-3)' }}>문제가 발생했어요</h1>
          <p className="body-sm" style={{ marginBottom: 'var(--sp-6)' }}>
            일시적인 오류일 수 있어요. 다시 시도해주세요.
          </p>
          <button className="btn btn-primary" onClick={() => window.location.reload()}>다시 시도</button>
        </div>
      );
    }
    return this.props.children;
  }
}
