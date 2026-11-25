'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { MobileShell } from '@/components/layout/mobile-shell';
import { BottomNav } from '@/components/navigation/bottom-nav';
import { DEMO_ACTOR } from '@/lib/app-config';
import { AssistantView } from '@/features/assistant/assistant-view';
import { ActorPicker } from '@/features/care/actor-picker';
import { CareView } from '@/features/care/care-view';
import { HomeView } from '@/features/home/home-view';
import { ProfileView } from '@/features/profile/profile-view';
import { HealthRecordListView } from '@/features/records/health-record-list-view';
import { MemoListView } from '@/features/records/memo-list-view';
import { TasksView } from '@/features/tasks/tasks-view';
import { TravelGuideView } from '@/features/travel/travel-guide-view';
import { ElderVoiceDock } from '@/features/voice/elder-voice-dock';
import { ElderVoiceToasts } from '@/features/voice/elder-voice-toasts';
import { ElderVoiceProvider, useElderVoice } from '@/features/voice/elder-voice-provider';
import { getVoicePreference, updateVoicePreference } from '@/lib/appointment-api';
import { speakText, stopPlayback } from '@/lib/tts-player';
import type { CareActor, RecordPage, TabId, TravelFocus, VoicePreference } from '@/types/domain';

/** 开场提示气泡说的话。它指向的是屏幕上真实的那个麦克风按钮，不是比喻。 */
const GREETING_TEXT = '我是复诊小助手，有什么问题，长按下面的麦克风告诉我。';
/** 气泡自己收起的时间。老人点了别处、或者按住麦克风，都会比这更早。 */
const GREETING_MS = 9000;

export default function HomePage() {
  // 入口先选身份：就诊人本人走老人端，家属 / 志愿者走协同照护端。
  // DEMO_ACTOR 可在配置里钉死，演示时省掉手动选择这一步。
  const [actor, setActor] = useState<CareActor | null>(DEMO_ACTOR ?? null);

  if (!actor) {
    return <MobileShell largeText={false}><ActorPicker onPick={setActor} /></MobileShell>;
  }
  if (actor !== 'ELDER') {
    // 家属 / 志愿者：协同照护端。老人端的语音、地图和助手不在这一支里出现。
    return <MobileShell largeText={false}>
      <CareView actor={actor} onSwitchActor={() => setActor(null)} />
    </MobileShell>;
  }
  return <ElderApp />;
}

/**
 * 老人端。
 *
 * <p>语音与会话的能力全部收在 {@link ElderVoiceProvider} 里，它包着老人端的**所有**页面
 * （含首页点进去的记录、备忘二级页），所以：
 * <ul>
 *   <li>麦克风只在这里挂一次，任何页面都看得见、都能说话；</li>
 *   <li>老人端内部切页、进二级页再回来，Provider 都还在，会话不会丢；</li>
 *   <li>助手页只是这套能力的一个视图，不再自己持有一份请求状态。</li>
 * </ul>
 */
