'use client';

import { useState } from 'react';
import { MobileShell } from '@/components/layout/mobile-shell';
import { BottomNav } from '@/components/navigation/bottom-nav';
import { DEMO_ACTOR } from '@/lib/app-config';
import { AssistantView } from '@/features/assistant/assistant-view';
import { HomeView } from '@/features/home/home-view';
import { ProfileView } from '@/features/profile/profile-view';
import { TasksView } from '@/features/tasks/tasks-view';
import { ActorPicker } from '@/features/care/actor-picker';
import { CareView } from '@/features/care/care-view';
import { HealthRecordListView } from '@/features/records/health-record-list-view';
import { MemoListView } from '@/features/records/memo-list-view';
import type { CareActor, RecordPage, TabId } from '@/types/domain';

export default function HomePage() {
  const [actor, setActor] = useState<CareActor | null>(DEMO_ACTOR ?? null);
  const [activeTab, setActiveTab] = useState<TabId>('home');
  const [largeText, setLargeText] = useState(false);
  // 首页点按钮进去的二级页，不是底部导航的一格：不占 tab，也就不进 TabId
  const [recordPage, setRecordPage] = useState<RecordPage | null>(null);

  if (!actor) {
    return <MobileShell largeText={false}><ActorPicker onPick={setActor} /></MobileShell>;
  }

  // 就诊人本人：现有老人端，逻辑与改动前一致。
  if (actor === 'ELDER') {
    // 二级页占满整屏、不显示底部导航：老人从底部溜走再回来会回到首页，容易以为自己点丢了
    if (recordPage) {
      const back = () => setRecordPage(null);
      const goAssistant = () => { setRecordPage(null); setActiveTab('assistant'); };
      return <MobileShell largeText={largeText}>
        {recordPage === 'records'
          ? <HealthRecordListView onBack={back} onGoAssistant={goAssistant} />
          : <MemoListView page={recordPage} onBack={back} onGoAssistant={goAssistant} />}
      </MobileShell>;
    }
    return <MobileShell largeText={largeText}>
      {activeTab === 'home' && <HomeView onNavigate={setActiveTab} onOpenPage={setRecordPage} />}
      {activeTab === 'tasks' && <TasksView onNavigate={setActiveTab} />}
      {activeTab === 'assistant' && <AssistantView onNavigate={setActiveTab} />}
      {activeTab === 'profile' && <ProfileView onNavigate={setActiveTab} largeText={largeText} onLargeTextChange={setLargeText} />}
      <BottomNav activeTab={activeTab} onChange={setActiveTab} />
    </MobileShell>;
  }

  // 家属 / 志愿者：协同照护端。
  return <MobileShell largeText={false}>
    <CareView actor={actor} onSwitchActor={() => setActor(null)} />
  </MobileShell>;
}
