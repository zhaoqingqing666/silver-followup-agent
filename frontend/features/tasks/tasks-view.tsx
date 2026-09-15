'use client';

import { useCallback, useEffect, useState } from 'react';
import { BellRing, CalendarPlus, CalendarX, ChevronRight, Clock3, History, LoaderCircle, MapPin, Navigation, RefreshCw, Route, Stethoscope, Trash2, UsersRound } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { PlainDialog } from '@/components/common/plain-dialog';
import { MaterialChecklist } from '@/features/materials/material-checklist';
import { matchPageVoiceCommand } from '@/features/voice/voice-commands';
import { cancelAppointment, getAppointments } from '@/lib/appointment-api';
import { doctorLine } from '@/lib/appointment-display';
import { speakText } from '@/lib/tts-player';
import type { AppointmentSummary, TabId, VoicePreference } from '@/types/domain';

export function TasksView({ onNavigate, onOpenTravel, voicePreference, onRegisterVoice, onAskAssistant }: {
  onNavigate: (tab: TabId) => void;
  onOpenTravel: (appointmentId?: string) => void;
  voicePreference?: VoicePreference;
  /** 注册本页的只读语音口令（返回、再念一遍）；返回 false 表示交给助手处理。 */
  onRegisterVoice?: (handler: ((text: string) => boolean) | null) => void;
  /**
   * 把一句话交给助手去办（切到助手页并替他发出去）。
   *
   * <p>这是本页两条取消路径里的第二条：卡片垃圾桶是「我自己取消」，
   * 就地弹窗核对一次就走接口；这里是「让助手帮我取消」，把话递到助手页，
   * 由助手照常出确认卡、点过「确认」才生效。
   *
   * <p>两条都保留了确认这一步——写操作必须经确认这条规矩，不因为换个入口就破例。
   * 区别只在「谁来办」，所以按钮文字必须写明是「让助手」，不能让老人点下去才发现跳了页。
   */
  onAskAssistant?: (text: string) => void;
}) {
  const [appointments, setAppointments] = useState<AppointmentSummary[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  /** 正在等二次确认的那条预约。null 表示没有弹窗。 */
  const [cancelling, setCancelling] = useState<AppointmentSummary | null>(null);
  const [cancelBusy, setCancelBusy] = useState(false);
  const [cancelError, setCancelError] = useState('');

  /**
   * 读事项列表。
   *
   * <p>只留 `CONFIRMED`：取消过的记录后端仍然留着（号源已释放、提醒已停），
   * 但这里和首页都不该再出现——老人翻到一条「已取消」除了添堵没有别的用。
   *
   * <p>按预约时间正序：他真正关心的是「最近那次什么时候去」，不是「哪条最新建出来的」。
   * 接口给的是 created_at 倒序，排一次比让每个人自己在心里排要省事。
   *
   * <p>silent 用于取消之后的重新读取：那时候屏幕上已经有内容，不该再切回整页的「正在读取事项…」。
   */
  const load = async (options?: { silent?: boolean }) => {
    if (!options?.silent) {
      setLoading(true);
      setError('');
    }
    try {
      const rows = (await getAppointments())
        .filter(row => row.status === 'CONFIRMED')
        .sort((left, right) => `${left.date} ${left.time}`.localeCompare(`${right.date} ${right.time}`));
      setAppointments(rows);
      // 只在新数据里还找得到时才保留当前选择，否则退回最近的一次。
      // 取消掉正看着的那条之后，详情区不该停在一个已经不存在的预约上。
      setSelectedId(current => (rows.some(row => row.appointmentId === current)
        ? current
        : (rows[0]?.appointmentId ?? '')));
    } catch (cause) {
      if (!options?.silent) setError(cause instanceof Error ? cause.message : '无法读取复诊事项');
    } finally {
      if (!options?.silent) setLoading(false);
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

  const openCancel = (row: AppointmentSummary) => {
    setCancelError('');
    setCancelling(row);
  };

  /**
   * 二次确认之后真的取消。
   *
   * <p>取消不可逆——号源当场释放、关联提醒停掉——所以这里刻意不做乐观更新：
   * 只有后端回了成功才关弹窗、重新读一遍列表。失败就把后端那句话原样摆在弹窗里，
   * 让老人明确知道「没成」。猜「应该取消了吧」比多等一次难受得多。
   *
   * <p>成功后再广播一次 appointments-updated：助手页和首页都挂着这个事件，
   * 否则他们那边还留着一条已经不存在的预约。
   */
  const confirmCancel = async () => {
    if (!cancelling || cancelBusy) return;
    setCancelBusy(true);
    setCancelError('');
    try {
      await cancelAppointment(cancelling.appointmentId);
      setCancelling(null);
      await load({ silent: true });
      window.dispatchEvent(new Event('silver-agent-appointments-updated'));
    } catch (cause) {
      setCancelError(cause instanceof Error ? cause.message : '取消没有成功，请稍后再试。');
    } finally {
      setCancelBusy(false);
    }
  };

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
          <section className="rounded-3xl border bg-card p-4 shadow-sm">
            <div className="mb-3 flex items-center gap-2"><History className="size-6 text-primary" /><h2 className="text-xl font-bold">我的复诊预约</h2></div>
            <div className="grid gap-3">
              {appointments.map(row => (
                /* 卡片里现在有两个可点区域（选中 + 取消），所以外层只能用 div：
                   按钮套按钮是无效 HTML，浏览器会把里面那个拆出来。 */
                <div key={row.appointmentId}
                  className={'flex items-center gap-2 rounded-2xl border p-3 ' + (selectedId === row.appointmentId ? 'border-primary bg-[#fff3e4] ring-1 ring-primary/20' : 'bg-white')}>
                  {/* 卡片正文全是嵌套的 div/strong，无障碍规则认不出按钮的文字来源，
                      所以这里显式给一个 aria-label：屏幕阅读器念出来的和眼睛看到的一致。 */}
                  <button type="button" onClick={() => selectAppointment(row)}
                    aria-label={`查看${formatDate(row.date)}${formatTime(row.time)}在${row.hospital}${row.department}的复诊详情`}
                    className="min-w-0 flex-1 px-1 py-1 text-left">
                    <div className="flex items-start justify-between gap-3">
                      <div><strong className="text-lg">{formatDate(row.date)} {formatTime(row.time)}</strong><p className="mt-1 text-base">{row.hospital} · {row.department}</p><p className="mt-1 text-base text-muted-foreground">{doctorLine(row)}</p></div>
                      <span className="shrink-0 rounded-full bg-green-100 px-3 py-1 text-sm font-bold text-green-800">已预约</span>
                    </div>
                  </button>
                  {/* 列表里只有「已预约」，所以垃圾桶永远可点；确认放在点下去之后。
                      图标下面必须留「取消」两个字：这页还有一条「让助手帮我取消」，
                      只放一个图标的话，老人分不清哪条是自己取消、哪条是找助手，
                      两条就都成了不敢按的。 */}
                  <button type="button" onClick={() => openCancel(row)}
                    aria-label={`直接取消${formatDate(row.date)}${formatTime(row.time)}在${row.hospital}的复诊预约`}
                    className="flex min-h-14 w-14 shrink-0 flex-col items-center justify-center gap-0.5 rounded-xl border border-[#e3b7ae] bg-white text-[#a8402f] active:bg-[#fdecea]">
                    <Trash2 className="size-5" aria-hidden="true" />
                    <span className="text-sm font-bold">取消</span>
                  </button>
                </div>
              ))}
            </div>
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
              <p className="flex items-center gap-2"><Stethoscope className="size-5 text-primary" />{doctorLine(appointment)}</p>
              <p className="flex items-center gap-2"><Navigation className="size-5 text-primary" />{appointment.departureAt ? `建议 ${formatDateTime(appointment.departureAt)} 出发` : '未设置出行提醒'}</p>
            </div>
            <button onClick={() => onOpenTravel(appointment.appointmentId)}
              className="mt-5 flex min-h-14 w-full items-center justify-between rounded-2xl bg-primary px-4 text-left text-white shadow-sm disabled:opacity-50"
              disabled={appointment.status !== 'CONFIRMED'}>
              <span className="flex items-center gap-3"><Route className="size-6" /><span><strong className="block text-lg">查看地图与院内指引</strong><span className="text-sm text-white/80">路线、楼层和诊室位置</span></span></span>
              <ChevronRight className="size-6" />
            </button>

            {/* 这条路交给助手走它那套确认流程：先核对清楚，老人点过「确认」才真的取消、才释放号源。
                它和列表里的垃圾桶是两条并列的路，不是同一个按钮摆两处，所以文字要写明「让助手」——
                否则点下去突然换了页，老人第一反应是「我是不是点错了」，而不是「有人来帮我了」。

                配色上仍是描边红、不做实心主色：这张卡片里「查看地图与院内指引」才是主操作，
                同一屏只突出一个主按钮（需求映射 A-06），取消归次级。 */}
            {appointment.status === 'CONFIRMED' && onAskAssistant && (
              <button onClick={() => onAskAssistant('我想取消这次复诊预约')}
                className="mt-3 flex min-h-14 w-full items-center justify-center gap-3 rounded-2xl border-2 border-[#c2564a] bg-white px-4 text-[#a8402f]">
                <CalendarX className="size-6 shrink-0" aria-hidden="true" />
                <span className="text-left">
                  <span className="block text-lg font-bold">让助手帮我取消</span>
                  <span className="block text-sm text-[#8e3e20]">助手会先和您核对一次</span>
                </span>
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

      {/* 取消的二次确认。取消会当场释放号源、停掉关联提醒，且不可逆，
          所以第二次确认必须把「取消的是哪一次」写清楚——只问一句「确定吗」，
          老人没法核对，那等于没确认。浮层用 PlainDialog（原生 dialog + 铺满全屏居中）。 */}
      {cancelling && (
        <PlainDialog label="确认取消复诊预约">
          <div className="w-full max-w-[440px] rounded-3xl bg-white p-5 shadow-xl">
            <h2 className="text-2xl font-bold text-[#8e3e20]">要取消这次复诊吗？</h2>
            <div className="mt-4 rounded-2xl bg-[#fff8ed] p-4 ring-1 ring-[#efcda4]">
              <p className="text-xl font-bold">{formatDate(cancelling.date)} {formatTime(cancelling.time)}</p>
              <p className="mt-1 text-base">{cancelling.hospital} · {cancelling.department}</p>
              {/* 取消是不可逆的，核对项里带上医生：同一天同一时段完全可能换过医生，
                  只写医院科室他分不清取消的是不是自己以为的那一次。老数据没有医生时
                  这里照实写「医生信息未记录」，不编一位。 */}
              <p className="mt-1 text-base text-muted-foreground">{doctorLine(cancelling)}</p>
            </div>
            <p className="mt-3 text-base leading-7 text-muted-foreground">取消后号源会当场释放，关联的提醒也会停掉，且不能再恢复。</p>
            {cancelError && <p className="mt-3 rounded-xl bg-[#fdecea] px-3 py-2 text-base font-semibold text-[#a8402f]">{cancelError}</p>}
            <div className="mt-5 grid gap-3">
              <button type="button" onClick={() => void confirmCancel()} disabled={cancelBusy}
                className="flex min-h-14 items-center justify-center gap-2 rounded-2xl bg-[#c2564a] text-lg font-bold text-white disabled:opacity-50">
                {cancelBusy
                  ? <LoaderCircle className="size-6 animate-spin" aria-hidden="true" />
                  : <Trash2 className="size-6" aria-hidden="true" />}
                {cancelBusy ? '正在取消…' : '确认取消这次复诊'}
              </button>
              <button type="button" onClick={() => setCancelling(null)} disabled={cancelBusy}
                className="min-h-14 rounded-2xl border text-lg font-bold disabled:opacity-50">先留着，不取消</button>
            </div>
          </div>
        </PlainDialog>
      )}
    </main>
  );
}

/** 只用列表接口返回的真实字段拼接，不虚构任何内容。 */
function appointmentNarration(row: AppointmentSummary, all: AppointmentSummary[]) {
  const others = all.length > 1 ? `您一共有${all.length}条复诊记录。` : '';
  return [
    `您${formatDate(row.date)}${spokenClock(row.time)}在${row.hospital}${row.department}复诊，状态是${row.status === 'CONFIRMED' ? '已预约' : '已取消'}。`,
    // 医生只在这条预约真的有医生快照时才念；老数据没有这一项，念「医生信息未记录」
    // 对老人没有任何用，不如不提。
    row.doctorName ? `主诊医生${row.doctorName}${row.doctorTitle ? `，${row.doctorTitle}` : ''}。` : '',
    row.departureAt ? `建议${formatTime(row.departureAt)}出发。` : '还没有设置出发提醒。',
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