function ElderApp() {
  const [activeTab, setActiveTab] = useState<TabId>('home');
  // 首页点按钮进去的二级页，不是底部导航的一格：不占 tab，也就不进 TabId
  const [recordPage, setRecordPage] = useState<RecordPage | null>(null);
  const [travelAppointmentId, setTravelAppointmentId] = useState('');
  const [travelFocus, setTravelFocus] = useState<TravelFocus>('outside');
  // 每次打开地图页都换一个 key 重新挂载：既让 initialTab 生效，也让“这一轮要朗读”只消费一次。
  const [travelSession, setTravelSession] = useState(0);
  const [travelForceSpeak, setTravelForceSpeak] = useState(false);
  const [largeText, setLargeText] = useState(false);
  const [voicePreference, setVoicePreference] = useState<VoicePreference>({
    userId: 'user-001', autoSpeakEnabled: false, speechRate: 0.9, speechVolume: 1,
  });
  const [voicePreferenceBusy, setVoicePreferenceBusy] = useState(false);
  const [voicePreferenceError, setVoicePreferenceError] = useState('');
  /**
   * 语音偏好读回来了没有。开场气泡要不要念，取决于「自动朗读」这个开关，
   * 而它默认是关的、要等后端返回才知道真实值——不等就读，会在开关明明开着的时候不吭声。
   */
  const [voicePreferenceReady, setVoicePreferenceReady] = useState(false);
  /** 开场提示气泡：新进入老人端时显示一次，之后在应用内部切页不再弹。 */
  const [greetingVisible, setGreetingVisible] = useState(true);
  const greetingSpoken = useRef(false);
  /** 当前页面注册的只读语音口令（返回、重听、切换院内/院外）。没接住的才交给主智能体。 */
  const pageVoiceRef = useRef<((text: string) => boolean) | null>(null);
  /**
   * 别的页面交给助手去说的一句话（事项页的「取消这次复诊」）。
   *
   * 取消预约要落到数据库，而按本项目的约定，只有确认门禁能触发写操作——
   * 所以事项页不自己调删除接口，而是把这句话交给助手，照常出确认卡、
   * 由老人点「确认」才算数。这里只负责把话递过去并切到助手页。
   */
  const [pendingAsk, setPendingAsk] = useState('');

  useEffect(() => {
    let cancelled = false;
    getVoicePreference().then(
      preference => {
        if (cancelled) return;
        setVoicePreference(preference);
        setVoicePreferenceReady(true);
      },
      () => {
        if (cancelled) return;
        setVoicePreferenceError('后端未连接，暂时使用关闭状态');
        setVoicePreferenceReady(true);
      });
    return () => { cancelled = true; };
  }, []);

  /**
   * 保存语音偏好。三个字段共用一条通路：后端 PUT 是部分更新（未提供的字段保持原值），
   * 所以自动朗读开关和朗读设置里的语速不会互相覆盖。
   * 先在本地乐观更新，失败再整体回滚——老人拖完滑条要立刻看到数字变了。
   */
  const changeVoicePreference = async (patch: { autoSpeakEnabled?: boolean; speechRate?: number }) => {
    const previous = voicePreference;
    setVoicePreference({ ...previous, ...patch });
    setVoicePreferenceBusy(true);
    setVoicePreferenceError('');
    try {
      setVoicePreference(await updateVoicePreference(patch));
    } catch {
      setVoicePreference(previous);
      setVoicePreferenceError('没有保存成功，请确认后端已经启动');
    } finally {
      setVoicePreferenceBusy(false);
    }
  };
  const changeAutoSpeak = (enabled: boolean) => void changeVoicePreference({ autoSpeakEnabled: enabled });

  /**
   * 切到某一格。二级页要先退掉，否则它会一直占着整屏——老人点了底部导航却什么都没变，
   * 会以为按钮坏了。
   */
  const navigate = useCallback((tab: TabId) => {
    setRecordPage(null);
    setActiveTab(tab);
  }, []);

  /**
   * forceSpeak 只在“用户主动用语音问出来”的这一次打开时传 true：
   * 地图页要立刻把路线/院内指引读出来，即使自动朗读开关是关的。它不写回任何设置。
   */
  const openTravel = useCallback((appointmentId = '', focus: TravelFocus = 'outside',
                                  options?: { forceSpeak?: boolean }) => {
    setRecordPage(null);
    setTravelAppointmentId(appointmentId);
    setTravelFocus(focus);
    setTravelForceSpeak(options?.forceSpeak === true);
    setTravelSession(session => session + 1);
    setActiveTab('travel');
  }, []);
  const closeTravel = useCallback(() => setActiveTab('tasks'), []);
  const registerPageVoice = useCallback((handler: ((text: string) => boolean) | null) => {
    pageVoiceRef.current = handler;
  }, []);
  const askAssistant = useCallback((text: string) => {
    setActiveTab('assistant');
    setPendingAsk(text);
  }, []);
  const consumeAsk = useCallback(() => setPendingAsk(''), []);

  /**
   * 收掉开场气泡。点它本身、点页面上任何别的地方、按住麦克风，都会走到这里。
   * 同时停掉提示音：字消失了声音还在响，老人只会以为关不掉。
   */
  const dismissGreeting = useCallback(() => {
    setGreetingVisible(false);
    stopPlayback();
  }, []);

  /**
   * 气泡出现期间监听整页的 pointerdown，用捕获阶段：按住麦克风时它自己的处理还没跑完，
   * 这里就要先把气泡收掉，否则气泡会一直压在录音浮层上。超过时间也自己收。
   */
  useEffect(() => {
    if (!greetingVisible) return;
    const hide = () => dismissGreeting();
    document.addEventListener('pointerdown', hide, true);
    const timer = window.setTimeout(hide, GREETING_MS);
    return () => {
      document.removeEventListener('pointerdown', hide, true);
      window.clearTimeout(timer);
    };
  }, [greetingVisible, dismissGreeting]);

  /**
   * 念一遍开场白，遵守「自动朗读」开关与语速；关着就只显示文字。
   * greetingSpoken 保证只念一次——气泡因重渲染重新出现时不会再念第二遍。
   */
  useEffect(() => {
    if (!greetingVisible || !voicePreferenceReady) return;
    if (!voicePreference.autoSpeakEnabled || greetingSpoken.current) return;
    greetingSpoken.current = true;
    void speakText(`greeting-${Date.now()}`, GREETING_TEXT, {
      rate: voicePreference.speechRate, volume: voicePreference.speechVolume,
    });
  }, [greetingVisible, voicePreferenceReady, voicePreference]);

  const assistantVisible = activeTab === 'assistant' && recordPage === null;

  return <ElderVoiceProvider assistantVisible={assistantVisible} navigate={navigate}
    openTravel={openTravel} voicePreference={voicePreference}>
    <MobileShell largeText={largeText}>
      {/* 首页点进去的二级页占满整屏、不显示底部导航：老人从底部溜走再回来会回到首页，
          容易以为自己点丢了。**但它同样有全局麦克风**——麦克风在下面那一层，不属于任何一格。 */}
      {recordPage ? (recordPage === 'records'
        ? <HealthRecordListView onBack={() => setRecordPage(null)}
          onGoAssistant={() => { consumeAsk(); navigate('assistant'); }} />
        : <MemoListView page={recordPage} onBack={() => setRecordPage(null)}
          onGoAssistant={() => { consumeAsk(); navigate('assistant'); }} />) : <>
        {activeTab === 'home' && <HomeView onNavigate={navigate} onOpenTravel={openTravel}
          onOpenPage={setRecordPage} />}
        {activeTab === 'tasks' && <TasksView onNavigate={navigate} onOpenTravel={openTravel}
          voicePreference={voicePreference} onRegisterVoice={registerPageVoice} onAskAssistant={askAssistant} />}
        <div className={assistantVisible ? 'block' : 'hidden'} aria-hidden={!assistantVisible}>
          <AssistantView active={assistantVisible} onNavigate={navigate} onOpenTravel={openTravel}
            voicePreference={voicePreference} />
        </div>
        {activeTab === 'travel' && <TravelGuideView key={travelSession} appointmentId={travelAppointmentId}
          initialTab={travelFocus} forceSpeak={travelForceSpeak} voicePreference={voicePreference}
          onBack={closeTravel} onRegisterVoice={registerPageVoice} />}
        {activeTab === 'profile' && <ProfileView onNavigate={navigate} largeText={largeText} onLargeTextChange={setLargeText}
          autoSpeakEnabled={voicePreference.autoSpeakEnabled} voicePreferenceBusy={voicePreferenceBusy}
          voicePreferenceError={voicePreferenceError} onAutoSpeakChange={changeAutoSpeak}
          speechRate={voicePreference.speechRate} onSpeechRateChange={patch => void changeVoicePreference(patch)} />
        }
        {activeTab !== 'travel' && <BottomNav activeTab={activeTab} onChange={navigate} />}
      </>}

      {/* 别的页面递过来的一句话：切到助手页并替他发出去。
          在这里发而不是让助手页自己发，是因为助手页可能还没挂载过——
          递话这件事不该依赖「老人先去点过一次助手」。 */}
      <PendingAskBridge text={pendingAsk} onDone={consumeAsk} pageVoiceRef={pageVoiceRef} />

      {/* 开场提示气泡：每次新进入老人端显示一次，之后在应用内部切页不再弹。
          位置就在麦克风正上方（bottom-[132px] 是录音浮层用的同一档），不盖住那个按钮本身。 */}
      {greetingVisible && <button type="button" onClick={dismissGreeting}
        className="fixed bottom-[132px] left-1/2 z-50 w-[calc(100%-48px)] max-w-[420px] -translate-x-1/2 rounded-3xl bg-white px-5 py-4 text-left text-base font-semibold leading-7 text-[#6c3d24] shadow-[0_16px_48px_rgb(91_55_32/28%)]">
        {GREETING_TEXT}
      </button>}

      <ElderVoiceToasts />

      {/* 全局麦克风在所有页面保持同一位置，包括助手页、地图页和记录二级页。
          它是操作入口而不是第五个路由；助手输入框不再重复放置第二个麦克风。
          居中用 -ml-8（按钮 size-16 的一半），**不要**改成 -translate-x-1/2：
          祖先元素上只要有 transform，录音浮层的 position:fixed 就会以这个 64px 宽、
          64px 高的盒子为包含块，`w-[calc(100%-40px)]` 算出 24px，一行只放得下一个汉字。 */}
      <div className="fixed bottom-[46px] left-1/2 z-40 -ml-8">
        <ElderVoiceDock pageVoiceRef={pageVoiceRef} />
      </div>
    </MobileShell>
  </ElderVoiceProvider>;
}

/**
 * 把别的页面递过来的一句话发出去。
 *
 * <p>等这一轮忙完再发：老人可能在上一次回答还没说完时就按了按钮，直接丢掉的话
 * 他会以为按钮坏了，而这里只有一件事要做，不值得排队。
 */
function PendingAskBridge({ text, onDone, pageVoiceRef }: {
  text: string;
  onDone: () => void;
  pageVoiceRef: React.RefObject<((text: string) => boolean) | null>;
}) {
  const voice = useElderVoice();
  useEffect(() => {
    if (!text || voice.busy) return;
    onDone();
    // 这一句来自页面上的按钮（不是麦克风），先让当前页面自己的口令看一眼。
    if (pageVoiceRef.current?.(text)) return;
    void voice.send(text);
  }, [text, voice, onDone, pageVoiceRef]);
  return null;
}
