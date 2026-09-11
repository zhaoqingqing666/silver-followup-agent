'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ArrowLeft, ArrowUp, BusFront, CarFront, Check, Clock3, DoorOpen, Footprints,
  Headphones, Hospital, LoaderCircle, MapPinned, Navigation, RefreshCw,
  Route, Signpost,
} from 'lucide-react';
import { matchPageVoiceCommand } from '@/features/voice/voice-commands';
import { getAppointments, getAppointmentTravelGuide } from '@/lib/appointment-api';
import { speakText } from '@/lib/speech-service';
import type { AppointmentTravelGuide, GeoPoint, TravelFocus, VoicePreference } from '@/types/domain';

interface AMapInstance {
  setFitView: (overlays?: unknown[], immediately?: boolean, avoid?: number[]) => void;
  destroy: () => void;
}

interface AMapNamespace {
  Map: new (container: HTMLElement, options: Record<string, unknown>) => AMapInstance;
  Marker: new (options: Record<string, unknown>) => unknown;
  Polyline: new (options: Record<string, unknown>) => unknown;
}

declare global {
  interface Window {
    AMap?: AMapNamespace;
    _AMapSecurityConfig?: { securityJsCode: string };
  }
}

export function TravelGuideView({ appointmentId, initialTab = 'outside', forceSpeak = false,
                                  voicePreference, onBack, onRegisterVoice }: {
  appointmentId: string;
  initialTab?: TravelFocus;
  /** 这一轮是用户用语音主动问出来的地图：加载完立刻朗读一次，不受“自动朗读”开关限制。 */
  forceSpeak?: boolean;
  voicePreference?: VoicePreference;
  onBack: () => void;
  /** 注册本页的只读语音口令（返回、再念一遍、切换院内外）；返回 false 表示交给助手处理。 */
  onRegisterVoice?: (handler: ((text: string) => boolean) | null) => void;
}) {
  const [guide, setGuide] = useState<AppointmentTravelGuide | null>(null);
  const [tab, setTab] = useState<TravelFocus>(initialTab);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      let target = appointmentId;
      if (!target) {
        const rows = await getAppointments();
        target = rows.find(item => item.status === 'CONFIRMED')?.appointmentId ?? '';
      }
      if (!target) throw new Error('目前没有可以查看路线的已确认预约');
      setGuide(await getAppointmentTravelGuide(target));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '路线读取失败');
    } finally {
      setLoading(false);
    }
  };

  // 页面在切到“出行”时才会挂载，因此 initialTab 只用作为初始值即可，不需要额外的同步副作用。
  useEffect(() => { void load(); }, [appointmentId]);

  // 页面加载完成后，用工具返回的真实路线和院内数据自动播报；用户关闭自动朗读时不播。
  // forceSpeak 只对“这一次语音问出来的地图”生效：外层每次打开都会重新挂载本页，因此它不会被永久消费，
  // 也不会写回设置去影响其他页面的自动朗读。
  // 文案不使用大模型生成，所有事实均来自 AppointmentTravelGuide。
  useEffect(() => {
    if (!guide) return;
    if (!forceSpeak && !voicePreference?.autoSpeakEnabled) return;
    speakText(travelNarration(guide), `travel-${guide.appointmentId}`, {
      rate: voicePreference?.speechRate,
      volume: voicePreference?.speechVolume,
    });
  }, [guide, forceSpeak, voicePreference?.autoSpeakEnabled, voicePreference?.speechRate, voicePreference?.speechVolume]);

  /** 朗读一次指定内容；用户明确要求时朗读，因此不检查“自动朗读”开关。 */
  const speak = useCallback((text: string) => {
    speakText(text, `travel-${appointmentId || 'current'}`, {
      rate: voicePreference?.speechRate,
      volume: voicePreference?.speechVolume,
    });
  }, [appointmentId, voicePreference?.speechRate, voicePreference?.speechVolume]);

  const handleVoiceCommand = useCallback((text: string) => {
    const command = matchPageVoiceCommand(text);
    if (!command) return false;
    if (command === 'BACK') { onBack(); return true; }
    if (!guide) return true;
    if (command === 'INSIDE') { setTab('inside'); speak(facilityNarration(guide)); return true; }
    if (command === 'OUTSIDE') { setTab('outside'); speak(routeNarration(guide)); return true; }
    speak(travelNarration(guide));
    return true;
  }, [guide, onBack, speak]);

  useEffect(() => {
    if (!onRegisterVoice) return;
    onRegisterVoice(handleVoiceCommand);
    return () => onRegisterVoice(null);
  }, [onRegisterVoice, handleVoiceCommand]);

  return <main className="min-h-dvh bg-[#f7f1e8] pb-8">
    <header className="sticky top-0 z-40 flex min-h-[76px] items-center gap-3 border-b bg-[#fffaf3]/95 px-5 backdrop-blur">
      <button onClick={onBack} aria-label="返回事项" className="grid size-12 place-items-center rounded-2xl border bg-white"><ArrowLeft className="size-6" /></button>
      <div className="min-w-0"><h1 className="truncate text-xl font-bold">出行与到院指引</h1><p className="text-sm text-muted-foreground">路线和诊室信息均来自工具结果</p></div>
    </header>

    {loading && <div className="grid min-h-[70vh] place-items-center"><div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-9 animate-spin" /><p className="mt-3 text-lg">正在查询路线和诊室…</p></div></div>}

    {!loading && error && <section className="mx-5 mt-8 rounded-3xl border bg-white p-6 text-center shadow-sm"><MapPinned className="mx-auto size-10 text-primary" /><h2 className="mt-3 text-xl font-bold">暂时无法显示路线</h2><p className="mt-2 text-base leading-7 text-muted-foreground">{error}</p><button onClick={() => void load()} className="mt-5 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white"><RefreshCw className="size-5" />重新读取</button></section>}

    {!loading && guide && <>
      <section className="bg-gradient-to-br from-[#98502f] to-[#d17b47] px-5 pb-6 pt-5 text-white">
        <p className="text-sm text-white/75">{formatDateTime(guide.appointmentAt)}复诊</p>
        <h2 className="mt-1 text-2xl font-bold">{guide.hospital} · {guide.department}</h2>
        <p className="mt-2 flex items-center gap-2 text-base"><Hospital className="size-5" />{guide.facility.building} {guide.facility.floor} {guide.facility.room}</p>
        <p className="mt-1 text-sm text-white/80">{guide.simulated ? '比赛模拟路线数据' : '在线地图路线数据'} · 具体位置以医院现场为准</p>
      </section>

      <div className="sticky top-[76px] z-30 grid grid-cols-2 gap-2 border-b bg-[#fffaf3]/95 px-5 py-3 backdrop-blur">
        <button onClick={() => setTab('outside')} className={`min-h-12 rounded-2xl font-bold ${tab === 'outside' ? 'bg-primary text-white' : 'border bg-white'}`}>院外路线</button>
        <button onClick={() => setTab('inside')} className={`min-h-12 rounded-2xl font-bold ${tab === 'inside' ? 'bg-primary text-white' : 'border bg-white'}`}>院内指引</button>
      </div>

      {tab === 'outside' ? <OutsideGuide guide={guide} /> : <InsideGuide guide={guide} />}
    </>}
  </main>;
}

