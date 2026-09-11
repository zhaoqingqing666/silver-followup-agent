'use client';

import { useEffect, useRef, useState } from 'react';
import { CalendarDays, Check, ChevronLeft, ChevronRight, Clock3, Hospital, LoaderCircle, Pencil, Route, TriangleAlert, UsersRound } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { getAppointments } from '@/lib/appointment-api';
import { cancelElderBooking, createBooking, getBookingDepartments, getBookingHospitals, getBookingWindows, modifyBooking, setBookingAccompany } from '@/lib/care-api';
import type {
  AppointmentSummary,
  BookingDateWindow,
  BookingDepartment,
  BookingHospital,
  BookingSlotOption,
  CareElder,
} from '@/types/domain';
import { formatDate, formatHM } from './format';
import { CareAppointmentDetailView } from './care-appointment-detail-view';

const TRANSPORTS = ['打车', '公交', '家属开车'] as const;

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

/** 2026-09-18 → 周五 */
const weekdayOf = (date: string): string => WEEKDAYS[new Date(`${date}T00:00:00`).getDay()];

/** "09:00" / "09:00:00" 都归一成分钟数后比较，用于把当前预约的号源和可约号源对上。 */
const minutesOf = (time: string): number => {
  const [h, m] = time.split(':');
  return Number(h) * 60 + Number(m);
};
const sameTime = (a: string, b: string): boolean => minutesOf(a) === minutesOf(b);

/** 本地时区的今天（yyyy-MM-dd），用于判断预约是否进行中。 */
const todayLocal = (): string => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
};

const relationship = (elder: CareElder): string => {
  const prefix = elder.relationship ? `${elder.relationship} ` : '';
  return `${prefix}${elder.name}`;
};

