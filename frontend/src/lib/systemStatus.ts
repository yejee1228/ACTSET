import { useQuery } from '@tanstack/react-query';
import { api } from './api';

export interface SystemStatus {
  maintenance: boolean;
  message: string;
  /** 인쇄 기능 켜짐 — 베타 서버는 꺼 둔다(메모리 부족, 2026-10-12 결정) */
  print_enabled: boolean;
}

/** 점검 배너·기능 켜짐 여부. 같은 키를 써서 화면 여러 곳이 한 번만 부른다. */
export function useSystemStatus() {
  return useQuery({
    queryKey: ['system', 'status'],
    queryFn: () => api.get<SystemStatus>('/system/status'),
    refetchInterval: 60_000,
  });
}
