import { Route, Routes } from 'react-router-dom';
import IntroPage from './pages/IntroPage';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import ForgotPasswordPage from './pages/ForgotPasswordPage';
import ResetPasswordPage from './pages/ResetPasswordPage';
import HomePage from './pages/HomePage';
import Step1InfoPage from './pages/Step1InfoPage';
import Step2AdditionalInfoPage from './pages/Step2AdditionalInfoPage';
import Step3DraftSelectionPage from './pages/Step3DraftSelectionPage';
import Step4ConfirmPage from './pages/Step4ConfirmPage';
import Step4bNextActionPage from './pages/Step4bNextActionPage';
import Step5FormatSelectionPage from './pages/Step5FormatSelectionPage';
import Step6RecomposeResultsPage from './pages/Step6RecomposeResultsPage';
import ProjectDashboardPage from './pages/ProjectDashboardPage';
import InfoEditPage from './pages/InfoEditPage';
import AccountSettingsPage from './pages/AccountSettingsPage';
import AdminPage from './pages/AdminPage';
import NotFoundPage from './pages/NotFoundPage';
import { RequireAuth } from './components/RequireAuth';
import { MaintenanceBanner } from './components/MaintenanceBanner';
import { ErrorBoundary } from './components/ErrorBoundary';

export default function App() {
  return (
    <ErrorBoundary>
      <MaintenanceBanner />
      <Routes>
        <Route path="/" element={<IntroPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />

        <Route path="/home" element={<RequireAuth><HomePage /></RequireAuth>} />
        <Route path="/account" element={<RequireAuth><AccountSettingsPage /></RequireAuth>} />
        <Route path="/admin" element={<RequireAuth><AdminPage /></RequireAuth>} />

        <Route path="/projects/:id/info" element={<RequireAuth><Step1InfoPage /></RequireAuth>} />
        <Route path="/projects/:id/additional" element={<RequireAuth><Step2AdditionalInfoPage /></RequireAuth>} />
        <Route path="/projects/:id/drafts" element={<RequireAuth><Step3DraftSelectionPage /></RequireAuth>} />
        <Route path="/projects/:id/confirm" element={<RequireAuth><Step4ConfirmPage /></RequireAuth>} />
        <Route path="/projects/:id/next" element={<RequireAuth><Step4bNextActionPage /></RequireAuth>} />
        <Route path="/projects/:id/dashboard" element={<RequireAuth><ProjectDashboardPage /></RequireAuth>} />
        <Route path="/projects/:id/edit" element={<RequireAuth><InfoEditPage /></RequireAuth>} />
        <Route path="/projects/:id/formats" element={<RequireAuth><Step5FormatSelectionPage /></RequireAuth>} />
        <Route path="/projects/:id/recompose-results" element={<RequireAuth><Step6RecomposeResultsPage /></RequireAuth>} />

        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </ErrorBoundary>
  );
}
