import { api } from './api';

/** 6-7 퍼널 이벤트. 방문(비로그인)부터 추적해야 하므로 세션 단위 익명 ID를 쓴다. */
function getSessionId(): string {
  const key = 'actset_funnel_session_id';
  let id = localStorage.getItem(key);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(key, id);
  }
  return id;
}

/** 6-9: 최초 유입의 UTM 파라미터를 세션 동안 기억해 이후 이벤트에도 함께 보낸다. */
function captureUtmOnce() {
  const key = 'actset_utm';
  if (sessionStorage.getItem(key)) return;
  const params = new URLSearchParams(window.location.search);
  const utm = {
    utm_source: params.get('utm_source') ?? undefined,
    utm_medium: params.get('utm_medium') ?? undefined,
    utm_campaign: params.get('utm_campaign') ?? undefined,
  };
  if (utm.utm_source || utm.utm_medium || utm.utm_campaign) {
    sessionStorage.setItem(key, JSON.stringify(utm));
  }
}

function getUtm(): { utm_source?: string; utm_medium?: string; utm_campaign?: string } {
  captureUtmOnce();
  const raw = sessionStorage.getItem('actset_utm');
  return raw ? JSON.parse(raw) : {};
}

/** 실패해도 화면 동작을 막지 않는다 — fire-and-forget. */
export function trackFunnelStep(step: string) {
  api.post('/funnel-events', { session_id: getSessionId(), step, ...getUtm() }).catch(() => {});
}