/** 帮助预约（含管理/新办）：先落“现有安排”管理视图（有进行中预约时）或直接进向导（没有时）。 */
export function CareBookingView({ caregiverId, elder, onBack, onFinished }: {
  caregiverId: string;
  elder: CareElder;
  onBack: () => void;
  onFinished: () => void;
}) {
  /* ---------- 向导可选数据 ---------- */
  const [hospitals, setHospitals] = useState<BookingHospital[] | null>(null);
  const [hospital, setHospital] = useState<BookingHospital | null>(null);
  const [departments, setDepartments] = useState<BookingDepartment[] | null>(null);
  const [department, setDepartment] = useState<BookingDepartment | null>(null);
  const [windows, setWindows] = useState<BookingDateWindow[] | null>(null);
  const [date, setDate] = useState('');
  const [slot, setSlot] = useState<BookingSlotOption | null>(null);
  const [transport, setTransport] = useState<(typeof TRANSPORTS)[number]>('打车');
  const [needTravel, setNeedTravel] = useState(true);
  const [willAccompany, setWillAccompany] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState('');
  const [error, setError] = useState('');
  const [step, setStep] = useState(0);

  /* ---------- 流程状态 ---------- */
  /** undefined=还在读预约，null=当前没有进行中预约，否则=这张是当前安排。 */
  const [upcoming, setUpcoming] = useState<AppointmentSummary | null | undefined>(undefined);
  const [phase, setPhase] = useState<'loading' | 'manage' | 'wizard'>('loading');
  /** 向导的用途：create=新代约，modify=改当前这张。 */
  const [mode, setMode] = useState<'create' | 'modify'>('create');
  const [notice, setNotice] = useState('');
  const [detail, setDetail] = useState<AppointmentSummary | null>(null);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState('');
  const [accompanySaving, setAccompanySaving] = useState(false);
  const [accompanyError, setAccompanyError] = useState('');
  /** 修改向导只做一次“按当前安排预填”，避免医院/科室反复请求。 */
  const prefillDone = useRef(false);

  /** 进入页面即加载可选医院；默认选中第一所，减少操作步数。 */
  useEffect(() => {
    let cancelled = false;
    void getBookingHospitals(caregiverId, elder.elderId)
      .then(list => {
        if (cancelled) return;
        setHospitals(list);
        if (list.length > 0) setHospital(list[0]);
      })
      .catch(cause => {
        if (!cancelled) setError(cause instanceof Error ? cause.message : '无法读取可选医院');
      });
    return () => { cancelled = true; };
  }, [caregiverId, elder.elderId]);

  /** 读取这位长辈当前有没有进行中的预约，据此决定落到“管理视图”还是“新向导”。 */
  useEffect(() => {
    let cancelled = false;
    void getAppointments(elder.elderId)
      .then(rows => {
        if (cancelled) return;
        const current = rows.find(item => item.status === 'CONFIRMED' && item.date >= todayLocal()) ?? null;
        setUpcoming(current);
        setPhase(current ? 'manage' : 'wizard');
      })
      .catch(() => { if (!cancelled) { setUpcoming(null); setPhase('wizard'); } });
    return () => { cancelled = true; };
  }, [elder.elderId]);

  const resetSelections = () => {
    setDepartment(null);
    setDepartments(null);
    setWindows(null);
    setDate('');
    setSlot(null);
    setTransport('打车');
    setNeedTravel(true);
    setSubmitError('');
    setError('');
    setStep(0);
  };

  /** 修改向导进入时按“当前这张预约”预填医院/科室，若原日期号源还在可选范围则一并带出。 */
  useEffect(() => {
    if (phase !== 'wizard' || mode !== 'modify' || !hospitals || !upcoming || prefillDone.current) return;
    const matchHospital = hospitals.find(item => item.name === upcoming.hospital) ?? null;
    if (!matchHospital) { prefillDone.current = true; return; }
    let cancelled = false;
    void (async () => {
      setHospital(matchHospital);
      setDepartments(null);
      setWindows(null);
      setDate('');
      setSlot(null);
      try {
        const departments = await getBookingDepartments(caregiverId, elder.elderId, matchHospital.id);
        if (cancelled) return;
        setDepartments(departments);
        const matchDepartment = departments.find(item => item.name === upcoming.department) ?? null;
        if (!matchDepartment) { prefillDone.current = true; return; }
        setDepartment(matchDepartment);
        const windows = await getBookingWindows(caregiverId, elder.elderId, matchHospital.id, matchDepartment.id);
        if (cancelled) return;
        setWindows(windows);
        const day = windows.find(item => item.date === upcoming.date);
        if (day) {
          setDate(day.date);
          const sameSlot = day.slots.find(item => sameTime(item.time, upcoming.time));
          if (sameSlot) setSlot(sameSlot);
        }
      } catch {
        // 预填失败就保持默认：医院已在，其余在向导里手动选仍可继续
      } finally {
        prefillDone.current = true;
      }
    })();
    return () => { cancelled = true; };
  }, [phase, mode, hospitals, upcoming, caregiverId, elder.elderId]);

  /** 进“新代约”向导。 */
  const beginCreate = () => {
    setMode('create');
    setWillAccompany(false);
    prefillDone.current = false;
    resetSelections();
    setPhase('wizard');
  };

  /** 从管理视图点“修改”：进向导改当前这张预约（默认带出当前医院/科室，只改想改的）。 */
  const beginModify = () => {
    setMode('modify');
    setWillAccompany(upcoming?.accompaniedBy === caregiverId);
    prefillDone.current = false;
    resetSelections();
    setPhase('wizard');
  };

  /** 只切换“我是否陪同这次复诊”，不触碰预约本身。 */
  const changeAccompany = async (value: boolean) => {
    if (accompanySaving) return;
    setAccompanySaving(true);
    setAccompanyError('');
    try {
      const updated = await setBookingAccompany(caregiverId, elder.elderId, value);
      setUpcoming(current => (current ? { ...current, accompaniedBy: updated.accompaniedBy } : current));
    } catch (cause) {
      setAccompanyError(cause instanceof Error ? cause.message : '保存陪同状态失败，请稍后重试');
    } finally {
      setAccompanySaving(false);
    }
  };

  /** 取消当前这张进行中的预约，随后可重新代约。 */
  const cancelCurrent = async () => {
    if (!upcoming) return;
    setCancelling(true);
    setCancelError('');
    try {
      await cancelElderBooking(caregiverId, elder.elderId);
      setNotice(`已取消${elder.name}的原复诊预约，如需新的安排请在下面重新代约。`);
      setUpcoming(null);
      setConfirmingCancel(false);
      beginCreate();
    } catch (cause) {
      setCancelError(cause instanceof Error ? cause.message : '取消预约失败，请稍后重试');
    } finally {
      setCancelling(false);
    }
  };

  /* ---------- 向导步骤操作 ---------- */
  const pickHospital = (item: BookingHospital) => {
    if (item.id === hospital?.id) return;
    setHospital(item);
    setDepartment(null);
    setDepartments(null);
    setWindows(null);
    setDate('');
    setSlot(null);
    setSubmitError('');
  };

  const openDepartmentStep = async () => {
    if (!hospital) return;
    setStep(1);
    setDepartments(null);
    setError('');
    try {
      setDepartments(await getBookingDepartments(caregiverId, elder.elderId, hospital.id));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法读取可选科室');
    }
  };

  const pickDepartment = (item: BookingDepartment) => {
    setDepartment(item);
    setWindows(null);
    setDate('');
    setSlot(null);
    setSubmitError('');
  };

  const openWindowStep = async () => {
    if (!hospital || !department) return;
    setStep(2);
    setWindows(null);
    setError('');
    try {
      setWindows(await getBookingWindows(caregiverId, elder.elderId, hospital.id, department.id));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法读取可选日期');
    }
  };

  const goBack = () => {
    setSubmitError('');
    setError('');
    if (step === 0) {
      if (mode === 'modify') setPhase('manage');
      else onBack();
      return;
    }
    if (step === 3) setStep(2);
    else setStep(prev => prev - 1);
  };

  /** 顶部分步随时可点：回已走步骤直接看；跳未走步骤时若前置选择没补齐，先落到需要补的页面。 */
  const goToStep = (target: number) => {
    if (submitting) return;
    setSubmitError('');
    setError('');
    if (target < step) { setStep(target); return; }
    if (target === step) return;
    if (target >= 1 && !hospital) { setStep(0); return; }
    if (target >= 2 && !department) { departments ? setStep(1) : void openDepartmentStep(); return; }
    if (target >= 3 && !(date !== '' && slot)) { windows ? setStep(2) : void openWindowStep(); return; }
    if (target === 1) { departments ? setStep(1) : void openDepartmentStep(); return; }
    if (target === 2) { windows ? setStep(2) : void openWindowStep(); return; }
    setStep(3);
  };

  const readyForNext = (): boolean => {
    if (step === 0) return hospital != null;
    if (step === 1) return department != null;
    if (step === 2) return date !== '' && slot != null;
    return false;
  };

  const advance = () => {
    if (step === 0) void openDepartmentStep();
    else if (step === 1) void openWindowStep();
    else if (step === 2) setStep(3);
  };

  const submit = async () => {
    if (!hospital || !department || !slot || !date) return;
    setSubmitting(true);
    setSubmitError('');
    const request = {
      hospitalId: hospital.id,
      departmentId: department.id,
      date,
      slotId: slot.slotId,
      needTravel,
      transport,
      willAccompany,
    };
    try {
      if (mode === 'modify') {
        await modifyBooking(caregiverId, elder.elderId, request);
        setNotice(`已把${elder.name}的复诊改到新的时间，可在下面查看或继续调整。`);
      } else {
        await createBooking(caregiverId, elder.elderId, request);
        setNotice(`已为${elder.name}约好${department.name}复诊，可在下面查看或继续调整。`);
      }
      const rows = await getAppointments(elder.elderId);
      const current = rows.find(item => item.status === 'CONFIRMED' && item.date >= todayLocal()) ?? null;
      setUpcoming(current);
      setDetail(null);
      setPhase('manage');
    } catch (cause) {
      setSubmitError(cause instanceof Error ? cause.message : '提交失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  const recap = (): string => {
    const bits = [hospital?.name, department?.name];
    return bits.filter(Boolean).join(' · ');
  };

  const stepLoading = (step === 1 && !departments && !error) || (step === 2 && !windows && !error);

  /* ---------- 复诊详情（只读） ---------- */
  if (detail) {
    return <CareAppointmentDetailView caregiverId={caregiverId} elder={elder} appointment={detail} onBack={() => setDetail(null)} />;
  }

  /* ---------- 读取预约情况 ---------- */
  if (upcoming === undefined || phase === 'loading') {
    return (
      <main className="space-y-5 px-5 pb-8 pt-5">
        <PageHeader title="帮助预约" subtitle={`为${relationship(elder)}安排复诊`} onBack={onBack} hideHelp />
        <section className="grid min-h-64 place-items-center rounded-3xl border bg-card">
          <div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-8 animate-spin" /><p className="mt-3 text-lg">正在查看{elder.name}的预约情况…</p></div>
        </section>
      </main>
    );
  }

  /* ---------- 现有安排：管理视图 ---------- */
  if (phase === 'manage' && upcoming) {
    const participating = upcoming.accompaniedBy === caregiverId;
    return (
      <>
        <main className="space-y-5 px-5 pb-40 pt-5">
          <PageHeader title="帮助预约" onBack={onBack} hideHelp />

          {notice && (
            <p className="flex items-start gap-2 rounded-2xl bg-green-50 px-4 py-3 text-[15px] font-semibold text-green-800 ring-1 ring-green-200">
              <Check className="mt-0.5 size-5 shrink-0" />{notice}
            </p>
          )}

          <p className="flex items-center gap-2 text-lg font-bold">
            <CalendarDays className="size-5 text-primary" />{elder.name}已有一个进行中的复诊
          </p>

          <section className="rounded-3xl border bg-card p-5 shadow-sm">
            <div className="flex gap-4">
              <div className="grid min-w-16 place-items-center rounded-2xl bg-secondary px-2 py-2 text-center text-secondary-foreground">
                <strong className="text-2xl">{Number(upcoming.date.split('-')[2])}</strong><span className="text-sm">{Number(upcoming.date.split('-')[1])}月</span>
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-xl font-bold">{formatHM(upcoming.time)} {formatDate(upcoming.date)} {weekdayOf(upcoming.date)} 复诊</p>
                <p className="mt-1 text-base">{upcoming.hospital} · {upcoming.department}</p>
                {upcoming.arrangedLabel && <p className="mt-1 text-sm text-muted-foreground">由{upcoming.arrangedLabel}约好</p>}
              </div>
            </div>
            {participating && upcoming.departureAt && (
              <p className="mt-4 flex items-center gap-2 border-t border-border/70 pt-4 text-sm text-muted-foreground"><Route className="size-4 shrink-0 text-primary" />建议 {formatHM(upcoming.departureAt)} 出发{upcoming.transport ? ` · ${upcoming.transport}` : ''}</p>
            )}
            <div className="mt-4 flex items-center justify-between gap-3 border-t border-border/70 pt-4">
              <p className="min-w-0 text-base font-semibold">您会陪同这次复诊吗</p>
              <div className="flex shrink-0 gap-2">
                <button
                  type="button"
                  disabled={accompanySaving}
                  onClick={() => void changeAccompany(true)}
                  className={`min-h-11 rounded-2xl border px-4 text-base font-bold transition active:scale-95 disabled:opacity-50 ${participating ? 'border-primary bg-primary text-white' : 'bg-white text-foreground'}`}
                >
                  会陪同
                </button>
                <button
                  type="button"
                  disabled={accompanySaving}
                  onClick={() => void changeAccompany(false)}
                  className={`min-h-11 rounded-2xl border px-4 text-base font-bold transition active:scale-95 disabled:opacity-50 ${participating ? 'bg-white text-foreground' : 'border-primary bg-primary text-white'}`}
                >
                  不陪同
                </button>
              </div>
            </div>
            {accompanyError && (
              <p className="mt-3 flex items-start gap-2 rounded-2xl bg-red-50 px-4 py-2.5 text-[15px] font-semibold text-red-700 ring-1 ring-red-200">
                <TriangleAlert className="mt-0.5 size-4 shrink-0" />{accompanyError}
              </p>
            )}
            <button type="button" onClick={() => setDetail(upcoming)} className="mt-4 flex min-h-12 w-full items-center justify-center gap-1 rounded-2xl bg-muted px-4 text-base font-bold text-foreground active:scale-[0.99]">
              查看详情<ChevronRight className="size-5" />
            </button>
          </section>

          <div className="grid gap-3">
            <button type="button" onClick={beginModify} className="flex min-h-14 items-center gap-3 rounded-2xl border bg-card px-5 text-left text-lg font-bold shadow-sm active:scale-[0.99]">
              <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-secondary text-primary"><Pencil className="size-5" /></span>
              <span className="min-w-0 flex-1">修改</span>
              <ChevronRight className="size-5 text-muted-foreground" />
            </button>
            <button type="button" onClick={() => { setConfirmingCancel(value => !value); setCancelError(''); }} className="flex min-h-14 items-center gap-3 rounded-2xl border border-[#f0c0b5] bg-[#fdeeea] px-5 text-left text-lg font-bold text-[#a1452f] shadow-sm active:scale-[0.99]">
              <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-white text-[#a1452f]"><CalendarDays className="size-5" /></span>
              <span className="min-w-0 flex-1">取消这次预约</span>
              <ChevronRight className="size-5" />
            </button>
          </div>

          {confirmingCancel && (
            <section className="rounded-3xl border border-[#f0c0b5] bg-[#fff5f2] p-5 shadow-sm">
              <p className="text-lg font-bold text-[#a1452f]">确认要取消这次复诊吗？</p>
              <p className="mt-2 text-base leading-7 text-muted-foreground">取消后号源会立即释放、已创建的复诊提醒一并停用，且此操作不可撤销；如需新的安排，可在下方重新代约。</p>
              {cancelError && (
                <p className="mt-3 flex items-start gap-2 rounded-2xl bg-red-50 px-4 py-3 text-[15px] font-semibold text-red-700 ring-1 ring-red-200">
                  <TriangleAlert className="mt-0.5 size-5 shrink-0" />{cancelError}
                </p>
              )}
              <div className="mt-4 flex gap-3">
                <button type="button" onClick={() => { setConfirmingCancel(false); setCancelError(''); }} disabled={cancelling} className="min-h-12 flex-1 rounded-2xl bg-muted px-4 text-base font-bold text-muted-foreground active:scale-[0.99] disabled:opacity-40">再想想</button>
                <button type="button" onClick={() => void cancelCurrent()} disabled={cancelling} className="flex min-h-12 flex-1 items-center justify-center gap-2 rounded-2xl bg-[#a1452f] px-4 text-base font-bold text-white active:scale-[0.99] disabled:opacity-40">
                  {cancelling ? <><LoaderCircle className="size-5 animate-spin" />正在取消…</> : '确认取消'}
                </button>
              </div>
            </section>
          )}
        </main>

        <footer className="fixed bottom-0 left-1/2 z-40 flex w-full max-w-[448px] -translate-x-1/2 items-center border-t border-border/70 bg-card/95 px-5 pb-[max(1rem,env(safe-area-inset-bottom))] pt-3 shadow-[0_-6px_24px_rgb(0_0_0/6%)] backdrop-blur">
          <button type="button" onClick={onFinished} className="flex min-h-14 w-full items-center justify-center gap-2 rounded-2xl bg-primary text-lg font-bold text-white shadow-lg shadow-primary/25 active:scale-[0.99]">
            完成，回到首页<ChevronRight className="size-5" />
          </button>
        </footer>
      </>
    );
  }

  /* ---------- 向导（新建 / 修改） ---------- */
  return (
    <>
      <main className="space-y-5 px-5 pb-44 pt-5">
        <PageHeader
          title="帮助预约"
          subtitle={mode === 'modify' ? `修改${relationship(elder)}的复诊` : `为${relationship(elder)}安排复诊`}
          onBack={goBack}
          hideHelp
        />

        {mode === 'modify' && (
          <button type="button" onClick={() => { setSubmitError(''); setError(''); setPhase('manage'); }} className="flex min-h-10 items-center gap-1 rounded-full bg-muted px-4 text-sm font-bold text-muted-foreground active:scale-[0.98]">
            <ChevronLeft className="size-4" />返回现有安排
          </button>
        )}

        <ol className="flex items-center gap-2 text-sm font-semibold" aria-label="预约步骤">
          {['医院', '科室', '日期号源', '确认'].map((label, index) => {
            const active = index === step;
            const reached = index <= step;
            return (
              <li key={label}>
                <button
                  type="button"
                  onClick={() => goToStep(index)}
                  aria-current={active ? 'step' : undefined}
                  className={`rounded-full px-3 py-1.5 transition active:scale-[0.98] ${reached ? (active ? 'bg-primary text-white' : 'bg-secondary text-secondary-foreground hover:bg-secondary/70') : 'bg-muted text-muted-foreground hover:bg-muted/70'}`}
                >
                  {index + 1} {label}
                </button>
              </li>
            );
          })}
        </ol>

        {error && step !== 3 && (
          <p className="flex items-start gap-2 rounded-2xl bg-red-50 px-4 py-3 text-[15px] font-semibold text-red-700 ring-1 ring-red-200">
            <TriangleAlert className="mt-0.5 size-5 shrink-0" />{error}
            <button type="button" onClick={step === 1 ? () => void openDepartmentStep() : () => void openWindowStep()} className="ml-auto shrink-0 text-red-800 underline">重试</button>
          </p>
        )}

        {stepLoading && (
          <section className="grid min-h-64 place-items-center rounded-3xl border bg-card">
            <div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-8 animate-spin" /><p className="mt-3 text-lg">正在读取可选号源…</p></div>
          </section>
        )}

        {step === 0 && hospitals && (
          <section className="grid gap-3">
            <p className="text-lg font-bold">选择医院</p>
            {hospitals.map(item => {
              const selected = hospital?.id === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => pickHospital(item)}
                  className={`flex items-start gap-4 rounded-3xl border p-4 text-left shadow-sm transition active:scale-[0.99] ${selected ? 'border-primary/70 bg-[#fff0dc] ring-1 ring-primary/30' : 'bg-card'}`}
                >
                  <span className={`grid size-12 shrink-0 place-items-center rounded-2xl ${selected ? 'bg-primary text-white' : 'bg-secondary text-primary'}`}>
                    <Hospital className="size-6" aria-hidden="true" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <strong className="block text-lg">{item.name}</strong>
                    <span className="mt-0.5 block text-sm text-muted-foreground">{item.level}</span>
                    <span className="mt-1 block text-sm text-muted-foreground">{item.address}</span>
                  </span>
                  {selected && <Check className="mt-1 size-6 shrink-0 text-primary" aria-hidden="true" />}
                </button>
              );
            })}
          </section>
        )}

        {step === 1 && departments && (
          <section className="grid gap-3">
            <p className="truncate text-lg text-muted-foreground">{hospital?.name} · 选择科室</p>
            {departments.map(item => {
              const selected = department?.id === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => pickDepartment(item)}
                  className={`rounded-3xl border p-4 text-left shadow-sm transition active:scale-[0.99] ${selected ? 'border-primary/70 bg-[#fff0dc] ring-1 ring-primary/30' : 'bg-card'}`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <strong className="text-lg">{item.name}</strong>
                    {selected && <Check className="size-6 text-primary" aria-hidden="true" />}
                  </div>
                  {item.followupScope && <p className="mt-1 text-sm text-muted-foreground">{item.followupScope}</p>}
                  {item.location && <p className="mt-1 text-sm text-muted-foreground">就诊位置：{item.location}</p>}
                </button>
              );
            })}
          </section>
        )}

        {step === 2 && windows && (
          <section className="grid gap-4">
            <p className="truncate text-lg text-muted-foreground">{recap()} · 选择日期与号源</p>
            {windows.length === 0 && (
              <section className="rounded-3xl border bg-card px-6 py-10 text-center shadow-sm">
                <CalendarDays className="mx-auto size-10 text-muted-foreground" />
                <h2 className="mt-4 text-xl font-bold">近期暂无号源</h2>
                <p className="mt-2 text-lg leading-8 text-muted-foreground">请返回更换科室，或稍后再来查看。</p>
              </section>
            )}
            {windows.map(window => {
              const daySelected = date === window.date;
              return (
                <section key={window.date} className={`rounded-3xl border p-4 shadow-sm transition ${daySelected ? 'border-primary/70 bg-[#fff0dc] ring-1 ring-primary/30' : 'bg-card'}`}>
                  <p className="text-lg font-bold">{formatDate(window.date)} <span className="ml-1 text-base font-normal text-muted-foreground">{weekdayOf(window.date)}</span></p>
                  <div className="mt-3 flex flex-wrap gap-2">
                    {window.slots.map(option => {
                      const picked = daySelected && slot?.slotId === option.slotId;
                      return (
                        <button
                          key={option.slotId}
                          type="button"
                          onClick={() => { setDate(window.date); setSlot(option); setSubmitError(''); }}
                          className={`flex min-h-12 items-center gap-1.5 rounded-2xl border px-4 text-base font-bold transition active:scale-95 ${picked ? 'border-primary bg-primary text-white' : 'bg-white text-foreground'}`}
                        >
                          <Clock3 className="size-4" aria-hidden="true" />{formatHM(option.time)}
                        </button>
                      );
                    })}
                  </div>
                </section>
              );
            })}
          </section>
        )}

        {step === 3 && hospital && department && slot && (
          <section className="space-y-5">
            <section className="rounded-3xl border bg-card p-5 shadow-sm">
              <h2 className="flex items-center gap-2 text-lg font-bold"><CalendarDays className="size-5 text-primary" />本次复诊安排</h2>
              <div className="mt-3 flex gap-4">
                <div className="grid min-w-16 place-items-center rounded-2xl bg-secondary px-2 py-2 text-center text-secondary-foreground">
                  <strong className="text-2xl">{Number(date.split('-')[2])}</strong>
                  <span className="text-sm">{Number(date.split('-')[1])}月</span>
                </div>
                <div className="min-w-0 flex-1">
                  <p className="text-xl font-bold">{formatHM(slot.time)} {formatDate(date)} {weekdayOf(date)} 复诊</p>
                  <p className="mt-1 text-base">{hospital.name}</p>
                  <p className="mt-1 text-base text-muted-foreground">{department.name} · 就诊位置：{department.location ?? '以医院指引为准'}</p>
                </div>
              </div>
            </section>

            <section className="rounded-3xl border bg-card p-5 shadow-sm">
              <h2 className="flex items-center gap-2 text-lg font-bold"><Route className="size-5 text-primary" />出行与提醒</h2>

              <p className="mt-4 flex items-center gap-2 text-base font-semibold"><UsersRound className="size-5 text-primary" />这次复诊您会陪同就诊吗？</p>
              <div className="mt-2 flex flex-wrap gap-2">
                {([{ value: true, label: '会陪同' }, { value: false, label: '不陪同' }] as const).map(item => (
                  <button
                    key={item.label}
                    type="button"
                    onClick={() => setWillAccompany(item.value)}
                    className={`min-h-12 rounded-2xl border px-4 text-base font-bold transition active:scale-95 ${willAccompany === item.value ? 'border-primary bg-primary text-white' : 'bg-white text-foreground'}`}
                  >
                    {item.label}
                  </button>
                ))}
              </div>

              <p className="mt-4 text-base font-semibold">去医院的交通方式</p>
              <div className="mt-2 flex flex-wrap gap-2">
                {TRANSPORTS.map(item => (
                  <button
                    key={item}
                    type="button"
                    onClick={() => setTransport(item)}
                    className={`min-h-12 rounded-2xl border px-4 text-base font-bold transition active:scale-95 ${transport === item ? 'border-primary bg-primary text-white' : 'bg-white text-foreground'}`}
                  >
                    {item}
                  </button>
                ))}
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={needTravel}
                onClick={() => setNeedTravel(value => !value)}
                className="mt-4 flex w-full items-center gap-4 rounded-2xl bg-muted/60 p-4 text-left"
              >
                <span className="min-w-0 flex-1">
                  <strong className="block text-base">按计划出发当天提醒</strong>
                  <span className="mt-0.5 block text-sm text-muted-foreground">开：前一天提醒准备材料，出发前按交通时间再提醒一次</span>
                </span>
                <span className={`relative h-8 w-14 shrink-0 rounded-full transition ${needTravel ? 'bg-primary' : 'bg-muted-foreground/40'}`}>
                  <span className={`absolute top-1 size-6 rounded-full bg-white shadow transition-all ${needTravel ? 'left-7' : 'left-1'}`} />
                </span>
              </button>
            </section>

            {submitError && (
              <p className="flex items-start gap-2 rounded-2xl bg-red-50 px-4 py-3 text-[15px] font-semibold text-red-700 ring-1 ring-red-200">
                <TriangleAlert className="mt-0.5 size-5 shrink-0" />{submitError}
              </p>
            )}
          </section>
        )}

        {!hospitals && !error && step === 0 && (
          <section className="grid min-h-64 place-items-center rounded-3xl border bg-card">
            <div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-8 animate-spin" /><p className="mt-3 text-lg">正在读取可选医院…</p></div>
          </section>
        )}
        {!hospitals && error && step === 0 && (
          <section className="rounded-3xl border bg-card p-6 text-center">
            <p className="text-lg">{error}</p>
            <button type="button" onClick={() => window.location.reload()} className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white">重新读取</button>
          </section>
        )}
      </main>

      <footer className="fixed bottom-0 left-1/2 z-40 flex w-full max-w-[448px] -translate-x-1/2 items-center gap-3 border-t border-border/70 bg-card/95 px-5 pb-[max(1rem,env(safe-area-inset-bottom))] pt-3 shadow-[0_-6px_24px_rgb(0_0_0/6%)] backdrop-blur">
        <button
          type="button"
          onClick={goBack}
          className="flex min-h-14 items-center gap-1 rounded-2xl bg-muted px-5 text-lg font-bold text-muted-foreground active:scale-[0.98]"
        >
          <ChevronLeft className="size-5" />{step === 0 ? (mode === 'modify' ? '返回现有安排' : '返回') : '上一步'}
        </button>
        <button
          type="button"
          onClick={step === 3 ? () => void submit() : advance}
          disabled={(step < 3 && !readyForNext()) || submitting || stepLoading}
          className="flex min-h-14 min-w-0 flex-1 items-center justify-center gap-2 rounded-2xl bg-primary px-4 text-lg font-bold text-white shadow-lg shadow-primary/25 active:scale-[0.99] disabled:opacity-40"
        >
          {submitting ? <><LoaderCircle className="size-5 animate-spin" />{mode === 'modify' ? '正在修改…' : '正在代约…'}</> : step === 3 ? <>{mode === 'modify' ? '确认修改' : '确认代约'}<ChevronRight className="size-5" /></> : <>下一步<ChevronRight className="size-5" /></>}
        </button>
      </footer>
    </>
  );
}
