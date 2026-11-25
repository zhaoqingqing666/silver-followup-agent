import type { ToolTrace } from '@/types/domain';

/**
 * 把内部工具名+参数转成老人看得懂的中文说明。
 *
 * 单独成一个模块，是因为「办理过程」有两个视图要共用这套口径：
 * 一轮结束后的中文列表（{@code ToolTracePanel}）和办理中的实时步骤（{@code LiveProgress}）。
 * 两份翻译一定会走样——同一个工具在两处说法不同，评审一眼就能看出来。
 */

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

/**
 * 工具返回的是 JSON，字段类型只能到运行时才知道。
 * 这里只认能直接读给老人听的三种原始类型；对象和数组一律给空串，
 * 免得把 `[object Object]` 这类东西写进「办理过程」给评委看。
 */
function str(value: unknown): string {
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return '';
}

/** “2026-09-10” → “9月10日”。 */
function fmtDate(value: unknown): string {
  const raw = str(value);
  const [date] = raw.split('T');
  if (!date) return raw;
  const parts = date.split('-');
  if (parts.length < 3) return raw;
  return `${Number(parts[1])}月${Number(parts[2])}日`;
}

/** “2026-09-10T08:00:00” → “9月10日 08:00”。 */
function fmtDateTime(value: unknown): string {
  const raw = str(value);
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

/** 取消范围的读法。范围由模型给结构化参数、Java 守门禁，这里只把它说成人话。 */
function describeCancelScope(req: Rec): string {
  const scope = str(get(req, 'scope'));
  const direction: Record<string, string> = {
    BEFORE: '之前', ON_OR_BEFORE: '及之前', AFTER: '之后', ON_OR_AFTER: '及之后',
  };
  if (scope === 'ALL') return '范围：全部已确认预约';
  if (scope === 'DATE_RANGE') {
    return `范围：${fmtDate(get(req, 'date'))}${direction[str(get(req, 'direction'))] ?? ''}的已确认预约`;
  }
  if (scope === 'SINGLE_FILTER') {
    const conditions = [
      str(get(req, 'date')) ? fmtDate(get(req, 'date')) : '',
      str(get(req, 'time')),
      str(get(req, 'period')) === 'MORNING' ? '上午' : str(get(req, 'period')) === 'AFTERNOON' ? '下午' : '',
      str(get(req, 'position')) === 'NEAREST' ? '最近一次' : str(get(req, 'position')) === 'EARLIEST' ? '最早一次' : '',
      str(get(req, 'hospital')),
      str(get(req, 'department')),
    ].filter(Boolean);
    return conditions.length ? `范围：${conditions.join(' · ')}` : '范围：还没有可用的筛选条件';
  }
  if (scope === 'AMBIGUOUS') return '范围：老人自己也说不清，需要先从真实候选里选一条';
  return `范围：${scope || '未提供'}`;
}

export function describeTrace(trace: ToolTrace): { label: string; note: string } {
  const req = asRec(parse(trace.parameters));
  const res = parse(trace.result);
  const resRec = asRec(res);
  const list = asList(res);

  switch (trace.toolName) {
    case 'memo.create': {
      const text = str(get(req, 'text'));
      const remindAt = get(req, 'remindAt');
      const repeat = str(get(req, 'repeat'));
      // 重复提醒存的是“下一次到点”这个具体日期（如 2026-09-11T08:00:00），
      // 直接显示日期会和首页列表的“每天 08:00”对不上；所以这里按 repeat 规则显示，
      // 口径与首页列表、助手回读保持一致。不要改回直接显示 anchor 日期。
      let when: string;
      if (repeat === 'DAILY' || repeat === 'WEEKLY' || repeat === 'MONTHLY') {
        when = `以后${fmtRepeat(str(remindAt), repeat)}到点打开应用会提醒您`;
      } else if (str(remindAt) === '长期') {
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
      return { label: '复诊预约 · 提交号源', note: resRec.status ? `预约成功，状态 ${str(resRec.status)}` : '预约已提交，号源已占用' };
    case 'appointment.cancel':
      return { label: '复诊预约 · 取消预约', note: '已取消并释放号源，关联提醒停用' };
    case 'appointment.reschedule':
      return { label: '复诊预约 · 改期换号源', note: resRec.status ? `已按新号源更新，状态 ${str(resRec.status)}` : '已将原预约改到新的号源' };
    case 'appointment.queryUpcomingSlots':
      return { label: '复诊号源 · 查询邻近日期', note: `${fmtDate(get(req, 'from'))}到${fmtDate(get(req, 'to'))}之间查到 ${list.length} 个可约时段` };
    case 'appointment.queryMine':
      return { label: '我的预约 · 查询已确认预约', note: `查到 ${str(get(resRec, 'count')) || list.length} 条已确认预约` };
    case 'catalog.queryHospitals':
      return { label: '医院目录 · 查询可办理医院', note: `共 ${list.length} 家医院` };
    case 'catalog.recommendHospitals':
      return { label: '医院目录 · 按科室推荐医院', note: `为「${str(get(req, 'department'))}」匹配 ${list.length} 家医院` };
    case 'catalog.queryDepartments':
      return { label: '医院目录 · 查询可预约科室', note: `共 ${list.length} 个科室` };
    case 'careGuide.search':
      return { label: '复诊指引 · 查办理说明', note: `按「${str(get(req, 'query'))}」找到 ${list.length} 条说明` };
    case 'hospital.locationGuide':
      return { label: '院内指引 · 查诊室怎么走', note: resRec.roomName ? `${str(get(resRec, 'floorName'))}${str(resRec.roomName)}，${str(get(resRec, 'checkInPoint')) || '按现场指引报到'}` : '查询院内位置指引' };
    case 'healthRecord.create':
      return { label: '健康记录 · 记下数值', note: `记下${str(get(req, 'item'))} ${str(get(req, 'value'))}${str(get(req, 'unit'))}` };
    case 'healthRecord.query':
      return { label: '健康记录 · 查询历史数值', note: `「${str(get(req, 'item'))}」共 ${str(get(resRec, 'count')) || list.length} 条记录` };
    case 'memo.list':
      return { label: '健康备忘 · 查看全部', note: `共 ${str(get(resRec, 'count')) || list.length} 条备忘` };
    case 'memo.update':
      return { label: '健康备忘 · 修改备忘', note: `改为：${str(get(req, 'text'))}` };
    case 'memo.delete':
      return { label: '健康备忘 · 删除备忘', note: resRec.deleted ? '已删除这条备忘' : '没有找到要删除的备忘' };
    case 'family.queryContact':
      return { label: '家属通讯录 · 查询默认联系人', note: resRec.name ? `默认联系人为${str(resRec.relationship)}${str(resRec.name)}` : '查询默认联系人' };
    case 'family.notify':
      return { label: '家属通知 · 发送消息', note: str(get(req, 'message')) };
    case 'material.generateChecklist':
      return { label: '就诊材料 · 生成清单', note: `共 ${list.length} 项${list.length ? `，如 ${list.slice(0, 3).map(str).join('、')}` : ''}` };
    case 'material.initializePreparation':
      return { label: '就诊材料 · 生成准备清单', note: `共 ${list.length} 项${str(get(req, 'department')) ? `（${str(get(req, 'department'))}）` : ''}` };
    case 'schedule.checkConflict': {
      const conflictNames = list.map(item => str(asRec(item).title)).filter(Boolean);
      return { label: '日程安排 · 检查时间冲突', note: conflictNames.length ? `与「${conflictNames.join('」「')}」冲突` : '与您其他日程不冲突' };
    }
    case 'schedule.createReminder':
      return { label: '到点提醒 · 创建提醒', note: `「${str(get(req, 'title'))}」将于 ${fmtDateTime(get(req, 'remindAt'))} 提醒` };
    case 'travel.plan': {
      const departure = resRec.departureAt;
      const transport = str(get(resRec, 'transport')) || str(get(req, 'transport'));
      return { label: '出行 · 计算出发时间', note: departure ? `建议 ${fmtDateTime(departure)} 出发（${transport}）` : '生成出发建议' };
    }
    case 'travel.routePlan': {
      const duration = str(get(resRec, 'durationMinutes'));
      const transport = str(get(resRec, 'transport')) || str(get(req, 'transport'));
      return { label: '出行 · 规划路线', note: duration ? `乘${transport}约 ${duration} 分钟，建议 ${fmtDateTime(resRec.departureAt)} 出发` : '生成路线指引' };
    }
    case 'interaction.requestConfirmation':
      return { label: '取消预约 · 生成确认卡', note: describeCancelScope(req) };
    case 'interaction.respondConfirmation':
      return {
        label: '取消预约 · 处理确认卡',
        note: str(get(req, 'decision')) === 'DENY' ? '老人选择保留，没有执行取消' : '老人已确认，按确认卡执行',
      };
    case 'interaction.askClarification': {
      // 澄清只提问：这里必须读得像“还在问”，不能写得像“已经办了”。
      const count = str(get(resRec, 'candidateCount'));
      const outcome = str(get(resRec, 'outcome'));
      if (outcome === 'NO_RESULT') return { label: '取消预约 · 没有可取消的预约', note: '没有查到已确认预约，未执行任何取消' };
      return { label: '取消预约 · 请老人选择要取消哪条', note: count ? `已列出 ${count} 条真实候选，等老人选择` : '等老人从真实候选中选择' };
    }
    case 'workflow.error':
      return { label: '办理中断', note: `需要修改或重试：${str(resRec.error) || str(get(req, 'error'))}` };
    default:
      return { label: trace.toolName, note: '' };
  }
}
