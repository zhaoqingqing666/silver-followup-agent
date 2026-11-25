'use client';

import { useCallback, useEffect, useState } from 'react';
import { BellRing, CalendarPlus, CalendarX, ChevronDown, ChevronRight, Clock3, History, LoaderCircle, MapPin, Navigation, RefreshCw, Route, UsersRound } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { MaterialChecklist } from '@/features/materials/material-checklist';
import { matchPageVoiceCommand } from '@/features/voice/voice-commands';
import { getAppointments } from '@/lib/appointment-api';
import { speakText } from '@/lib/tts-player';
import type { AppointmentSummary, TabId, VoicePreference } from '@/types/domain';

/** 折叠时默认摆几次「即将到来的复诊」。 */
const DEFAULT_RECORD_COUNT = 3;

/**
 * 一条预约对应的时刻。date 是 YYYY-MM-DD、time 是 HH:MM，不带时区，
 * 按本地时区解释即可——这一页是给本人看的，显示和判断都取决于他手机上此刻的钟。
 */
function appointmentTime(row: AppointmentSummary) {
  return new Date(`${row.date}T${row.time}`).getTime();
}

/** 记录按时间分成三组：即将到来 / 过去的 / 已取消的。 */
interface AppointmentGroups {
  upcoming: AppointmentSummary[];
  past: AppointmentSummary[];
  cancelled: AppointmentSummary[];
}

/**
 * 把记录分成三组。now 由调用方传进来，不在这里读时钟：
 * 这个函数在渲染期被调用，而「现在几点」每次渲染都可能不同，属于渲染期不该发生的事。
 * 于是改为读到数据时算一次、连同结果一起存进 state——顺带也让分组在页面停留期间不会自己跳。
 *
 * 「即将到来」只认 CONFIRMED：取消掉的不算。已经过去但状态还是 CONFIRMED 的也不算——
 * 拿一条过期记录冒充「即将复诊」，比少显示一条更坏。
 */
function groupAppointments(rows: AppointmentSummary[], now: number): AppointmentGroups {
  const time = (row: AppointmentSummary) => appointmentTime(row);
  return {
    upcoming: rows.filter(row => row.status === 'CONFIRMED' && time(row) >= now)
      .sort((left, right) => time(left) - time(right)),
    past: rows.filter(row => row.status === 'CONFIRMED' && time(row) < now)
      .sort((left, right) => time(right) - time(left)),
    cancelled: rows.filter(row => row.status !== 'CONFIRMED'),
  };
}