function OutsideGuide({ guide }: { guide: AppointmentTravelGuide }) {
  const route = guide.route;
  return <div className="space-y-4 px-5 py-5">
    <RouteMap points={route.polyline} origin={route.origin} destination={route.destination} />
    <section className="rounded-3xl border bg-white p-5 shadow-sm">
      <div className="flex items-center justify-between gap-4"><div><p className="text-sm text-muted-foreground">建议出发</p><strong className="text-3xl text-primary">{formatTime(route.departureAt)}</strong></div><span className="grid size-14 place-items-center rounded-2xl bg-[#fff0dc] text-primary">{transportIcon(route.transport)}</span></div>
      <div className="mt-4 grid grid-cols-2 gap-3"><Metric icon={<Clock3 />} label="预计用时" value={`${route.durationMinutes}分钟`} /><Metric icon={<Navigation />} label="路线距离" value={formatDistance(route.distanceMeters)} /></div>
      <p className="mt-4 rounded-2xl bg-[#fff7ec] p-3 text-sm leading-6 text-[#76533d]">已额外预留20分钟用于进院、报到和寻找诊室。</p>
    </section>
    <section className="rounded-3xl border bg-white p-5 shadow-sm"><div className="flex items-center gap-2"><Route className="size-6 text-primary" /><h3 className="text-xl font-bold">路线步骤</h3></div><ol className="mt-4 space-y-4">{route.steps.map((step, index) => <li key={step} className="flex gap-3"><span className="grid size-8 shrink-0 place-items-center rounded-full bg-primary font-bold text-white">{index + 1}</span><span className="pt-1 text-base leading-7">{step}</span></li>)}</ol></section>
  </div>;
}

