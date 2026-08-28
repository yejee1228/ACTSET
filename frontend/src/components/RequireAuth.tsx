import { Navigate } from 'react-router-dom';
import { useAuth } from '../lib/useAuth';

export function RequireAuth({ children }: { children: React.ReactNode }) {
  const { account, isLoading } = useAuth();
  if (isLoading) return <div className="page">불러오는 중…</div>;
  if (!account) return <Navigate to="/" replace />;
  return <>{children}</>;
}