export function TasksView({ onNavigate, onOpenTravel, voicePreference, onRegisterVoice, onAskAssistant }: {
  onNavigate: (tab: TabId) => void;
  onOpenTravel: (appointmentId?: string) => void;
  voicePreference?: VoicePreference;
  /** 注册本页的只读语音口令（返回、再念一遍）；返回 false 表示交给助手处理。 */
  onRegisterVoice?: (handler: ((text: string) => boolean) | null) => void;
  /**
   * 把一句话交给助手去办（切到助手页并替他发出去）。
   * 本页不直接调取消接口：写操作必须经过确认门禁，这条规矩不能因为换了个入口就破例。
   */
  onAskAssistant?: (text: string) => void;
}) {
  const [appointments, setAppointments] = useState<AppointmentSummary[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  /** 默认只摆最近三次即将到来的复诊；过去的和已取消的要老人自己展开才看。 */
  const [expanded, setExpanded] = useState(false);
  /** 分组结果和记录一起读回来。分开存是因为分组要跟「此刻」比时间，而读时钟不能在渲染期做。 */
  const [groups, setGroups] = useState<AppointmentGroups>({ upcoming: [], past: [], cancelled: [] });

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const rows = await getAppointments();
      const grouped = groupAppointments(rows, Date.now());
      setAppointments(rows);
      setGroups(grouped);
      // 默认选中「最近一次还没到时间的已确认预约」，而不是后端返回顺序里的第一条：
      // 后者可能是一条早就过去的记录，一进页面就把过期详情摆在老人眼前。
      // 但已经选中的那条只要还在（比如刚取消完刷新），就保留——老人手刚点过的地方不该自己跳走。
      setSelectedId(current => {
        if (current && rows.some(row => row.appointmentId === current)) return current;
        const preferred = grouped.upcoming[0] ?? rows[0];
        return preferred?.appointmentId ?? '';
      });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法读取复诊事项');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    // 助手在别的页面确认或取消预约后，这里要重新读取，否则还显示旧数据。
    const refresh = () => void load();
    window.addEventListener('silver-agent-appointments-updated', refresh);
    return () => window.removeEventListener('silver-agent-appointments-updated', refresh);
  }, []);

  const appointment = appointments.find(item => item.appointmentId === selectedId) ?? appointments[0];

  const selectAppointment = (row: AppointmentSummary) => {
    setSelectedId(row.appointmentId);
  };

  const { upcoming, past, cancelled } = groups;
  const shownUpcoming = expanded ? upcoming : upcoming.slice(0, DEFAULT_RECORD_COUNT);
  /**
   * 还有没有折叠起来的内容 —— 没有就不摆这颗按钮，没东西可展开的按钮只会让人白按一下。
   *
   * 不能写成「隐藏条数 > 0」：全部记录都是「即将到来」时（过去的和已取消的都没有），
   * 展开后隐藏条数正好归零，按钮会跟着消失，老人就再也收不回去了。
   * 判据要和「现在是展开还是折叠」无关。
   */
  const hasMoreRecords = upcoming.length > DEFAULT_RECORD_COUNT || past.length > 0 || cancelled.length > 0;

  /** 一条记录的样子。三组（即将到来 / 过去的 / 已取消的）共用，免得各写一套样式跑偏。 */
  const renderRecord = (row: AppointmentSummary) => (
    <button key={row.appointmentId} onClick={() => selectAppointment(row)}
      className={'rounded-2xl border p-4 text-left ' + (selectedId === row.appointmentId ? 'border-primary bg-[#fff3e4] ring-1 ring-primary/20' : 'bg-white')}>
      <div className="flex items-start justify-between gap-3">
        <div><strong className="text-lg">{formatDate(row.date)} {formatTime(row.time)}</strong><p className="mt-1 text-base">{row.hospital} · {row.department}</p></div>
        <span className={'shrink-0 rounded-full px-3 py-1 text-sm font-bold ' + (row.status === 'CONFIRMED' ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-600')}>{row.status === 'CONFIRMED' ? '已预约' : '已取消'}</span>
      </div>
    </button>
  );

  /** 页面只读语音口令：说话是明确要求，所以不受“自动朗读”开关限制。 */
  const handleVoiceCommand = useCallback((text: string) => {
    const command = matchPageVoiceCommand(text);
    if (command === 'BACK') { onNavigate('home'); return true; }
    if (command !== 'REPEAT') return false;
    const options = { rate: voicePreference?.speechRate, volume: voicePreference?.speechVolume };
    // 「念一遍」是单向按钮：每次念都换一个 key，否则同一句话再点会变成「停」而不是重新念。
    if (!appointment) { void speakText(`tasks-empty-${Date.now()}`, '现在还没有复诊事项可以念。', options); return true; }
    void speakText(`appointment-${appointment.appointmentId}-${Date.now()}`,
      appointmentNarration(appointment, appointments), options);
    return true;
  }, [appointment, appointments, onNavigate, voicePreference?.speechRate, voicePreference?.speechVolume]);

  useEffect(() => {
    if (!onRegisterVoice) return;
    onRegisterVoice(handleVoiceCommand);
    return () => onRegisterVoice(null);
  }, [onRegisterVoice, handleVoiceCommand]);

  return (
    <main className="space-y-5 px-5 pb-8 pt-5">
      <PageHeader title="复诊事项" onBack={() => onNavigate('home')} />

      {loading && (
        <section className="grid min-h-64 place-items-center rounded-3xl border bg-card">
          <div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-8 animate-spin" /><p className="mt-3 text-lg">正在读取事项…</p></div>
        </section>
      )}

      {!loading && error && (
        <section className="rounded-3xl border bg-card p-6 text-center">
          <p className="text-lg">{error}</p>
          <button onClick={() => void load()} className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white"><RefreshCw className="size-5" />重新读取</button>
        </section>
      )}

      {!loading && !error && !appointment && (
        <section className="rounded-3xl border border-[#efcda4] bg-[#fff8ed] px-6 py-10 text-center shadow-sm">
          <span className="mx-auto grid size-20 place-items-center rounded-full bg-[#ffe2bf] text-primary"><CalendarPlus className="size-10" /></span>
          <h2 className="mt-5 text-2xl font-bold">还没有复诊事项</h2>
          <p className="mt-2 text-lg leading-8 text-muted-foreground">完成预约并确认后，医院、时间、材料和提醒会显示在这里。</p>
          <button onClick={() => onNavigate('assistant')} className="mt-6 min-h-14 w-full rounded-2xl bg-primary px-5 text-lg font-bold text-white">让助手帮我预约</button>
        </section>
      )}

      {!loading && appointment && (
        <>
          {/* 默认只摆最近三次即将到来的复诊：老人一进这一页要看的是「下次什么时候去」，
              把所有历史记录一次铺开，最近的反而被埋在中间。过去的和已取消的展开后分组显示。 */}
          <section className="rounded-3xl border bg-card p-4 shadow-sm">
            <div className="mb-3 flex items-center gap-2"><History className="size-6 text-primary" /><h2 className="text-xl font-bold">我的复诊预约记录</h2></div>
            <div className="grid gap-3">
              {shownUpcoming.map(renderRecord)}
              {!shownUpcoming.length && (
                <p className="rounded-2xl border border-dashed border-[#efcda4] bg-[#fff8ed] px-4 py-3 text-base text-muted-foreground">
                  现在没有即将到来的复诊。
                </p>
              )}
            </div>

            {expanded && past.length > 0 && (
              <div className="mt-4">
                <p className="mb-2 text-base font-bold text-muted-foreground">过去的复诊</p>
                <div className="grid gap-3">{past.map(renderRecord)}</div>
              </div>
            )}

            {expanded && cancelled.length > 0 && (
              <div className="mt-4">
                <p className="mb-2 text-base font-bold text-muted-foreground">已取消的预约</p>
                <div className="grid gap-3">{cancelled.map(renderRecord)}</div>
              </div>
            )}

            {hasMoreRecords && (
              <button onClick={() => setExpanded(value => !value)}
                className="mt-4 flex min-h-12 w-full items-center justify-center gap-2 rounded-2xl border bg-white text-base font-bold">
                {expanded ? '收起' : `展开全部预约记录（${appointments.length}）`}
                <ChevronDown className={`size-5 transition ${expanded ? 'rotate-180' : ''}`} aria-hidden="true" />
              </button>
            )}
          </section>

          <section className="rounded-3xl bg-[#fff0dc] p-5 ring-1 ring-[#efcda4]">
            <div className="flex items-center gap-3">
              <div className="grid size-12 place-items-center rounded-2xl bg-primary text-white"><Clock3 /></div>
              <div>
                <p className="text-base text-muted-foreground">{appointment.status === 'CONFIRMED' ? '当前选择的复诊预约' : '已取消的预约记录'}</p>
                <h2 className="text-xl font-bold">{formatDate(appointment.date)} {formatTime(appointment.time)}</h2>
              </div>
            </div>
            <div className="mt-4 space-y-2 text-base">
              <p className="flex items-center gap-2"><MapPin className="size-5 text-primary" />{appointment.hospital} · {appointment.department}</p>
              {/* 已取消的预约不再给出发建议：这是取消前算出来的时间，留着会让人以为还得赶过去。
                  展开后能翻到已取消的记录，正是不该再显示它的场合。 */}
              <p className="flex items-center gap-2"><Navigation className="size-5 text-primary" />{appointment.status === 'CONFIRMED'
                ? (appointment.departureAt ? `建议 ${formatDateTime(appointment.departureAt)} 出发` : '未设置出行提醒')
                : '这次预约已经取消，不再提示出发时间'}</p>
            </div>
            <button onClick={() => onOpenTravel(appointment.appointmentId)}
              className="mt-5 flex min-h-14 w-full items-center justify-between rounded-2xl bg-primary px-4 text-left text-white shadow-sm disabled:opacity-50"
              disabled={appointment.status !== 'CONFIRMED'}>
              <span className="flex items-center gap-3"><Route className="size-6" /><span><strong className="block text-lg">查看地图与院内指引</strong><span className="text-sm text-white/80">路线、楼层和诊室位置</span></span></span>
              <ChevronRight className="size-6" />
            </button>

            {/* 取消不在这里直接调接口，而是交给助手走它那套确认流程：
                先核对清楚，老人点过「确认」才会真的取消并释放号源。
                一个按钮直接删数据，快是快，但老人按错一次就没有回头路了。 */}
            {appointment.status === 'CONFIRMED' && onAskAssistant && (
              <button onClick={() => onAskAssistant('我想取消这次复诊预约')}
                className="mt-3 flex min-h-14 w-full items-center justify-center gap-3 rounded-2xl border-2 border-[#c2564a] bg-white text-lg font-bold text-[#a8402f]">
                <CalendarX className="size-6" />取消这次复诊
              </button>
            )}
          </section>

          <MaterialChecklist appointmentId={appointment.appointmentId} disabled={appointment.status !== 'CONFIRMED'} />

          <section className="grid gap-3">
            <div className="flex items-center gap-4 rounded-3xl border bg-card p-4"><BellRing className="size-7 text-primary" /><div><strong className="text-lg">{appointment.reminderStatus ?? '未创建提醒'}</strong><p className="text-sm text-muted-foreground">以数据库中的执行结果为准</p></div></div>
            <div className="flex items-center gap-4 rounded-3xl border bg-card p-4"><UsersRound className="size-7 text-primary" /><div><strong className="text-lg">家属通知</strong><p className="text-sm text-muted-foreground">{appointment.familyStatus ?? '无需通知家属'}</p></div></div>
          </section>
        </>
      )}
    </main>
  );
}

/** 只用列表接口返回的真实字段拼接，不虚构任何内容。 */
function appointmentNarration(row: AppointmentSummary, all: AppointmentSummary[]) {
  const others = all.length > 1 ? `您一共有${all.length}条复诊记录。` : '';
  return [
    `您${formatDate(row.date)}${spokenClock(row.time)}在${row.hospital}${row.department}复诊，状态是${row.status === 'CONFIRMED' ? '已预约' : '已取消'}。`,
    row.status !== 'CONFIRMED' ? '这次预约已经取消。' : row.departureAt ? `建议${formatTime(row.departureAt)}出发。` : '还没有设置出发提醒。',
    row.reminderStatus ? `提醒：${row.reminderStatus}。` : '',
    row.familyStatus ? `家属通知：${row.familyStatus}。` : '',
    others,
    row.status === 'CONFIRMED' ? '想看路线的话，可以说“打开地图”。' : '',
  ].filter(Boolean).join('');
}

function spokenClock(value: string) {
  const [hourText, minuteText] = formatTime(value).split(':');
  const hour = Number(hourText);
  const minute = Number(minuteText);
  return `${hour < 12 ? '上午' : '下午'}${hour > 12 ? hour - 12 : hour}点${minute === 0 ? '' : `${minute}分`}`;
}

function formatDate(value: string) {
  const [, month, day] = value.split('-');
  return `${Number(month)}月${Number(day)}日`;
}

function formatTime(value: string) {
  return value.slice(0, 5);
}

function formatDateTime(value: string) {
  return value.includes('T') ? value.split('T')[1].slice(0, 5) : value.slice(11, 16);
}
