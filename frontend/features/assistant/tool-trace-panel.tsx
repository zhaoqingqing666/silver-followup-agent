import { ChevronDown, Wrench } from 'lucide-react';
import type { ToolTrace } from '@/types/domain';

type Rec = Record<string, unknown>;

function parse(raw: string): unknown {
  try { return JSON.parse(raw); } catch { return raw; }
}

function asRec(value: unknown): Rec {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Rec : {};
}

function asList(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function get(record: Rec, key: string): unknown {
  return record[key];
}

/** “2026-09-10” → “9月10日”。 */
function fmtDate(value: unknown): string {
  const raw = String(value ?? '');
  const [date] = raw.split('T');
  if (!date) return raw;
  const parts = date.split('-');
  if (parts.length < 3) return raw;
  return `${Number(parts[1])}月${Number(parts[2])}日`;
}

/** “2026-09-10T08:00:00” → “9月10日 08:00”。 */
function fmtDateTime(value: unknown): string {
  const raw = String(value ?? '');
  const [date, time] = raw.split('T');
  if (!time) return fmtDate(value);
  return `${fmtDate(date)} ${time.slice(0, 5)}`;
}

const pad2 = (n: number) => String(n).padStart(2, '0');
const WEEKDAY_TEXT = '日一二三四五六';

/** 重复备忘的显示口径与首页列表完全一致：“每天 08:00 / 每周三 15:00 / 每月15号 09:00”。 */
function fmtRepeat(iso: string, repeat: string): string {
  const at = new Date(iso);
  const time = `${pad2(at.getHours())}:${pad2(at.getMinutes())}`;
  if (repeat === 'DAILY') return `每天 ${time}`;
  if (repeat === 'WEEKLY') return `每周${WEEKDAY_TEXT[at.getDay()]} ${time}`;
  return `每月${at.getDate()}号 ${time}`;
}

/** 把内部工具名+参数转成老人看得懂的中文说明。 */
function describeTrace(trace: ToolTrace): { label: string; note: string } {
  const req = asRec(parse(trace.parameters));
  const res = parse(trace.result);
  const resRec = asRec(res);
  const list = asList(res);

  switch (trace.toolName) {
    case 'memo.create': {
      const text = String(get(req, 'text') ?? '');
      const remindAt = get(req, 'remindAt');
      const repeat = String(get(req, 'repeat') ?? '');
      // 重复提醒存的是“下一次到点”这个具体日期（如 2026-09-11T08:00:00），
      // 直接显示日期会和首页列表的“每天 08:00”对不上；所以这里按 repeat 规则显示，
      // 口径与首页列表、助手回读保持一致。不要改回直接显示 anchor 日期。
      let when: string;
      if (repeat === 'DAILY' || repeat === 'WEEKLY' || repeat === 'MONTHLY') {
        when = `以后${fmtRepeat(String(remindAt ?? ''), repeat)}到点打开应用会提醒您`;
      } else if (String(remindAt ?? '') === '长期') {
        when = '长期备忘，首页常驻';
      } else {
        when = `${fmtDateTime(remindAt)} 打开应用时提醒`;
      }
      return { label: '健康备忘 · 记录事项', note: `记下：${text}（${when}）` };
    }
    case 'appointment.querySlots':
      return { label: '复诊号源 · 查询当日时段', note: `${fmtDate(get(req, 'date'))} 共查到 ${list.length} 个可约时段` };
    case 'appointment.queryAlternatives':
      return { label: '复诊号源 · 查询邻近日期', note: `当日已满，检索到邻近日期 ${list.length} 个号源` };
    case 'appointment.submit':
      return { label: '复诊预约 · 提交号源', note: resRec.status ? `预约成功，状态 ${resRec.status}` : '预约已提交，号源已占用' };
    case 'appointment.cancel':
      return { label: '复诊预约 · 取消预约', note: '已取消并释放号源，关联提醒停用' };
    case 'appointment.reschedule':
      return { label: '复诊预约 · 改期换号源', note: resRec.status ? `已按新号源更新，状态 ${resRec.status}` : '已将原预约改到新的号源' };
    case 'appointment.queryUpcomingSlots':
      return { label: '复诊号源 · 查询邻近日期', note: `${fmtDate(get(req, 'from'))}到${fmtDate(get(req, 'to'))}之间查到 ${list.length} 个可约时段` };
    case 'appointment.queryMine':
      return { label: '我的预约 · 查询已确认预约', note: `查到 ${get(resRec, 'count') ?? list.length} 条已确认预约` };
    case 'catalog.queryHospitals':
      return { label: '医院目录 · 查询可办理医院', note: `共 ${list.length} 家医院` };
    case 'catalog.recommendHospitals':
      return { label: '医院目录 · 按科室推荐医院', note: `为「${get(req, 'department')}」匹配 ${list.length} 家医院` };
    case 'catalog.queryDepartments':
      return { label: '医院目录 · 查询可预约科室', note: `共 ${list.length} 个科室` };
    case 'catalog.searchDepartments':
      return { label: '医院目录 · 搜索科室', note: `按「${get(req, 'keyword')}」检索到 ${list.length} 个结果` };
    case 'careGuide.search':
      return { label: '复诊指引 · 查办理说明', note: `按「${get(req, 'query')}」找到 ${list.length} 条说明` };
    case 'hospital.locationGuide':
      return { label: '院内指引 · 查诊室怎么走', note: resRec.roomName ? `${get(resRec, 'floorName') ?? ''}${resRec.roomName}，${get(resRec, 'checkInPoint') ?? '按现场指引报到'}` : '查询院内位置指引' };
    case 'healthRecord.create':
      return { label: '健康记录 · 记下数值', note: `记下${get(req, 'item')} ${get(req, 'value')}${get(req, 'unit') ?? ''}` };
    case 'healthRecord.query':
      return { label: '健康记录 · 查询历史数值', note: `「${get(req, 'item')}」共 ${get(resRec, 'count') ?? list.length} 条记录` };
    case 'memo.list':
      return { label: '健康备忘 · 查看全部', note: `共 ${get(resRec, 'count') ?? list.length} 条备忘` };
    case 'memo.update':
      return { label: '健康备忘 · 修改备忘', note: `改为：${String(get(req, 'text') ?? '')}` };
    case 'memo.delete':
      return { label: '健康备忘 · 删除备忘', note: resRec.deleted ? '已删除这条备忘' : '没有找到要删除的备忘' };
    case 'family.queryContact':
      return { label: '家属通讯录 · 查询默认联系人', note: resRec.name ? `默认联系人为${resRec.relationship ?? ''}${resRec.name}` : '查询默认联系人' };
    case 'family.notify':
      return { label: '家属通知 · 发送消息', note: String(get(req, 'message') ?? '') };
    case 'material.generateChecklist':
      return { label: '就诊材料 · 生成清单', note: `共 ${list.length} 项${list.length ? `，如 ${list.slice(0, 3).join('、')}` : ''}` };
    case 'material.initializePreparation':
      return { label: '就诊材料 · 生成准备清单', note: `共 ${list.length} 项${get(req, 'department') ? `（${get(req, 'department')}）` : ''}` };
    case 'schedule.checkConflict': {
      const conflictNames = list.map(item => String(asRec(item).title ?? '')).filter(Boolean);
      return { label: '日程安排 · 检查时间冲突', note: conflictNames.length ? `与「${conflictNames.join('」「')}」冲突` : '与您其他日程不冲突' };
    }
    case 'schedule.createReminder':
      return { label: '到点提醒 · 创建提醒', note: `「${get(req, 'title')}」将于 ${fmtDateTime(get(req, 'remindAt'))} 提醒` };
    case 'travel.plan': {
      const departure = resRec.departureAt;
      const transport = get(resRec, 'transport') ?? get(req, 'transport');
      return { label: '出行 · 计算出发时间', note: departure ? `建议 ${fmtDateTime(departure)} 出发（${transport ?? ''}）` : '生成出发建议' };
    }
    case 'travel.routePlan': {
      const duration = get(resRec, 'durationMinutes');
      const transport = get(resRec, 'transport') ?? get(req, 'transport');
      return { label: '出行 · 规划路线', note: duration ? `乘${transport ?? ''}约 ${duration} 分钟，建议 ${fmtDateTime(resRec.departureAt)} 出发` : '生成路线指引' };
    }
    case 'workflow.error':
      return { label: '办理中断', note: `需要修改或重试：${String(resRec.error ?? get(req, 'error') ?? '')}` };
    default:
      return { label: trace.toolName, note: '' };
  }
}

export function ToolTracePanel({ traces }: { traces: ToolTrace[] }) {
  if (!traces.length) return null;
  return <details className="rounded-2xl border bg-white/80 px-4 py-3">
    <summary className="flex cursor-pointer list-none items-center gap-2 font-semibold text-[#6d432c]">
      <Wrench className="size-5" /> 查看办理过程
      <span className="ml-auto rounded-full bg-[#f4dfc8] px-2 py-0.5 text-sm">{traces.length} 步</span>
      <ChevronDown className="size-4" />
    </summary>
    <div className="mt-1">
      <p className="text-sm text-muted-foreground">刚才我为您依次做了这几步：</p>
      <ol className="mt-2 space-y-3">
        {traces.map((trace, index) => {
          const { label, note } = describeTrace(trace);
          return <li key={index} className="rounded-xl bg-[#fff8ef] p-3 text-sm leading-6">
            <div className="flex items-center justify-between">
              <strong>{index + 1}. {label}</strong>
              <span className={trace.success ? 'text-green-700' : 'text-red-700'}>{trace.success ? '成功' : '未完成'}</span>
            </div>
            {note && <p className="mt-1 break-words text-muted-foreground">{note}</p>}
          </li>;
        })}
      </ol>
    </div>
  </details>;
}
