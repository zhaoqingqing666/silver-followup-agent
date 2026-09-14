'use client';

import { FormEvent, useEffect, useRef, useState, type ChangeEvent } from 'react';
import { CalendarSearch, Camera, ClipboardCheck, History, Image as ImageIcon, LoaderCircle, Lock, NotebookPen, Plus, RefreshCw, Send, Sparkles, SquarePen, X } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { useElderVoice } from '@/features/voice/elder-voice-provider';
import { compressImageFile } from '@/lib/image-compress';
import { stopPlayback } from '@/lib/tts-player';
import type { TabId, TravelFocus, VoicePreference } from '@/types/domain';
import { BoundaryAlert, ConfirmationCardView, PlanCard, ResultCardView } from './assistant-cards';
import { CameraCapture } from './camera-capture';
import { ChatBubble } from './chat-bubble';
import { HistorySheet } from './history-sheet';
import { ToolTracePanel } from './tool-trace-panel';
import { useTurnProgress } from './use-turn-progress';

/** 点一下就填进输入框的示例话：分别对应“记数值 / 记提醒 / 管理已有提醒”三件事。 */
const EXAMPLE_SAYINGS = ['我的血压是100', '明早八点提醒我吃药', '我都有哪些备忘'];

/**
 * 等待时显示的话。图片轮要走视觉识别，实测十几秒，一句「正在处理」老人会以为卡死了；
 * 所以按阶段轮换，让他知道系统在往前走。措辞必须老实——这几步后端真的在依次做，
 * 不是拿来撑场面的假进度条。
 */
const IMAGE_STAGES = ['正在识别图片…', '正在核对信息…', '正在整理结果…'];
const PLAIN_STAGES = ['复诊助手正在处理…'];
/** 每个阶段停留多久。比识别耗时略短，让最后一个阶段在结束前就到位。 */
const STAGE_INTERVAL_MS = 4000;

/** 一轮最多发几张图。后端也只取前 3 张，前端先挡住，别让老人白等。 */
const MAX_IMAGES = 3;

const stageLabels: Record<string, string> = {
  ASK_HOSPITAL: '确认医院', ASK_DEPARTMENT: '确认科室', ASK_DATE: '确认日期',
  ASK_ALTERNATIVE: '补充偏好', ASK_COMPANION: '陪同安排', ASK_TRAVEL: '出行安排',
  ASK_NOTIFY: '家属通知', ASK_TRANSPORT: '交通方式', READY_TO_PLAN: '检查计划', SELECT_PERIOD: '选择上午或下午', CONFIRM_SLOT: '确认推荐时间', SELECT_SLOT: '选择具体时间', NO_SLOT: '更换时间', CONFLICT: '处理冲突',
  EMERGENCY_PAUSED: '已暂停，请及时求助', PARTIAL: '部分完成', TOOL_ERROR: '需要重试或修改', AWAITING_CONFIRMATION: '等待确认', MEMO_TIME: '补充提醒时间', COMPLETED: '办理完成', CANCELLED: '已取消',
};

/**
 * 复诊助手页。
 *
 * <p>它**不再自己持有会话**：会话、发送、进行中状态、朗读和页面指令全在
 * {@link useElderVoice} 那一层，和全局麦克风共用同一份。这样助手页只是这套能力的一个视图，
 * 不会再出现「同一句语音被发两次」，也不会因为助手页没挂载过就用不了语音。
 *
 * <p>这里留下的都是纯界面状态：输入框、待发图片、拍照、快捷选项翻页、等车提示。
 */
