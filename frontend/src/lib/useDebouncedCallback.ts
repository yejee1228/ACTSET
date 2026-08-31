import { useEffect, useRef } from 'react';

/** ②의 자동저장 디바운스에 쓴다(docs/09 "9개 그룹의 입력 상태와 자동저장 디바운스"). */
export function useDebouncedCallback<T extends (...args: never[]) => void>(fn: T, delayMs: number): T {
  const fnRef = useRef(fn);
  const timerRef = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    fnRef.current = fn;
  }, [fn]);

  useEffect(() => () => clearTimeout(timerRef.current), []);

  return ((...args: never[]) => {
    clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => fnRef.current(...args), delayMs);
  }) as T;
}
