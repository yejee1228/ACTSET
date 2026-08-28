import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api } from '../lib/api';

interface ProjectListItem {
  id: string;
  main_title: string;
  genre: string | null;
  primary_date: string | null;
  date_undetermined: boolean;
  thumbnail_url: string | null;
  updated_at: string;
}

/** 0-B 홈 대시보드(1-6). active 프로젝트만 노출, 빈 상태·검색을 지원한다(docs/04). */
export default function HomePage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [q, setQ] = useState('');
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['projects', q],
    queryFn: () => api.get<{ items: ProjectListItem[] }>(`/projects${q ? `?q=${encodeURIComponent(q)}` : ''}`),
  });

  const createProject = useMutation({
    mutationFn: () => api.post<{ id: string }>('/projects'),
    onSuccess: (project) => navigate(`/projects/${project.id}/info`),
  });

  const deleteProject = useMutation({
    mutationFn: (id: string) => api.del(`/projects/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['projects'] }),
  });

  const items = data?.items ?? [];

  return (
    <div>
      <Header />
      <div className="page">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--sp-6)' }}>
          <h1 className="h1">내 프로젝트</h1>
          <button className="btn btn-primary" onClick={() => createProject.mutate()} disabled={createProject.isPending}>
            + 새 프로젝트 만들기
          </button>
        </div>

        <input
          className="input"
          placeholder="공연명 검색"
          style={{ maxWidth: 320, marginBottom: 'var(--sp-6)' }}
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />

        {isLoading && <p className="body-sm">불러오는 중…</p>}

        {!isLoading && items.length === 0 && (
          <div className="card" style={{ padding: 'var(--sp-16)', textAlign: 'center' }}>
            <div
              style={{
                width: 160, height: 213, margin: '0 auto var(--sp-5)',
                border: '2px dashed var(--border)', borderRadius: 'var(--r-lg)',
              }}
            />
            <p className="body-strong" style={{ marginBottom: 'var(--sp-4)' }}>첫 공연을 등록해보세요</p>
            <button className="btn btn-primary" onClick={() => createProject.mutate()}>
              + 새 프로젝트 만들기
            </button>
          </div>
        )}

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 'var(--sp-4)' }}>
          {items.map((p) => (
            <div key={p.id} className="card" style={{ overflow: 'hidden', position: 'relative' }}>
              <div
                onClick={() => navigate(`/projects/${p.id}/dashboard`)}
                style={{
                  aspectRatio: '3 / 4', background: p.thumbnail_url ? `url(${p.thumbnail_url}) center/cover` : 'var(--bg-hover)',
                  cursor: 'pointer',
                }}
              />
              <div style={{ padding: 'var(--sp-3)' }}>
                <h3 className="h3" style={{ marginBottom: 'var(--sp-1)' }}>{p.main_title || '(제목 없음)'}</h3>
                <p className="body-sm">
                  {p.date_undetermined ? '일정 미정' : p.primary_date ?? '-'} · {p.genre ?? '-'}
                </p>
              </div>
              <button
                className="btn btn-tertiary btn-sm"
                style={{ position: 'absolute', top: 4, right: 4 }}
                onClick={() => setMenuOpenId(menuOpenId === p.id ? null : p.id)}
              >
                ⋮
              </button>
              {menuOpenId === p.id && (
                <div className="card" style={{ position: 'absolute', top: 32, right: 4, zIndex: 1, padding: 'var(--sp-2)' }}>
                  <button
                    className="btn btn-destructive btn-sm"
                    style={{ width: '100%' }}
                    onClick={() => {
                      if (confirm(`'${p.main_title}' 프로젝트를 삭제할까요? 30일간 복구할 수 있습니다.`)) {
                        deleteProject.mutate(p.id);
                      }
                      setMenuOpenId(null);
                    }}
                  >
                    삭제
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