function InsideGuide({ guide }: { guide: AppointmentTravelGuide }) {
  const facility = guide.facility;
  return <div className="space-y-4 px-5 py-5">
    <section className="overflow-hidden rounded-3xl border bg-white shadow-sm"><div className="bg-[#f1e3d1] p-5"><p className="text-sm font-bold text-primary">院内简图</p><div className="mt-4 flex items-center justify-between gap-1 text-center text-xs font-bold text-[#68432e]"><IndoorStop icon={<DoorOpen />} label={facility.entrance} /><IndoorArrow /><IndoorStop icon={<Check />} label="一层报到" /><IndoorArrow /><IndoorStop icon={<ArrowUp />} label={facility.floor} /><IndoorArrow /><IndoorStop icon={<Hospital />} label={facility.room} /></div></div><div className="grid grid-cols-2 gap-px bg-border"><LocationValue label="楼栋" value={facility.building} /><LocationValue label="诊室" value={`${facility.floor} ${facility.room}`} /></div></section>
    <section className="rounded-3xl border bg-white p-5 shadow-sm"><div className="flex items-center gap-2"><Signpost className="size-6 text-primary" /><h3 className="text-xl font-bold">到院后这样走</h3></div><ol className="mt-4 space-y-4"><GuideStep index={1} text={`从${facility.entrance}进入${facility.building}`} /><GuideStep index={2} text={`先到${facility.checkInPoint}报到`} /><GuideStep index={3} text={facility.accessibleRouteHint} /><GuideStep index={4} text={`到达${facility.floor}${facility.room}后等待叫号`} /></ol>{facility.landmark && <p className="mt-4 rounded-2xl bg-[#fff7ec] p-3 text-sm leading-6"><strong>附近标志：</strong>{facility.landmark}</p>}</section>
    <section className="rounded-3xl border-2 border-[#dfb98f] bg-[#fff7ec] p-5"><div className="flex items-start gap-3"><Headphones className="mt-1 size-7 shrink-0 text-primary" /><div><h3 className="text-lg font-bold">找不到时可以问工作人员</h3><p className="mt-1 text-base leading-7">请前往{facility.helpDesk ?? '门诊服务台'}，出示预约事项卡，请工作人员协助指路。</p></div></div></section>
  </div>;
}

function RouteMap({ points, origin, destination }: { points: GeoPoint[]; origin: GeoPoint; destination: GeoPoint }) {
  const key = process.env.NEXT_PUBLIC_AMAP_JS_KEY ?? '';
  return <section className="overflow-hidden rounded-3xl border bg-white shadow-sm"><div className="relative h-64">{key ? <AmapCanvas points={points} origin={origin} destination={destination} apiKey={key} /> : <SimulatedRouteMap points={points} />}<span className="absolute left-3 top-3 rounded-full bg-white/95 px-3 py-1 text-xs font-bold text-primary shadow">{key ? '高德地图' : '比赛模拟地图'}</span></div></section>;
}

