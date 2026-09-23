'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { CalendarDays, Check, ChevronLeft, ChevronRight, ClipboardCheck, Clock3, Hospital, LoaderCircle, Pencil, RefreshCw, Route, TriangleAlert, UsersRound } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { getAppointments } from '@/lib/appointment-api';
import { cancelElderBooking, createBooking, getBookingDepartments, getBookingHospitals, getBookingWindows, modifyBooking, prepareBooking, prepareCancelBooking, prepareModifyBooking, setBookingAccompany } from '@/lib/care-api';
import type {
  AppointmentSummary,
  BookingDateWindow,
  BookingDepartment,
  BookingHospital,
  BookingPreview,
  BookingSlotOption,
  CareElder,
  CreateBookingRequest,
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

/**
 * 医院名的两种写法要能对上：可选医院列表来自目录接口，名字里的「（模拟）」被后端去掉了；
 * 而预约卡片上的医院名直接来自号源记录，带着这个后缀。不做归一化的话
 * `hospitals.find(item => item.name === upcoming.hospital)` 永远找不到，
 * 改期向导就悄悄放弃了预填——长辈的预约在 h002 时，向导会默认停在 h001。
 */
const hospitalKey = (name: string | undefined | null): string =>
  (name ?? '').replace(/[（(]模拟[）)]/g, '').trim();
const sameHospital = (a: string | undefined | null, b: string | undefined | null): boolean =>
  hospitalKey(a) === hospitalKey(b);

/**
 * 请求指纹。确认卡带着开卡时的指纹存下来，提交时只认指纹仍然对得上的那张——
 * 否则「改了陪同方式但卡还是旧的」那一瞬间点下去，提交的是新内容、用户看的是旧内容。
 */
const signatureOf = (request: CreateBookingRequest): string => [
  request.hospitalId, request.departmentId, request.date, request.slotId,
  request.transport, String(request.needTravel), String(request.willAccompany),
].join('|');

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
  /** 读「有没有进行中的预约」失败了。失败不是「没有预约」，界面上必须分开说。 */
  const [upcomingError, setUpcomingError] = useState('');
  /** 换一个号就重读一次预约情况（「重新读取」按钮用）。 */
  const [upcomingRetry, setUpcomingRetry] = useState(0);
  /** 提交成功之后的那次刷新失败了：预约已经办好，只是卡片没读回来。 */
  const [manageRefreshError, setManageRefreshError] = useState('');
  /** 第 4 步要确认卡失败后的重试号：光靠 step/选项的依赖，重试是点不动的。 */
  const [prepareRetry, setPrepareRetry] = useState(0);
  /** 向导的用途：create=新代约，modify=改当前这张。 */
  const [mode, setMode] = useState<'create' | 'modify'>('create');
  const [notice, setNotice] = useState('');
  const [detail, setDetail] = useState<AppointmentSummary | null>(null);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState('');
  const [accompanySaving, setAccompanySaving] = useState(false);
  const [accompanyError, setAccompanyError] = useState('');
  /** 第 3 步的确认卡：由后端算出「点确认后到底会发生什么」，同时带回一张票据。 */
  const [preview, setPreview] = useState<{ signature: string; card: BookingPreview } | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [previewError, setPreviewError] = useState('');
  /** 取消前的确认卡。取消不可逆，凭它才能提交。 */
  const [cancelPreview, setCancelPreview] = useState<BookingPreview | null>(null);
  const [cancelPreparing, setCancelPreparing] = useState(false);
  /** 修改向导只做一次“按当前安排预填”，避免医院/科室反复请求。 */
  const prefillDone = useRef(false);
  /** 每发起一次读取就换一个令牌；迟到的响应拿不到当前令牌就丢掉，
   *  免得切了长辈之后旧请求把列表覆盖回来。 */
  const hospitalsToken = useRef(0);
  const previewToken = useRef(0);

  /** 读取可选医院；默认选中第一所，减少操作步数。失败时留下错误提示，交给页面上的“重新读取”重试。 */
  const loadHospitals = useCallback(async () => {
    const token = ++hospitalsToken.current;
    setError('');
    try {
      const list = await getBookingHospitals(caregiverId, elder.elderId);
      if (token !== hospitalsToken.current) return;
      setHospitals(list);
      if (list.length > 0) setHospital(list[0]);
    } catch (cause) {
      if (token !== hospitalsToken.current) return;
      setError(cause instanceof Error ? cause.message : '无法读取可选医院');
    }
  }, [caregiverId, elder.elderId]);

  /**
   * 进入页面即加载可选医院。不必写清理：换了长辈就是换了一份 loadHospitals，
   * 它自己开头就会把令牌推到下一号，上一个请求回来时已经对不上了。
   */
  useEffect(() => {
    void loadHospitals();
  }, [loadHospitals]);

  /**
   * 读取这位长辈当前有没有进行中的预约，据此决定落到“管理视图”还是“新向导”。
   *
   * 读失败**绝不能**当成“没有预约”。长辈明明有一张进行中的预约、只是这次没读出来时，
   * 家属会被直接送进新建向导：一路都进不去管理视图（改期和取消两个入口等于消失），
   * 白填医院/科室/日期三步，到第 4 步才被后端一句「这位就诊人已有一个进行中的复诊预约」打回来，
   * 而他会觉得莫名其妙——刚才不是告诉我没有吗。所以失败就停在原地，说清楚并给一个重读。
   */
  useEffect(() => {
    let cancelled = false;
    void getAppointments(elder.elderId)
      .then(rows => {
        if (cancelled) return;
        const current = rows.find(item => item.status === 'CONFIRMED' && item.date >= todayLocal()) ?? null;
        setUpcoming(current);
        setUpcomingError('');
        setPhase(current ? 'manage' : 'wizard');
      })
      .catch(cause => {
        if (cancelled) return;
        // upcoming 就停在 undefined：那是「还没读到」，和 null（确实没有）不是一件事
        setUpcomingError(cause instanceof Error ? cause.message : '无法读取预约情况');
      });
    return () => { cancelled = true; };
  }, [elder.elderId, upcomingRetry]);

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

  /** 眼下这套选择对应的提交体；四项没选齐就是 null（第 3 步到不了这里）。 */
  const buildRequest = (): CreateBookingRequest | null => {
    if (!hospital || !department || !slot || !date) return null;
    return {
      hospitalId: hospital.id,
      departmentId: department.id,
      date,
      slotId: slot.slotId,
      needTravel,
      transport,
      willAccompany,
    };
  };

  const currentRequest = buildRequest();
  /** 当前可用的确认卡：指纹对得上才算数。 */
  const card = preview && currentRequest && preview.signature === signatureOf(currentRequest) ? preview.card : null;

  /**
   * 进第 3 步（以及在这一步上改动任一选项）就向后端要一次确认卡。
   * 选项一改，旧票据在后端也就作废了，必须重新要一张。
   *
   * <p>同样不需要清理函数：上面那句 `++previewToken.current` 就是作废旧请求的地方，
   * 每次重跑都会把令牌推走。
   */
  useEffect(() => {
    if (phase !== 'wizard' || step !== 3) return;
    if (!hospital || !department || !slot || !date) return;
    const request: CreateBookingRequest = {
      hospitalId: hospital.id,
      departmentId: department.id,
      date,
      slotId: slot.slotId,
      needTravel,
      transport,
      willAccompany,
    };
    const token = ++previewToken.current;
    setPreparing(true);
    setPreviewError('');
    const call = mode === 'modify'
      ? prepareModifyBooking(caregiverId, elder.elderId, request)
      : prepareBooking(caregiverId, elder.elderId, request);
    void call
      .then(returned => { if (token === previewToken.current) setPreview({ signature: signatureOf(request), card: returned }); })
      .catch(cause => {
        if (token !== previewToken.current) return;
        setPreview(null);
        setPreviewError(cause instanceof Error ? cause.message : '无法生成办理确认，请稍后重试');
      })
      .finally(() => { if (token === previewToken.current) setPreparing(false); });
  }, [phase, step, mode, caregiverId, elder.elderId, hospital, department, date, slot, transport, needTravel, willAccompany, prepareRetry]);

  /** 修改向导进入时按“当前这张预约”预填医院/科室，若原日期号源还在可选范围则一并带出。 */
  useEffect(() => {
    if (phase !== 'wizard' || mode !== 'modify' || !hospitals || !upcoming || prefillDone.current) return;
    const matchHospital = hospitals.find(item => sameHospital(item.name, upcoming.hospital)) ?? null;
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
    // 回执跟着向导显示了（见下面的 notice 横幅），所以这里要把上一条清掉：
    // 刚约好那句是上一次动作的回执，站在“改期”向导里看着它只会让人以为改期已经完成了。
    setNotice('');
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

  /**
   * 展开/收起取消确认区。展开时先要一张取消确认卡——取消是唯一不可逆的动作，
   * 号源一放出去就可能被别人抢走，得让家属看清要取消的确实是眼下这一张。
   */
  const loadCancelPreview = async () => {
    setCancelError('');
    setCancelPreview(null);
    setCancelPreparing(true);
    try {
      setCancelPreview(await prepareCancelBooking(caregiverId, elder.elderId));
    } catch (cause) {
      setCancelError(cause instanceof Error ? cause.message : '无法生成取消确认，请稍后重试');
    } finally {
      setCancelPreparing(false);
    }
  };

  const toggleCancelPanel = async () => {
    if (confirmingCancel) {
      setConfirmingCancel(false);
      setCancelError('');
      setCancelPreview(null);
      return;
    }
    setConfirmingCancel(true);
    await loadCancelPreview();
  };

  /** 取消当前这张进行中的预约，随后可重新代约。 */
  const cancelCurrent = async () => {
    if (!upcoming || !cancelPreview) return;
    setCancelling(true);
    setCancelError('');
    try {
      await cancelElderBooking(caregiverId, elder.elderId, cancelPreview.confirmationId);
      setNotice(`已取消${elder.name}的原复诊预约，如需新的安排请在下面重新代约。`);
      setUpcoming(null);
      setConfirmingCancel(false);
      setCancelPreview(null);
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

  /**
   * 进第 3 步时先把旧确认卡丢掉。选项没变的话指纹仍然对得上，不清就会有一次渲染
   * 拿着上一轮已经作废的票据——那一下点下去，后端只会回一句「确认已失效」。
   */
  const goToConfirmStep = () => {
    setPreview(null);
    setPreviewError('');
    setStep(3);
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
    goToConfirmStep();
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
    else if (step === 2) goToConfirmStep();
  };

  const submit = async () => {
    const request = buildRequest();
    // 没有对得上的确认卡就不发：那张卡的票据才是后端放行的凭据
    if (!request || !card || !department) return;
    setSubmitting(true);
    setSubmitError('');
    try {
      if (mode === 'modify') {
        await modifyBooking(caregiverId, elder.elderId, request, card.confirmationId);
        setNotice(`已把${elder.name}的复诊改到新的时间，可在下面查看或继续调整。`);
      } else {
        await createBooking(caregiverId, elder.elderId, request, card.confirmationId);
        setNotice(`已为${elder.name}约好${department.name}复诊，可在下面查看或继续调整。`);
      }
    } catch (cause) {
      setSubmitError(cause instanceof Error ? cause.message : '提交失败，请稍后重试');
      setSubmitting(false);
      return;
    }
    // 走到这里预约已经写进去了（票据也在后端核销了）。下面再读一次只是为了刷新管理视图，
    // 它失败不能再报成“提交失败”——家属会以为没约上，而重试只会拿到“这份确认已经失效”。
    setDetail(null);
    setPhase('manage');
    await reloadUpcoming();
    setSubmitting(false);
  };

  /**
   * 刷新管理视图。失败时**不能**把界面交回向导：向导的 step 还停在 3、选项和指纹都还在，
   * 于是那张「确认代约」按钮又是可点的——家属以为没提交成功，再点一次就是拿已被核销的票据重提。
   * 失败只在 manage 里留一个说明卡，上面由 {@code manageRefreshError} 那个分支接住。
   */
  const reloadUpcoming = async () => {
    try {
      const rows = await getAppointments(elder.elderId);
      setUpcoming(rows.find(item => item.status === 'CONFIRMED' && item.date >= todayLocal()) ?? null);
      setManageRefreshError('');
    } catch (cause) {
      setUpcoming(null);
      setManageRefreshError(cause instanceof Error ? cause.message : '没能读回刚办好的这张预约');
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
        {upcomingError ? (
          <section className="rounded-3xl border bg-card p-6 text-center shadow-sm">
            <p className="text-lg">{upcomingError}</p>
            <p className="mt-2 text-base leading-7 text-muted-foreground">
              没读到{elder.name}现在有没有进行中的复诊，先别急着新建：万一已经有一张，新建到最后一步会被打回来。
            </p>
            <button type="button"
              onClick={() => { setUpcomingError(''); setUpcomingRetry(count => count + 1); }}
              className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white">
              <RefreshCw className="size-5" />重新读取
            </button>
            {/* 读不出来又不让人往前走就成了死路：家属确实知道没有时，得给他一条自己负责的路。
                upcoming 置成 null（确实没有）而不是留着 undefined（还没读到），否则这一页会一直挂在这儿。 */}
            <button type="button"
              onClick={() => { setUpcoming(null); beginCreate(); }}
              className="mt-3 block min-h-11 w-full text-base font-bold text-muted-foreground underline">
              确实没有，直接新建
            </button>
          </section>
        ) : (
          <section className="grid min-h-64 place-items-center rounded-3xl border bg-card">
            <div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-8 animate-spin" /><p className="mt-3 text-lg">正在查看{elder.name}的预约情况…</p></div>
          </section>
        )}
      </main>
    );
  }

  /* ---------- 已经提交成功，只是刷新没读回来 ---------- */
  if (phase === 'manage' && !upcoming && manageRefreshError) {
    return (
      <main className="space-y-5 px-5 pb-8 pt-5">
        <PageHeader title="帮助预约" onBack={onFinished} hideHelp />
        {notice && (
          <p className="flex items-start gap-2 rounded-2xl bg-green-50 px-4 py-3 text-[15px] font-semibold text-green-800 ring-1 ring-green-200">
            <Check className="mt-0.5 size-5 shrink-0" />{notice}
          </p>
        )}
        <section className="rounded-3xl border bg-card p-6 shadow-sm">
          <p className="text-lg font-bold">这件事已经办好了</p>
          <p className="mt-2 text-base leading-7 text-muted-foreground">
            {manageRefreshError}——刚办好的这张只是没显示出来，已经生效了。别再点一次「确认」：那份确认后端已经核销过了。
          </p>
          <button type="button" onClick={() => void reloadUpcoming()} className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white">
            <RefreshCw className="size-5" />重新读取
          </button>
        </section>
        <button type="button" onClick={onFinished} className="flex min-h-14 w-full items-center justify-center gap-2 rounded-2xl bg-muted text-lg font-bold text-foreground active:scale-[0.99]">
          完成，回到首页<ChevronRight className="size-5" />
        </button>
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
            <button type="button" onClick={() => void toggleCancelPanel()} className="flex min-h-14 items-center gap-3 rounded-2xl border border-[#f0c0b5] bg-[#fdeeea] px-5 text-left text-lg font-bold text-[#a1452f] shadow-sm active:scale-[0.99]">
              <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-white text-[#a1452f]"><CalendarDays className="size-5" /></span>
              <span className="min-w-0 flex-1">取消这次预约</span>
              <ChevronRight className="size-5" />
            </button>
          </div>

          {confirmingCancel && (
            <section className="rounded-3xl border border-[#f0c0b5] bg-[#fff5f2] p-5 shadow-sm">
              <p className="text-lg font-bold text-[#a1452f]">确认要取消这次复诊吗？</p>

              {cancelPreparing && (
                <p className="mt-3 flex items-center gap-2 text-base text-muted-foreground"><LoaderCircle className="size-5 animate-spin" />正在核对要取消的是哪一张…</p>
              )}

              {cancelPreview && (
                <>
                  <p className="mt-2 text-base leading-7 text-muted-foreground">要取消的是{elder.name}名下这一张：</p>
                  <p className="mt-1 text-base font-bold">{cancelPreview.hospital} · {cancelPreview.department}</p>
                  <p className="text-base font-bold">{formatHM(cancelPreview.time)} {formatDate(cancelPreview.date)} {weekdayOf(cancelPreview.date)} · {cancelPreview.arrangement}</p>
                  <ul className="mt-3 space-y-2 border-t border-[#f0c0b5] pt-3">
                    {cancelPreview.operations.map(item => (
                      <li key={item} className="flex items-start gap-2 text-base text-muted-foreground"><TriangleAlert className="mt-1 size-4 shrink-0 text-[#a1452f]" />{item}</li>
                    ))}
                  </ul>
                </>
              )}

              {!cancelPreview && !cancelPreparing && (
                <p className="mt-2 text-base leading-7 text-muted-foreground">取消后号源会立即释放、已创建的复诊提醒一并停用，且此操作不可撤销；如需新的安排，可在下方重新代约。</p>
              )}

              {cancelError && (
                <p className="mt-3 flex items-start gap-2 rounded-2xl bg-red-50 px-4 py-3 text-[15px] font-semibold text-red-700 ring-1 ring-red-200">
                  <TriangleAlert className="mt-0.5 size-5 shrink-0" />{cancelError}
                </p>
              )}
              <div className="mt-4 flex gap-3">
                <button type="button" onClick={() => { setConfirmingCancel(false); setCancelError(''); setCancelPreview(null); }} disabled={cancelling} className="min-h-12 flex-1 rounded-2xl bg-muted px-4 text-base font-bold text-muted-foreground active:scale-[0.99] disabled:opacity-40">再想想</button>
                <button type="button" onClick={() => void cancelCurrent()} disabled={cancelling || !cancelPreview} className="flex min-h-12 flex-1 items-center justify-center gap-2 rounded-2xl bg-[#a1452f] px-4 text-base font-bold text-white active:scale-[0.99] disabled:opacity-40">
                  {cancelling ? <><LoaderCircle className="size-5 animate-spin" />正在取消…</> : '确认取消'}
                </button>
              </div>
              {!cancelPreview && !cancelPreparing && (
                <button type="button" onClick={() => void loadCancelPreview()} className="mt-3 w-full text-base font-bold text-[#a1452f] underline">重新核对</button>
              )}
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

        {/* 取消成功的回执挂在这里，不只是管理视图上：取消成功那一下正好就把界面切进了向导，
            管理视图连同它的回执一起不渲染了——取消是唯一不可逆的动作，办成了却一声不吭最吓人。 */}
        {notice && (
          <p className="flex items-start gap-2 rounded-2xl bg-green-50 px-4 py-3 text-[15px] font-semibold text-green-800 ring-1 ring-green-200">
            <Check className="mt-0.5 size-5 shrink-0" />{notice}
          </p>
        )}

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

        {/* 第 0 步不挂这条横幅：这里的 error 是「读不到可选医院」，重试要走 loadHospitals，
            可这条横幅按 step 1/2 分派重试、第 0 步会落到 openWindowStep，那函数张口就 return
            （还没有医院），按钮看着能点其实什么也不做。第 0 步下面本来就有一张带「重新读取」的错误卡。 */}
        {error && step !== 0 && step !== 3 && (
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

            <section className="rounded-3xl border bg-card p-5 shadow-sm">
              <h2 className="flex items-center gap-2 text-lg font-bold"><ClipboardCheck className="size-5 text-primary" />确认后会发生什么</h2>

              {preparing && (
                <p className="mt-3 flex items-center gap-2 text-base text-muted-foreground"><LoaderCircle className="size-5 animate-spin" />正在核对这次要办理的内容…</p>
              )}

              {/* 提示自己写着「请稍后重试」，那就得给一处能重试的地方：确认卡出不来时
                  「确认代约」按钮是灰的，点顶上的分步条也不管用（step 没变，那个 effect 不会重跑），
                  不给按钮就是把人卡死在第 4 步。 */}
              {previewError && !preparing && (
                <p className="mt-3 flex items-start gap-2 rounded-2xl bg-red-50 px-4 py-3 text-[15px] font-semibold text-red-700 ring-1 ring-red-200">
                  <TriangleAlert className="mt-0.5 size-5 shrink-0" />{previewError}
                  <button type="button" onClick={() => setPrepareRetry(count => count + 1)} className="ml-auto shrink-0 text-red-800 underline">重试</button>
                </p>
              )}

              {card && (
                <>
                  <dl className="mt-3 space-y-1.5 text-base">
                    <div className="flex gap-2">
                      <dt className="shrink-0 text-muted-foreground">替谁办</dt>
                      <dd className="font-semibold">{card.serviceSubject}</dd>
                    </div>
                    <div className="flex gap-2">
                      <dt className="shrink-0 text-muted-foreground">预约归属</dt>
                      <dd className="min-w-0 font-semibold">{card.arrangement}</dd>
                    </div>
                  </dl>
                  <ul className="mt-3 space-y-2 border-t border-border/70 pt-3">
                    {card.operations.map(item => (
                      <li key={item} className="flex items-start gap-2 text-base text-muted-foreground">
                        <Check className="mt-1 size-4 shrink-0 text-primary" />{item}
                      </li>
                    ))}
                  </ul>
                </>
              )}
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
            <button type="button" onClick={() => void loadHospitals()} className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white">重新读取</button>
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
          disabled={(step < 3 && !readyForNext()) || submitting || stepLoading || (step === 3 && !card)}
          className="flex min-h-14 min-w-0 flex-1 items-center justify-center gap-2 rounded-2xl bg-primary px-4 text-lg font-bold text-white shadow-lg shadow-primary/25 active:scale-[0.99] disabled:opacity-40"
        >
          {submitting
            ? <><LoaderCircle className="size-5 animate-spin" />{mode === 'modify' ? '正在修改…' : '正在代约…'}</>
            : step === 3
              ? preparing ? <><LoaderCircle className="size-5 animate-spin" />正在核对…</> : <>{mode === 'modify' ? '确认修改' : '确认代约'}<ChevronRight className="size-5" /></>
              : <>下一步<ChevronRight className="size-5" /></>}
        </button>
      </footer>
    </>
  );
}
