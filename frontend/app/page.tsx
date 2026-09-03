'use client';

import { useState } from 'react';
import { MobileShell } from '@/components/layout/mobile-shell';
import { BottomNav } from '@/components/navigation/bottom-nav';
import { AssistantView } from '@/features/assistant/assistant-view';
import { HomeView } from '@/features/home/home-view';
import { ProfileView } from '@/features/profile/profile-view';
import { TasksView } from '@/features/tasks/tasks-view';
import type { TabId } from '@/types/domain';

export default function HomePage() {
  const [activeTab, setActiveTab] = useState<TabId>('home');
  const [largeText, setLargeText] = useState(false);
  return <MobileShell largeText={largeText}>
    {activeTab === 'home' && <HomeView onNavigate={setActiveTab} />}
    {activeTab === 'tasks' && <TasksView onNavigate={setActiveTab} />}
    {activeTab === 'assistant' && <AssistantView onNavigate={setActiveTab} />}
    {activeTab === 'profile' && <ProfileView onNavigate={setActiveTab} largeText={largeText} onLargeTextChange={setLargeText} />}
    <BottomNav activeTab={activeTab} onChange={setActiveTab} />
  </MobileShell>;
}