export function AssistantView({ active, onNavigate, onOpenTravel, voicePreference }: {
  active: boolean;
  onNavigate: (tab: TabId) => void;
  onOpenTravel: (appointmentId?: string, focus?: TravelFocus, options?: { forceSpeak?: boolean }) => void;
  voicePreference: VoicePreference;
}) {
  const voice = useElderVoice();
  const { conversationId, messages, turn, visiblePlan, busy, readOnly } = voice;
  /** 等待提示的当前阶段。图片轮用它轮换，普通轮恒为一条。 */
  const [stages, setStages] = useState<string[]>(PLAIN_STAGES);
  const [stageIndex, setStageIndex] = useState(0);
  const [input, setInput] = useState('');
  const [historyOpen, setHistoryOpen] = useState(false);
  /** 每开一段新对话 / 切一次历史就加一，浮层下次打开会重新拉一遍列表。 */
  const [historyToken, setHistoryToken] = useState(0);
  // 「查看办理过程」的实时一半：轮询后端此刻正在进行的工具调用。
  const progress = useTurnProgress(conversationId);
  const [choicePage, setChoicePage] = useState(0);
  /** 拍好/选好但还没发出去的图片（data URL），可以补一句说明再发。 */
  const [pendingImages, setPendingImages] = useState<string[]>([]);
  const [cameraOpen, setCameraOpen] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const bottomAnchor = useRef<HTMLDivElement | null>(null);

  /** 助手页第一次露出来时接上会话：能接上上次那段就接，接不上就开一段新的。 */
  useEffect(() => {
    if (active) voice.ensureReady();
  }, [active, voice]);

  useEffect(() => {
    bottomAnchor.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages.length, turn?.stage]);

  // 等待提示逐段前进，只前进不回头：识别真花多久不由前端说了算，
  // 停在最后一段「正在整理结果…」比循环回第一段更像在骗人。
  useEffect(() => {
    if (!busy || stages.length < 2) return;
    const timer = window.setInterval(
      () => setStageIndex(index => Math.min(index + 1, stages.length - 1)), STAGE_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [busy, stages]);

  /** 选图/拍照：先压缩再暂存预览，同一张图重复选（连点、对着同一盒药连拍）只留一张，
   *  否则后端会把同一张图各识别一遍，回复里出现好几遍一模一样的描述。 */
  const addImages = (compressed: string[]) => {
    const room = MAX_IMAGES - pendingImages.length;
    if (room <= 0) { voice.showHint(`一次最多 ${MAX_IMAGES} 张图片，请先把这张发出去`); return; }
    const fresh = compressed.filter((url, index) =>
      compressed.indexOf(url) === index && !pendingImages.includes(url));
    if (!fresh.length) { voice.showHint('这张图片已经添加过了，不用重复上传。'); return; }
    if (fresh.length > room) voice.showHint(`一次最多 ${MAX_IMAGES} 张，已保留前 ${MAX_IMAGES} 张。`);
    setPendingImages([...pendingImages, ...fresh].slice(0, MAX_IMAGES));
  };

  const onPickedFiles = async (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    // 先清 value：同一张图连选两次也要能再触发 change
    event.target.value = '';
    if (!files.length) return;
    // 压缩失败会自动回退原图，老人不会因为压不动就传不了
    addImages(await Promise.all(files.slice(0, MAX_IMAGES).map(compressImageFile)));
  };

  /** 「新对话」：先合上浮层，再交给公共层去结束旧的、开新的。 */
  const startNewConversation = async () => {
    setHistoryOpen(false);
    setHistoryToken(token => token + 1);
    await voice.startNew();
  };

  /** 发送待发图片：图片就是这一轮的问题，输入框里的字（如果有）当补充说明。 */
  const sendPendingImages = async () => {
    if (!pendingImages.length || !conversationId || busy) return;
    const images = pendingImages;
    setPendingImages([]);
    // 带图的一轮要等十几秒，换成按阶段轮换的提示；普通轮保持一句话。
    setStages(IMAGE_STAGES);
    setStageIndex(0);
    await voice.send(input.trim(), { images });
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    // 有待发图片时，输入框里的字是图片的说明，点发送就是把图片发出去
    if (pendingImages.length) void sendPendingImages();
    else void voice.send(input);
  };

  return <main className="flex min-h-dvh flex-col pb-[180px]">
    <PageHeader title="复诊助手" subtitle="一次只问一件事" onBack={() => onNavigate('home')}
      onHelp={() => void voice.sendAction('CONTACT_HUMAN', '', '联系人工帮助')}>
      {/* 这一栏放进页头里，而不是自己再写一层 sticky top-[76px]：
          它原本粘在标题行下面 76px 处，大字模式把标题行撑高后就会错位或者留一条缝。 */}
      <div className="flex items-center gap-2">
        <div className="flex min-w-0 flex-1 items-center gap-2 text-sm font-semibold text-[#76533d]">
          <Sparkles className="size-4 shrink-0 text-primary" aria-hidden="true" />
          <span className="truncate">{readOnly ? '这段对话已经结束' : turn?.task?.active ? `办理步骤：${stageLabels[turn.stage] ?? '正在处理'}` : '当前可以自由交流'}</span>
        </div>
        <button type="button" onClick={() => setHistoryOpen(true)}
          className="flex min-h-10 shrink-0 items-center gap-1.5 rounded-xl border border-[#dfb98f] bg-white px-3 text-sm font-bold text-[#6c3d24]">
          <History className="size-4 text-primary" aria-hidden="true" />历史记录
        </button>
        <button type="button" onClick={() => void startNewConversation()} disabled={busy}
          className="flex min-h-10 shrink-0 items-center gap-1.5 rounded-xl border border-[#dfb98f] bg-white px-3 text-sm font-bold text-[#6c3d24] disabled:opacity-40">
          <SquarePen className="size-4 text-primary" aria-hidden="true" />新对话
        </button>
      </div>
    </PageHeader>

    {/* 只读态由前端自己声明，不靠拼后端回复：后端每一轮的 reply 与 speechText
        必须逐字对应（有测试盯着），前端往里加自己的话会破坏这个约定。 */}
    {readOnly && <section className="border-b bg-[#f6f2ec] px-5 py-3">
      <div className="rounded-2xl border border-[#d9d2c7] bg-white px-4 py-3 shadow-sm">
        <p className="flex items-center gap-2 text-base font-bold text-[#6b6255]">
          <Lock className="size-4 shrink-0" aria-hidden="true" />这是之前的一段对话
        </p>
        <p className="mt-1 text-base leading-6 text-muted-foreground">还能往上翻看，但这里已经不能再办事了。</p>
        <button type="button" onClick={() => void startNewConversation()} disabled={busy}
          className="mt-3 min-h-12 w-full rounded-2xl bg-primary text-base font-bold text-white disabled:opacity-40">
          开始新的一段对话
        </button>
      </div>
    </section>}
    <div className="border-b bg-[#fffaf3] px-5 py-3">
      <div className="grid grid-cols-2 gap-2">
        {/* 只读态下「预约复诊」直接禁用：后端一定会拒收，让它可点只是诱着老人白按一次。 */}
        <button disabled={busy || readOnly} onClick={() => void voice.sendAction('CONTINUE', '', turn?.task?.active ? '继续刚才的办理' : '我想预约复诊')} className="flex min-h-12 items-center justify-center gap-2 rounded-2xl bg-primary text-base font-bold text-white disabled:opacity-40"><CalendarSearch className="size-5" />{turn?.task?.active ? '继续办理' : '预约复诊'}</button>
        <button onClick={() => onNavigate('tasks')} className="flex min-h-12 items-center justify-center gap-2 rounded-2xl border bg-white text-base font-bold"><ClipboardCheck className="size-5 text-primary" />事项查询</button>
      </div>
      {/* 朗读设置整块搬到「我的」了（见 profile-view.tsx）：语速和音色是全局偏好，
          和「自动朗读」开关摆在一起才讲得通，也免得每个页面各摆一份、各存一份。 */}
      <div className="mt-2 rounded-2xl border border-dashed border-[#dba976] bg-white px-3 py-2 shadow-sm">
        <p className="flex items-center gap-2 text-sm font-semibold text-[#6c3d24]"><NotebookPen className="size-4 text-primary" aria-hidden="true" />记数值、记提醒、看看有哪些提醒，都可以说一句</p>
        <div className="mt-1.5 flex flex-wrap gap-2">
          {EXAMPLE_SAYINGS.map(saying => (
            <button key={saying} type="button" onClick={() => setInput(saying)} className="min-h-11 rounded-xl bg-[#fff4e2] px-3 text-base font-semibold text-[#6c3d24]">{saying}</button>
          ))}
        </div>
      </div>
    </div>

    {/* 办理中的紧凑摘要：只说清「办的是哪一次、到哪一步了」，不再摆第二颗「继续办理」。
        summary 由后端按真实状态拼（字段没定就写「待选择科室」，不编造），日期是中文写法。
        恢复办理用上面操作区那颗按钮（有任务时它就叫「继续办理」），要回答的问题在对话里。
        取消仍留在这里——办理中连一个显式的退出入口都没有，老人就只能靠说话，代价太大。 */}
    {!readOnly && turn?.task?.active && <section className="border-b bg-[#fff4e7] px-5 py-3">
      <div className="rounded-2xl border border-[#e6bc8c] bg-white px-4 py-3 shadow-sm">
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0">
            <p className="text-sm font-bold text-primary">当前复诊办理</p>
            <p className="mt-1 text-base font-semibold">{turn.task.summary}</p>
          </div>
          <span className="shrink-0 rounded-full bg-[#fff0dc] px-3 py-1 text-sm font-bold text-primary">{turn.task.status === 'PAUSED' ? '已暂停' : '进行中'}</span>
        </div>
        <button disabled={busy} onClick={() => void voice.sendAction('CANCEL_TASK', '', '取消本次办理')} className="mt-3 min-h-11 w-full rounded-xl border px-3 font-semibold">取消本次办理</button>
      </div>
    </section>}
    <div className="flex-1 space-y-4 px-5 py-5">
      <section aria-label="对话记录" className="space-y-3">
        {messages.map(message => <ChatBubble key={message.id} message={message} voicePreference={voicePreference} />)}
        {busy && <div aria-live="polite" className="flex items-center gap-2 text-base text-muted-foreground"><LoaderCircle className="size-5 animate-spin" />{stages[Math.min(stageIndex, stages.length - 1)]}</div>}
      </section>

      {/* 越界提示紧跟最近一轮消息：老人问完「这个药还能吃吗」，眼睛停在屏幕下方，
          提示块摆在这里才看得见。办理中的计划卡、确认卡都还照常留在下面。 */}
      {turn?.notice && <BoundaryAlert notice={turn.notice} />}

      {visiblePlan && <PlanCard plan={visiblePlan} />}
      {/* 结束了的会话不摆确认卡：后端已经不放行，一张按不动的「确认办理」比没有卡片更糟。 */}
      {!readOnly && turn?.confirmation && <ConfirmationCardView card={turn.confirmation} busy={busy} onConfirm={() => void voice.confirm(true)} onCancel={() => void voice.confirm(false)} />}
      {turn?.result && <ResultCardView result={turn.result} partial={turn.stage === 'PARTIAL'} onOpenTravel={onOpenTravel} />}
      {/* 工具调用过程由后端每轮返回（toolTraces），这里原样渲染，不写死任何一条。
          办理中换成实时进度：这时上一轮的 toolTraces 已经不是「正在发生的事」了，
          混在一起会让人误以为旧步骤是本轮在跑的。 */}
      {turn && <ToolTracePanel traces={busy ? [] : turn.toolTraces}
        liveEvents={progress.events} liveActive={busy && progress.active} />}
      {!readOnly && !!turn?.quickReplies.length && !turn.confirmation && <section aria-label="快捷回答" className="flex flex-wrap gap-2">
        {turn.quickReplies.slice(choicePage * 3, choicePage * 3 + 3).map(choice => <button key={choice.label + choice.action + choice.value} disabled={busy} onClick={() => void voice.sendAction(choice.action, choice.value, choice.label)} className="min-h-12 rounded-2xl border border-[#dfb98f] bg-white px-4 text-base font-semibold text-[#6c3d24] shadow-sm disabled:opacity-50">{choice.label}</button>)}
      </section>}

      {!readOnly && (turn?.quickReplies.length ?? 0) > 3 && !turn?.confirmation && <button disabled={busy} onClick={() => setChoicePage(page => (page + 1) % Math.ceil((turn?.quickReplies.length ?? 0) / 3))} className="min-h-12 rounded-2xl border bg-white px-4 text-base font-bold">查看更多选项</button>}
      {!conversationId && !busy && <button onClick={() => voice.ensureReady()} className="flex min-h-13 w-full items-center justify-center gap-2 rounded-2xl border bg-white text-base font-bold"><RefreshCw className="size-5" />重新连接</button>}
      <div ref={bottomAnchor} aria-hidden="true" className="h-px" />
    </div>

    {/* 已结束的会话不给输入框：让老人打完一整句话再被拒，比一开始就不给打字更伤人。 */}
    {readOnly ? <div className="fixed bottom-[90px] left-1/2 z-40 w-[calc(100%-32px)] max-w-[448px] -translate-x-1/2 rounded-3xl border bg-[#f6f2ec] p-3 text-center shadow-[0_8px_30px_rgb(91_55_32/18%)]">
      <p className="text-base font-semibold text-[#6b6255]">这段对话已经结束了</p>
      <button type="button" onClick={() => void startNewConversation()} disabled={busy}
        className="mt-2 min-h-12 w-full rounded-2xl bg-primary text-base font-bold text-white disabled:opacity-40">
        开一段新对话
      </button>
    </div> : <form onSubmit={submit} className="fixed bottom-[90px] left-1/2 z-40 w-[calc(100%-32px)] max-w-[448px] -translate-x-1/2 rounded-3xl border bg-card p-2 shadow-[0_8px_30px_rgb(91_55_32/18%)]">
      {/* 待发送的图片先给老人自己看一眼：拍糊了、拍错了，现在还能点掉重拍 */}
      {pendingImages.length > 0 && <div className="mb-2 flex flex-wrap items-center gap-2 rounded-2xl bg-[#fff8ed] p-2">
        {pendingImages.map((url, index) => <div key={url} className="relative shrink-0">
          {/* eslint-disable-next-line next/no-img-element */}
          <img src={url} alt={`待发送图片${index + 1}`} className="size-24 rounded-xl object-cover" />
          <button type="button" onClick={() => setPendingImages(prev => prev.filter(item => item !== url))} aria-label="取消这张图片" className="absolute -right-1.5 -top-1.5 grid size-6 place-items-center rounded-full bg-[#dfb98f] text-[#6c3d24] shadow"><X className="size-3.5" /></button>
        </div>)}
        {pendingImages.length < MAX_IMAGES && <button type="button" onClick={() => fileInputRef.current?.click()} aria-label="继续添加图片" className="grid size-24 shrink-0 place-items-center rounded-xl border border-dashed border-[#dfb98f] text-[#a87e5c]"><Plus className="size-6" /></button>}
      </div>}
      <div className="flex items-center gap-2">
        <input value={input} onChange={event => setInput(event.target.value)} onFocus={() => stopPlayback()} aria-label="输入想说的话" placeholder={pendingImages.length ? '可在此添加说明（可选）' : '也可以在这里打字'} className="min-w-0 flex-1 bg-transparent px-2 text-base outline-none" />
        <button type="button" onClick={() => fileInputRef.current?.click()} disabled={busy} aria-label="从相册选择图片" className="grid size-12 shrink-0 place-items-center rounded-2xl border border-[#dfb98f] bg-white text-[#6c3d24] disabled:opacity-40"><ImageIcon className="size-5" /></button>
        <button type="button" onClick={() => setCameraOpen(true)} disabled={busy} aria-label="拍照" className="grid size-12 shrink-0 place-items-center rounded-2xl border border-[#dfb98f] bg-white text-[#6c3d24] disabled:opacity-40"><Camera className="size-5" /></button>
        <button type="submit" disabled={(!input.trim() && !pendingImages.length) || busy} aria-label="发送" className="grid size-12 shrink-0 place-items-center rounded-2xl bg-primary text-white disabled:opacity-40"><Send className="size-5" /></button>
      </div>
    </form>}

    {/* 用 opacity-0 + absolute 代替 hidden：部分浏览器对 display:none 的 input 调 click() 不生效 */}
    <input ref={fileInputRef} type="file" accept="image/*" multiple className="pointer-events-none absolute h-0 w-0 opacity-0" onChange={event => void onPickedFiles(event)} />

    {cameraOpen && <CameraCapture onClose={() => setCameraOpen(false)} onCapture={dataUrl => {
      setCameraOpen(false);
      addImages([dataUrl]);
    }} />}

    <HistorySheet open={historyOpen} onClose={() => setHistoryOpen(false)} currentId={conversationId}
      refreshToken={historyToken} onPick={summary => { setHistoryToken(token => token + 1); void voice.pickFromHistory(summary); }} />
  </main>;
}
