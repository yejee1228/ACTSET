import { useRef, useState } from 'react';
import { api } from '../lib/api';

interface Props {
  projectId: string;
  kind: 'performance_photo' | 'cast_photo' | 'logo' | 'reference_image';
  onUploaded: (fileId: string, url: string) => void;
  label: string;
}

/**
 * ②의 업로드 슬롯. kind에 따라 안내 문구가 갈린다(docs/04·05·15) —
 * cast_photo·performance_photo·logo는 "외부로 전송되지 않는다"는 신뢰 문구,
 * reference_image만 "스타일 분석을 위해 전송된다"는 별도 경고.
 */
export function FileUploadField({ projectId, kind, onUploaded, label }: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const notice =
    kind === 'reference_image'
      ? '스타일 분석을 위해 외부 AI로 전송됩니다. 인물 사진은 넣지 말아주세요.'
      : '사진은 외부 AI로 전송되지 않고 홍보물에 그대로 배치됩니다.';

  async function onChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploading(true);
    setError(null);
    try {
      const form = new FormData();
      form.append('file', file);
      form.append('kind', kind);
      const csrfToken = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)?.[1];
      const res = await fetch(`/api/v1/projects/${projectId}/files`, {
        method: 'POST',
        credentials: 'include',
        headers: csrfToken ? { 'X-XSRF-TOKEN': decodeURIComponent(csrfToken) } : undefined,
        body: form,
      });
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        throw new Error(body?.error?.message ?? '업로드에 실패했습니다.');
      }
      const data = await res.json();
      onUploaded(data.id, data.url);
    } catch (err) {
      setError(err instanceof Error ? err.message : '업로드에 실패했습니다.');
    } finally {
      setUploading(false);
      if (inputRef.current) inputRef.current.value = '';
    }
  }

  return (
    <div>
      <label className="field-label">{label}</label>
      <input ref={inputRef} type="file" accept="image/jpeg,image/png" onChange={onChange} disabled={uploading} />
      <p className="caption" style={{ color: kind === 'reference_image' ? 'var(--warning)' : 'var(--gray-warm)' }}>
        {notice}
      </p>
      {uploading && <p className="caption">업로드 중…</p>}
      {error && <p className="caption" style={{ color: 'var(--error)' }}>{error}</p>}
    </div>
  );
}
