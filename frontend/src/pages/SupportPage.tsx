import { useEffect, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { api } from '../lib/api';
import { trackFunnelStep } from '../lib/funnel';

/** 6-5 피드백 수집(인터뷰 접점) + 6-5b 고객 문의 창구. 서로 다른 목적의 별개 폼(docs/13). */
export default function SupportPage() {
  useEffect(() => { trackFunnelStep('support'); }, []);
  const [fbMessage, setFbMessage] = useState('');
  const [fbContact, setFbContact] = useState('');
  const [inqSubject, setInqSubject] = useState('');
  const [inqMessage, setInqMessage] = useState('');
  const [inqContact, setInqContact] = useState('');

  const submitFeedback = useMutation({
    mutationFn: () => api.post('/feedback', { message: fbMessage, contact: fbContact }),
    onSuccess: () => { setFbMessage(''); setFbContact(''); },
  });

  const submitInquiry = useMutation({
    mutationFn: () => api.post('/inquiries', { subject: inqSubject, message: inqMessage, contact: inqContact }),
    onSuccess: () => { setInqSubject(''); setInqMessage(''); setInqContact(''); },
  });

  return (
    <div>
      <Header />
      <div className="page" style={{ maxWidth: 560, display: 'grid', gap: 'var(--sp-8)' }}>
        <div>
          <h1 className="h1" style={{ marginBottom: 'var(--sp-2)' }}>베타 이용 소감을 들려주세요</h1>
          <p className="body-sm" style={{ marginBottom: 'var(--sp-4)' }}>인터뷰 참여 의향이 있으시면 연락처를 남겨주세요.</p>
          <div className="card" style={{ padding: 'var(--sp-5)', display: 'grid', gap: 'var(--sp-3)' }}>
            <textarea className="textarea" placeholder="느낀 점, 불편했던 점, 원하는 기능…" value={fbMessage}
                      onChange={(e) => setFbMessage(e.target.value)} />
            <input className="input" placeholder="연락처(선택)" value={fbContact}
                   onChange={(e) => setFbContact(e.target.value)} />
            {submitFeedback.isSuccess && <p className="body-sm" style={{ color: 'var(--success)' }}>보내주셔서 감사합니다.</p>}
            <button className="btn btn-primary" disabled={!fbMessage.trim() || submitFeedback.isPending}
                    onClick={() => submitFeedback.mutate()}>보내기</button>
          </div>
        </div>

        <div>
          <h1 className="h1" style={{ marginBottom: 'var(--sp-2)' }}>문의하기</h1>
          <p className="body-sm" style={{ marginBottom: 'var(--sp-4)' }}>결제·삭제 등 처리가 필요한 문의는 여기로 남겨주세요.</p>
          <div className="card" style={{ padding: 'var(--sp-5)', display: 'grid', gap: 'var(--sp-3)' }}>
            <input className="input" placeholder="제목" value={inqSubject}
                   onChange={(e) => setInqSubject(e.target.value)} />
            <textarea className="textarea" placeholder="문의 내용" value={inqMessage}
                      onChange={(e) => setInqMessage(e.target.value)} />
            <input className="input" placeholder="회신받을 연락처" value={inqContact}
                   onChange={(e) => setInqContact(e.target.value)} />
            {submitInquiry.isSuccess && <p className="body-sm" style={{ color: 'var(--success)' }}>접수되었습니다. 확인 후 연락드릴게요.</p>}
            <button className="btn btn-primary" disabled={!inqSubject.trim() || !inqMessage.trim() || submitInquiry.isPending}
                    onClick={() => submitInquiry.mutate()}>문의 접수</button>
          </div>
        </div>
      </div>
    </div>
  );
}
