import { useEffect, useRef, useState } from 'react';
import { JobStage } from '../lib/api';

interface Props {
  /** 작업 상태 — succeeded면 100%, failed면 멈춘 자리에서 빨간색 */
  status?: string;
  /** 단계형 작업(시안 생성·포스터 분석) */
  stage?: JobStage | null;
  /** 묶음 작업(규격별 변환) — 완료 수 기준 */
  batch?: { done: number; total: number };
  /** 아직 단계가 기록되기 전(대기열) 문구 */
  waitingLabel?: string;
}

/**
 * 작업 진행 바(시안 생성 ③·포스터 분석 U-1·규격 변환 ⑥).
 * 단계 전환은 서버가 기록한 실제 값이고, 단계 안에서는 예상 시간(expected_sec) 기준 추정으로 채운다
 * — 외부 AI 응답을 기다리는 동안 실제 진척은 알 수 없어서다. 단계 끝(to)의 95%를 넘지 않게 해 "멈춘 듯 100%"를 피한다.
 */
export function JobProgressBar({ status, stage, batch, waitingLabel = '작업을 준비하는 중' }: Props) {
  const [now, setNow] = useState(Date.now());
  const maxSeen = useRef(0);
  const done = status === 'succeeded';
  const failed = status === 'failed' || status === 'canceled';

  useEffect(() => {
    if (done || failed) return;
    const t = setInterval(() => setNow(Date.now()), 400);
    return () => clearInterval(t);
  }, [done, failed]);

  let pct = 2;
  let label = waitingLabel;
  let stepText: string | null = null;
  if (batch && batch.total > 0) {
    pct = Math.max(4, (batch.done / batch.total) * 100);
    label = batch.done < batch.total ? '규격별로 배치하고 합성하는 중' : '완료';
    stepText = `${batch.done}/${batch.total}개 규격`;
  } else if (stage) {
    const elapsed = Math.max(0, (now - Date.parse(stage.started_at)) / 1000);
    const ratio = Math.min(0.95, elapsed / Math.max(1, stage.expected_sec));
    pct = stage.from + (stage.to - stage.from) * ratio;
    label = stage.label;
    stepText = `단계 ${stage.step}/${stage.total}`;
  }
  if (done) { pct = 100; label = '완료'; }
  // 폴링 사이에 값이 뒤로 가지 않게
  maxSeen.current = Math.max(maxSeen.current, pct);
  const shown = Math.round(maxSeen.current);

  return (
    <div style={{ width: '100%' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 'var(--sp-3)', marginBottom: 'var(--sp-2)' }}>
        <span className="body-sm" style={{ color: failed ? 'var(--error)' : 'var(--charcoal)' }}>
          {failed ? '중단됨' : label}
        </span>
        <span className="caption tabular" style={{ color: 'var(--gray-warm)', whiteSpace: 'nowrap' }}>
          {stepText ? `${stepText} · ` : ''}{shown}%
        </span>
      </div>
      <div role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={shown} aria-label={label}
           style={{ height: 8, background: 'var(--bg-hover)', borderRadius: 'var(--r-full)', overflow: 'hidden' }}>
        <div style={{
          width: `${shown}%`, height: '100%', borderRadius: 'var(--r-full)',
          background: failed ? 'var(--error)' : 'var(--orange)', transition: 'width 0.4s ease',
        }} />
      </div>
    </div>
  );
}
