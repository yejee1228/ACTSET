import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/useAuth';

/** 계정 설정 화면(1-19). 프로필·비밀번호 변경, 탈퇴 진입(1-4b) — 삭제 범위를 화면에서 고지한다. */
export default function AccountSettingsPage() {
  const { account } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [displayName, setDisplayName] = useState('');
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [profileMsg, setProfileMsg] = useState<string | null>(null);
  const [passwordMsg, setPasswordMsg] = useState<string | null>(null);
  const [withdrawConfirming, setWithdrawConfirming] = useState(false);

  const updateProfile = useMutation({
    mutationFn: () => api.patch('/account', { display_name: displayName }),
    onSuccess: () => setProfileMsg('저장됐어요.'),
    onError: (e) => setProfileMsg(e instanceof ApiError ? e.message : '저장에 실패했습니다.'),
  });

  const changePassword = useMutation({
    mutationFn: () => api.post('/account/password', { current_password: currentPassword, new_password: newPassword }),
    onSuccess: () => { setPasswordMsg('비밀번호가 변경됐어요.'); setCurrentPassword(''); setNewPassword(''); },
    onError: (e) => setPasswordMsg(e instanceof ApiError ? e.message : '변경에 실패했습니다.'),
  });

  const withdraw = useMutation({
    mutationFn: () => api.del('/account'),
    onSuccess: () => { queryClient.setQueryData(['auth', 'me'], null); navigate('/'); },
  });

  function onProfileSubmit(e: FormEvent) {
    e.preventDefault();
    updateProfile.mutate();
  }

  function onPasswordSubmit(e: FormEvent) {
    e.preventDefault();
    changePassword.mutate();
  }

  return (
    <div>
      <Header />
      <div className="page" style={{ maxWidth: 480 }}>
        <h1 className="h1" style={{ marginBottom: 'var(--sp-6)' }}>계정 설정</h1>

        <div className="card" style={{ padding: 'var(--sp-6)', marginBottom: 'var(--sp-5)' }}>
          <p className="body-sm" style={{ marginBottom: 'var(--sp-4)' }}>{account?.email}</p>
          <form onSubmit={onProfileSubmit} style={{ display: 'grid', gap: 'var(--sp-3)' }}>
            <div>
              <label className="field-label">표시 이름</label>
              <input className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
            </div>
            {profileMsg && <p className="caption">{profileMsg}</p>}
            <button className="btn btn-secondary btn-md" type="submit" disabled={updateProfile.isPending}>저장</button>
          </form>
        </div>

        <div className="card" style={{ padding: 'var(--sp-6)', marginBottom: 'var(--sp-5)' }}>
          <h3 className="h3" style={{ marginBottom: 'var(--sp-4)' }}>비밀번호 변경</h3>
          <form onSubmit={onPasswordSubmit} style={{ display: 'grid', gap: 'var(--sp-3)' }}>
            <input className="input" type="password" placeholder="현재 비밀번호" value={currentPassword}
                   onChange={(e) => setCurrentPassword(e.target.value)} required />
            <input className="input" type="password" placeholder="새 비밀번호(8자 이상)" minLength={8} value={newPassword}
                   onChange={(e) => setNewPassword(e.target.value)} required />
            {passwordMsg && <p className="caption">{passwordMsg}</p>}
            <button className="btn btn-secondary btn-md" type="submit" disabled={changePassword.isPending}>변경</button>
          </form>
        </div>

        <div className="card" style={{ padding: 'var(--sp-6)', borderColor: 'var(--error)' }}>
          <h3 className="h3" style={{ marginBottom: 'var(--sp-2)' }}>회원 탈퇴</h3>
          <p className="body-sm" style={{ marginBottom: 'var(--sp-4)' }}>
            탈퇴하면 계정·프로젝트·업로드 파일·생성물이 모두 삭제됩니다. 선택 기록은 개인 식별 정보를
            지운 채 통계 목적으로만 남습니다. 이 작업은 되돌릴 수 없습니다.
          </p>
          {!withdrawConfirming ? (
            <button className="btn btn-destructive" onClick={() => setWithdrawConfirming(true)}>탈퇴하기</button>
          ) : (
            <div style={{ display: 'flex', gap: 'var(--sp-3)' }}>
              <button className="btn btn-secondary" onClick={() => setWithdrawConfirming(false)}>취소</button>
              <button className="btn btn-destructive" onClick={() => withdraw.mutate()} disabled={withdraw.isPending}>
                정말 탈퇴합니다
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
