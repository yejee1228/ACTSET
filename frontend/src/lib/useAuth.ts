import { useQuery } from '@tanstack/react-query';
import { api, Account, ApiError } from './api';

/** GET /auth/me — 미인증이면 401 → account undefined로 처리(docs/11). */
export function useAuth() {
  const query = useQuery<Account | null>({
    queryKey: ['auth', 'me'],
    queryFn: async () => {
      try {
        return await api.get<Account>('/auth/me');
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null;
        throw e;
      }
    },
  });
  return { account: query.data ?? null, isLoading: query.isLoading, refetch: query.refetch };
}
