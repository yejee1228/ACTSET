import { useQuery } from '@tanstack/react-query';
import { api } from '../lib/api';

/** 점검 배너(1-24). 서버가 maintenance=true를 내려주면 화면 상단에 고정 노출한다. */
export function MaintenanceBanner() {
  const { data } = useQuery({
    queryKey: ['system', 'status'],
    queryFn: () => api.get<{ maintenance: boolean; message: string }>('/system/status'),
    refetchInterval: 60_000,
  });

  if (!data?.maintenance) return null;

  return (
    <div style={{ background: 'var(--warning-bg)', color: 'var(--warning)', textAlign: 'center', padding: 'var(--sp-2)' }} className="body-sm">
      {data.message}
    </div>
  );
}
