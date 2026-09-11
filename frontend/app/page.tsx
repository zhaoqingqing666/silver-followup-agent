'use client';

import { useEffect, useState } from 'react';
import { MobileShell } from '@/components/layout/mobile-shell';
import { BottomNav } from '@/components/navigation/bottom-nav';
import { AssistantView } from '@/features/assistant/assistant-view';
import { HomeView } from '@/features/home/home-view';
import { ProfileView } from '@/features/profile/profile-view';
import { TasksView } from '@/features/tasks/tasks-view';
import { getVoicePreference, updateVoicePreference } from '@/lib/appointment-api';
import type { TabId, VoicePreference } from '@/types/domain';

export default function HomePage() {
  const [activeTab, setActiveTab] = useState<TabId>('home');
  const [largeText, setLargeText] = useState(false);
  const [voicePreference, setVoicePreference] = useState<VoicePreference>({
    userId: 'user-001', autoSpeakEnabled: false, speechRate: 0.9, speechVolume: 1,
  });
  const [voicePreferenceBusy, setVoicePreferenceBusy] = useState(false);
  const [voicePreferenceError, setVoicePreferenceError] = useState('');

  useEffect(() => {
    getVoicePreference().then(setVoicePreference).catch(() => {
      setVoicePreferenceError('后端未连接，暂时使用关闭状态');
    });
  }, []);

  const changeAutoSpeak = async (enabled: boolean) => {
    const previous = voicePreference;
    setVoicePreference({ ...previous, autoSpeakEnabled: enabled });
    setVoicePreferenceBusy(true);
    setVoicePreferenceError('');
    try {
      setVoicePreference(await updateVoicePreference(enabled));
    } catch {
      setVoicePreference(previous);
      setVoicePreferenceError('没有保存成功，请确认后端已经启动');
    } finally {
      setVoicePreferenceBusy(false);
    }
  };
  return <MobileShell largeText={largeText}>
    {activeTab === 'home' && <HomeView onNavigate={setActiveTab} />}
    {activeTab === 'tasks' && <TasksView onNavigate={setActiveTab} />}
    {activeTab === 'assistant' && <AssistantView onNavigate={setActiveTab} autoSpeakEnabled={voicePreference.autoSpeakEnabled} />}
    {activeTab === 'profile' && <ProfileView onNavigate={setActiveTab} largeText={largeText} onLargeTextChange={setLargeText}
      autoSpeakEnabled={voicePreference.autoSpeakEnabled} voicePreferenceBusy={voicePreferenceBusy}
      voicePreferenceError={voicePreferenceError} onAutoSpeakChange={value => void changeAutoSpeak(value)} />}
    <BottomNav activeTab={activeTab} onChange={setActiveTab} />
  </MobileShell>;
}
