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
import { VoiceMicButton } from '@/features/voice/voice-mic-button';
import type { VoiceRecording } from '@/features/voice/use-press-to-talk';
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
  /** 开场提示气泡：新进入老人端时显示一次，之后在应用内部切页不再弹（状态活在本组件里）。 */
  const [greetingVisible, setGreetingVisible] = useState(true);
  const greetingSpoken = useRef(false);
  // 助手注册的“说一句话就发送”，以及当前页面注册的只读语音口令。
  const assistantSendRef = useRef<((text: string, recording: VoiceRecording | null) => void) | null>(null);
  const pageVoiceRef = useRef<((text: string) => boolean) | null>(null);
  /**
   * 别的页面交给助手去说的一句话（事项页的「取消这次复诊」）。
   *
   * 取消预约要落到数据库，而按本项目的约定，只有确认门禁能触发写操作——
   * 所以事项页不自己调删除接口，而是把这句话交给助手，让它照常出确认卡、
   * 由老人点「确认」才算数。这里只负责把话递过去并切到助手页。
   */
  const [pendingAsk, setPendingAsk] = useState('');

  useEffect(() => {
    // 协同照护端没有语音入口，不必拉取朗读设置。
    if (actor !== 'ELDER') return;
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
  }, [actor]);

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
   * forceSpeak 只在“用户主动用语音问出来”的这一次打开时传 true：
   * 地图页要立刻把路线/院内指引读出来，即使自动朗读开关是关的。它不写回任何设置。
   */
  const openTravel = useCallback((appointmentId = '', focus: TravelFocus = 'outside',
                                  options?: { forceSpeak?: boolean }) => {
    setTravelAppointmentId(appointmentId);
    setTravelFocus(focus);
    setTravelForceSpeak(options?.forceSpeak === true);
    setTravelSession(session => session + 1);
    setActiveTab('travel');
  }, []);
  const closeTravel = useCallback(() => setActiveTab('tasks'), []);
  const registerAssistantSend = useCallback((send: ((text: string, recording: VoiceRecording | null) => void) | null) => {
    assistantSendRef.current = send;
  }, []);
  const registerPageVoice = useCallback((handler: ((text: string) => boolean) | null) => {
    pageVoiceRef.current = handler;
  }, []);
  const askAssistant = useCallback((text: string) => {
    setActiveTab('assistant');
    setPendingAsk(text);
  }, []);
  const consumeAsk = useCallback(() => setPendingAsk(''), []);

  /**
   * 全局麦克风：当前页面能自己处理的口令就本地处理（不打断助手对话），
   * 其余一律在后台交给助手；页面跳转由后端回传的 uiDirective 决定，不在前端猜、也不抢先切页。
   */
  const onGlobalVoice = useCallback((text: string, recording: VoiceRecording | null) => {
    if (pageVoiceRef.current?.(text)) return;
    assistantSendRef.current?.(text, recording);
  }, []);

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
    if (actor !== 'ELDER' || !greetingVisible) return;
    const hide = () => dismissGreeting();
    document.addEventListener('pointerdown', hide, true);
    const timer = window.setTimeout(hide, GREETING_MS);
    return () => {
      document.removeEventListener('pointerdown', hide, true);
      window.clearTimeout(timer);
    };
  }, [actor, greetingVisible, dismissGreeting]);

  /**
   * 念一遍开场白，遵守「自动朗读」开关与语速；关着就只显示文字。
   * greetingSpoken 保证只念一次——气泡因重渲染重新出现时不会再念第二遍。
   */
  useEffect(() => {
    if (actor !== 'ELDER' || !greetingVisible || !voicePreferenceReady) return;
    if (!voicePreference.autoSpeakEnabled || greetingSpoken.current) return;
    greetingSpoken.current = true;
    void speakText(`greeting-${Date.now()}`, GREETING_TEXT, {
      rate: voicePreference.speechRate, volume: voicePreference.speechVolume,
    });
  }, [actor, greetingVisible, voicePreferenceReady, voicePreference]);

  // hooks 必须全部先于下面的提前 return，否则身份切换会改变 hook 顺序。
  if (!actor) {
    return <MobileShell largeText={false}><ActorPicker onPick={setActor} /></MobileShell>;
  }

  // 家属 / 志愿者：协同照护端。老人端的语音、地图和助手不在这一支里出现。
  if (actor !== 'ELDER') {
    return <MobileShell largeText={false}>
      <CareView actor={actor} onSwitchActor={() => setActor(null)} />
    </MobileShell>;
  }

  // 首页点进去的二级页占满整屏、不显示底部导航：老人从底部溜走再回来会回到首页，容易以为自己点丢了。
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
    {activeTab === 'home' && <HomeView onNavigate={setActiveTab} onOpenTravel={openTravel}
      onOpenPage={setRecordPage} />}
    {activeTab === 'tasks' && <TasksView onNavigate={setActiveTab} onOpenTravel={openTravel}
      voicePreference={voicePreference} onRegisterVoice={registerPageVoice} onAskAssistant={askAssistant} />}
    <div className={activeTab === 'assistant' ? 'block' : 'hidden'} aria-hidden={activeTab !== 'assistant'}>
      <AssistantView active={activeTab === 'assistant'} onNavigate={setActiveTab}
        onOpenTravel={openTravel} voicePreference={voicePreference} onRegisterSend={registerAssistantSend}
        pendingAsk={pendingAsk} onAskConsumed={consumeAsk} />
    </div>
    {activeTab === 'travel' && <TravelGuideView key={travelSession} appointmentId={travelAppointmentId}
      initialTab={travelFocus} forceSpeak={travelForceSpeak} voicePreference={voicePreference}
      onBack={closeTravel} onRegisterVoice={registerPageVoice} />}
    {activeTab === 'profile' && <ProfileView onNavigate={setActiveTab} largeText={largeText} onLargeTextChange={setLargeText}
      autoSpeakEnabled={voicePreference.autoSpeakEnabled} voicePreferenceBusy={voicePreferenceBusy}
      voicePreferenceError={voicePreferenceError} onAutoSpeakChange={changeAutoSpeak}
      speechRate={voicePreference.speechRate} onSpeechRateChange={patch => void changeVoicePreference(patch)} />
    }
    {activeTab !== 'travel' && <BottomNav activeTab={activeTab} onChange={setActiveTab} />}

    {/* 开场提示气泡：每次新进入老人端显示一次，之后在应用内部切页不再弹。
        位置就在麦克风正上方（bottom-[132px] 是录音浮层用的同一档），不盖住那个按钮本身。
        宽度按视口算、不放进麦克风那个 64px 的小盒子——理由和录音浮层一样（见下面的注释）。
        点它、点别处、按住麦克风，三种情况都会收掉它。 */}
    {greetingVisible && <button type="button" onClick={dismissGreeting}
      className="fixed bottom-[132px] left-1/2 z-50 w-[calc(100%-48px)] max-w-[420px] -translate-x-1/2 rounded-3xl bg-white px-5 py-4 text-left text-base font-semibold leading-7 text-[#6c3d24] shadow-[0_16px_48px_rgb(91_55_32/28%)]">
      {GREETING_TEXT}
    </button>}
    {/* 全局麦克风在所有页面保持同一位置，包括助手页和地图页。
        它是操作入口而不是第五个路由；助手输入框不再重复放置第二个麦克风。
        居中用 -ml-8（按钮 size-16 的一半），**不要**改成 -translate-x-1/2：
        祖先元素上只要有 transform，录音浮层的 position:fixed 就会以这个 64px 宽、
        64px 高的盒子为包含块，`w-[calc(100%-40px)]` 算出 24px，一行只放得下一个汉字。 */}
    <div className="fixed bottom-[46px] left-1/2 z-40 -ml-8">
      <VoiceMicButton onTranscript={onGlobalVoice} />
    </div>
  </MobileShell>;
}
