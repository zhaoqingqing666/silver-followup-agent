'use client';

import { useEffect, useState } from 'react';
import { MobileShell } from '@/components/layout/mobile-shell';
import { BottomNav } from '@/components/navigation/bottom-nav';
import { AssistantView } from '@/features/assistant/assistant-view';
import { HomeView } from '@/features/home/home-view';
import { ProfileView } from '@/features/profile/profile-view';
import { TasksView } from '@/features/tasks/tasks-view';
import type { TabId } from '@/types/domain';
import { getVoicePreference, updateVoicePreference } from '@/lib/appointment-api';
import { DEFAULT_SPEECH_RATE, DEFAULT_SPEECH_VOLUME, DEMO_USER_ID } from '@/lib/app-config';
import type { VoicePreference } from '@/types/domain';

export default function HomePage() {
  const [activeTab, setActiveTab] = useState<TabId>('home');
  const [largeText, setLargeText] = useState(false);
  const [voicePreference, setVoicePreference] = useState<VoicePreference>({
    userId: DEMO_USER_ID, autoSpeakEnabled: false,
    speechRate: DEFAULT_SPEECH_RATE, speechVolume: DEFAULT_SPEECH_VOLUME,
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
    <div className={activeTab === 'assistant' ? 'block' : 'hidden'} aria-hidden={activeTab !== 'assistant'}>
      <AssistantView active={activeTab === 'assistant'} onNavigate={setActiveTab} voicePreference={voicePreference} />
    </div>
    {activeTab === 'profile' && <ProfileView onNavigate={setActiveTab} largeText={largeText} onLargeTextChange={setLargeText}
      autoSpeakEnabled={voicePreference.autoSpeakEnabled} voicePreferenceBusy={voicePreferenceBusy}
      voicePreferenceError={voicePreferenceError} onAutoSpeakChange={value => void changeAutoSpeak(value)} />}
    <BottomNav activeTab={activeTab} onChange={setActiveTab} />
  </MobileShell>;
}
