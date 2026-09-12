'use client';

import { useEffect, useState } from 'react';
import { CareBottomNav, type CareTabId } from '@/components/navigation/care-bottom-nav';
import { CARE_CID_BY_ROLE, getCareElders } from '@/lib/care-api';
import { getUserProfile } from '@/lib/appointment-api';
import type { CareElder, CareRole } from '@/types/domain';
import { CareAppointmentsView } from './care-appointments-view';
import { CareAssistantView } from './care-assistant-view';
import { CareBookingView } from './care-booking-view';
import { CareElderPickerView } from './care-elder-picker-view';
import { CareHomeView, type CareEntryKind } from './care-home-view';
import { CareInboxView } from './care-inbox-view';
import { CareElderInfoView } from './care-elder-info-view';
import { CareMyEldersView } from './care-my-elders-view';
import { CareProfileView } from './care-profile-view';
import { CareSettingsView } from './care-settings-view';
import { ElderTimelineView } from './elder-timeline-view';

interface CareViewProps {
  actor: CareRole;
  onSwitchActor: () => void;
}

const FALLBACK_NAME: Record<CareRole, string> = { FAMILY: '小丽', VOLUNTEER: '李阿姨' };

type CareSubPage = 'elders' | 'settings';

interface PickerState {
  kind: CareEntryKind;
  elders: CareElder[];
  loading: boolean;
  error: string;
}

interface DrillState {
  kind: CareEntryKind;
  elder: CareElder;
}

/** 协同照护端外壳：首页 / 消息 / 助手 / 我的（样式对齐就诊人端四栏）。 */
export function CareView({ actor, onSwitchActor }: CareViewProps) {
  const caregiverId = CARE_CID_BY_ROLE[actor];
  const [tab, setTab] = useState<CareTabId>('home');
  const [actorName, setActorName] = useState(FALLBACK_NAME[actor]);
  const [picker, setPicker] = useState<PickerState | null>(null);
  const [drill, setDrill] = useState<DrillState | null>(null);
  const [sub, setSub] = useState<CareSubPage | null>(null);

  useEffect(() => {
    getUserProfile(caregiverId)
      .then(user => setActorName(user.name))
      .catch(() => setActorName(FALLBACK_NAME[actor]));
  }, [caregiverId, actor]);

  const switchTab = (next: CareTabId) => {
    setDrill(null);
    setPicker(null);
    setSub(null);
    setTab(next);
  };
  const goHome = () => switchTab('home');

  /** 代约完成后回到首页（清除可能保留的长辈选择）。 */
  const finishBooking = () => {
    setDrill(null);
    setPicker(null);
    setSub(null);
    setTab('home');
  };

  /** 从首页点“就诊动态/复诊预约”：绑定一位长辈直接进入，多位先进选择页。 */
  const startFor = (kind: CareEntryKind) => {
    setDrill(null);
    setPicker({ kind, elders: [], loading: true, error: '' });
    void loadFor(kind);
  };

  const loadFor = async (kind: CareEntryKind) => {
    try {
      const list = await getCareElders(caregiverId);
      // 帮助预约是“为哪位长辈办”的动作：即使只绑一位也先进“选择长辈”，明确操作对象
      if (list.length === 1 && kind !== 'booking') {
        setPicker(null);
        setDrill({ kind, elder: list[0] });
      } else {
        setPicker({ kind, elders: list, loading: false, error: '' });
      }
    } catch (cause) {
      setPicker({ kind, elders: [], loading: false, error: cause instanceof Error ? cause.message : '无法读取协同的长辈' });
    }
  };

  const retryPicker = () => {
    if (!picker) return;
    const { kind } = picker;
    setPicker({ kind, elders: [], loading: true, error: '' });
    void loadFor(kind);
  };

  /** 进入长辈详情：保留当前 picker 状态（若存在），返回时回到上一级。 */
  const pickElder = (elder: CareElder) => {
    const kind = picker?.kind ?? 'timeline';
    setDrill({ kind, elder });
  };

  /** 从长辈详情返回：仅关闭详情。多长辈时回到保留着的选长辈列表，单长辈时回到首页。 */
  const backFromDrill = () => setDrill(null);

  const isRoot = !picker && !drill && !sub;

  return (
    <>
      {drill?.kind === 'timeline' && (
        <ElderTimelineView caregiverId={caregiverId} elder={drill.elder} onBack={backFromDrill} />
      )}
      {drill?.kind === 'appointments' && (
        <CareAppointmentsView
          caregiverId={caregiverId}
          elder={drill.elder}
          onBack={backFromDrill}
          onManageUpcoming={elder => setDrill({ kind: 'booking', elder })}
        />
      )}
      {drill?.kind === 'booking' && (
        <CareBookingView caregiverId={caregiverId} elder={drill.elder} onBack={backFromDrill} onFinished={finishBooking} />
      )}
      {drill?.kind === 'info' && (
        <CareElderInfoView
          elder={drill.elder}
          onBack={backFromDrill}
          onOpenTimeline={() => setDrill({ kind: 'timeline', elder: drill.elder })}
          onOpenAppointments={() => setDrill({ kind: 'appointments', elder: drill.elder })}
        />
      )}

      {picker && (
        <div className={drill ? 'hidden' : undefined}>
          <CareElderPickerView
            kind={picker.kind}
            caregiverId={caregiverId}
            elders={picker.elders}
            loading={picker.loading}
            error={picker.error}
            onPick={pickElder}
            onRetry={retryPicker}
            onBack={goHome}
          />
        </div>
      )}

      {sub === 'elders' && (
        <CareMyEldersView caregiverId={caregiverId} onBack={() => setSub(null)} />
      )}
      {sub === 'settings' && (
        <CareSettingsView onBack={() => setSub(null)} />
      )}

      {isRoot && tab === 'home' && (
        <CareHomeView caregiverId={caregiverId} onOpen={startFor} onOpenElder={(kind, elder) => setDrill({ kind, elder })} />
      )}
      {isRoot && tab === 'messages' && (
        <CareInboxView caregiverId={caregiverId} onBack={goHome} />
      )}
      {isRoot && tab === 'assistant' && (
        <CareAssistantView actor={actor} onBack={goHome} />
      )}
      {isRoot && tab === 'profile' && (
        <CareProfileView caregiverId={caregiverId} actorName={actorName} onSwitchActor={onSwitchActor} onBack={goHome} onOpenElders={() => setSub('elders')} onOpenSettings={() => setSub('settings')} />
      )}

      {isRoot && <CareBottomNav activeTab={tab} onChange={switchTab} />}
    </>
  );
}