function AmapCanvas({ points, origin, destination, apiKey }: { points: GeoPoint[]; origin: GeoPoint; destination: GeoPoint; apiKey: string }) {
  const container = useRef<HTMLDivElement | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let map: AMapInstance | null = null;
    let cancelled = false;
    const initialize = () => {
      if (cancelled || !container.current || !window.AMap) return;
      const AMap = window.AMap;
      map = new AMap.Map(container.current, { zoom: 13, center: [destination.longitude, destination.latitude], viewMode: '2D' });
      const start = new AMap.Marker({ position: [origin.longitude, origin.latitude], title: '出发地', map });
      const end = new AMap.Marker({ position: [destination.longitude, destination.latitude], title: '医院', map });
      const line = new AMap.Polyline({ path: points.map(item => [item.longitude, item.latitude]), strokeColor: '#c86436', strokeWeight: 7, strokeOpacity: 0.9, map });
      map.setFitView([start, end, line], false, [48, 48, 48, 48]);
    };
    const existing = document.getElementById('amap-js-api') as HTMLScriptElement | null;
    if (window.AMap) initialize();
    else {
      const securityCode = process.env.NEXT_PUBLIC_AMAP_SECURITY_CODE ?? '';
      if (securityCode) window._AMapSecurityConfig = { securityJsCode: securityCode };
      const script = existing ?? document.createElement('script');
      if (!existing) {
        script.id = 'amap-js-api'; script.async = true;
        script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(apiKey)}`;
        document.head.appendChild(script);
      }
      script.addEventListener('load', initialize, { once: true });
      script.addEventListener('error', () => setFailed(true), { once: true });
    }
    return () => { cancelled = true; map?.destroy(); };
  }, [apiKey, destination.latitude, destination.longitude, origin.latitude, origin.longitude, points]);
  return failed ? <SimulatedRouteMap points={points} /> : <div ref={container} className="h-full w-full" />;
}

function SimulatedRouteMap({ points }: { points: GeoPoint[] }) {
  const source = points.length >= 2 ? points : [{ longitude: 0, latitude: 0 }, { longitude: 1, latitude: 1 }];
  const xs = source.map(item => item.longitude); const ys = source.map(item => item.latitude);
  const minX = Math.min(...xs); const maxX = Math.max(...xs); const minY = Math.min(...ys); const maxY = Math.max(...ys);
  const mapped = source.map(item => ({ x: 24 + ((item.longitude - minX) / (maxX - minX || 1)) * 352, y: 214 - ((item.latitude - minY) / (maxY - minY || 1)) * 170 }));
  const path = mapped.map((item, index) => `${index ? 'L' : 'M'} ${item.x} ${item.y}`).join(' ');
  const start = mapped[0]; const end = mapped[mapped.length - 1];
  return <svg viewBox="0 0 400 240" role="img" aria-label="从模拟住址到医院的路线图" className="h-full w-full bg-[#f3eadc]"><defs><pattern id="grid" width="42" height="42" patternUnits="userSpaceOnUse"><path d="M 42 0 L 0 0 0 42" fill="none" stroke="#decdb8" strokeWidth="2" /></pattern></defs><rect width="400" height="240" fill="url(#grid)" /><path d={path} fill="none" stroke="#c86436" strokeWidth="9" strokeLinecap="round" strokeLinejoin="round" /><circle cx={start.x} cy={start.y} r="12" fill="#4f8548" stroke="white" strokeWidth="4" /><circle cx={end.x} cy={end.y} r="12" fill="#b53b2f" stroke="white" strokeWidth="4" /><text x={Math.max(8, start.x - 12)} y={Math.min(235, start.y + 28)} fontSize="14" fontWeight="700" fill="#4d3526">出发地</text><text x={Math.max(8, end.x - 18)} y={Math.max(18, end.y - 20)} fontSize="14" fontWeight="700" fill="#4d3526">医院</text></svg>;
}

function Metric({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) { return <div className="rounded-2xl bg-[#f8f3ec] p-3"><span className="text-primary [&>svg]:size-5">{icon}</span><p className="mt-2 text-sm text-muted-foreground">{label}</p><strong className="text-lg">{value}</strong></div>; }
function IndoorStop({ icon, label }: { icon: React.ReactNode; label: string }) { return <div className="flex w-16 shrink-0 flex-col items-center gap-2"><span className="grid size-11 place-items-center rounded-2xl bg-white text-primary shadow-sm [&>svg]:size-6">{icon}</span><span>{label}</span></div>; }
function IndoorArrow() { return <span className="h-0.5 min-w-3 flex-1 bg-[#bd8a62]" />; }
function LocationValue({ label, value }: { label: string; value: string }) { return <div className="bg-white p-4"><p className="text-sm text-muted-foreground">{label}</p><strong className="mt-1 block text-lg">{value}</strong></div>; }
function GuideStep({ index, text }: { index: number; text: string }) { return <li className="flex gap-3"><span className="grid size-8 shrink-0 place-items-center rounded-full bg-[#fff0dc] font-bold text-primary">{index}</span><span className="pt-0.5 text-base leading-7">{text}</span></li>; }
function transportIcon(value: string) { if (value.includes('公交')) return <BusFront className="size-7" />; if (value.includes('步行')) return <Footprints className="size-7" />; return <CarFront className="size-7" />; }
function formatTime(value: string) { return value.includes('T') ? value.split('T')[1].slice(0, 5) : value.slice(11, 16); }
function formatDateTime(value: string) { const [date, time] = value.split('T'); const [, month, day] = date.split('-'); return `${Number(month)}月${Number(day)}日 ${time.slice(0, 5)}`; }
function formatDistance(value: number) { return value >= 1000 ? `${(value / 1000).toFixed(1)}公里` : `${value}米`; }

/** 只用工具返回的 route 与 facility 数据拼接口播文案，不经过大模型。 */
function travelNarration(guide: AppointmentTravelGuide) {
  return routeNarration(guide) + facilityNarration(guide);
}

/** 院外部分：预约时间、交通方式、用时、距离、建议出发时间。 */
function routeNarration(guide: AppointmentTravelGuide) {
  const route = guide.route;
  return [
    '路线已经打开。',
    `您${spokenDateTime(guide.appointmentAt)}在${guide.hospital}${guide.department}复诊。`,
    `${route.transport}预计${route.durationMinutes}分钟，大约${formatDistance(route.distanceMeters)}，建议${spokenClock(route.departureAt)}出发。`,
  ].join('');
}

/** 院内部分：入口、楼栋、报到点、楼层诊室、求助点。 */
function facilityNarration(guide: AppointmentTravelGuide) {
  const facility = guide.facility;
  return [
    `到院后请从${facility.entrance}进入${facility.building}，先到${facility.checkInPoint}报到，再前往${facility.floor}${facility.room}。`,
    facility.landmark ? `附近标志是${facility.landmark}。` : '',
    `找不到时，请到${facility.helpDesk ?? '门诊服务台'}询问工作人员。`,
  ].filter(Boolean).join('');
}

function spokenDateTime(value: string) {
  const [date] = value.split('T');
  const [, month, day] = date.split('-');
  return `${Number(month)}月${Number(day)}日${spokenClock(value)}`;
}

function spokenClock(value: string) {
  const [hourText, minuteText] = formatTime(value).split(':');
  const hour = Number(hourText);
  const minute = Number(minuteText);
  return `${hour < 12 ? '上午' : '下午'}${hour > 12 ? hour - 12 : hour}点${minute === 0 ? '' : `${minute}分`}`;
}
