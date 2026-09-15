import type { AppointmentSummary } from '@/types/domain';

/**
 * 预约卡片上「医生 · 职称 · 号别 · 挂号费」的显示口径。
 *
 * 事项页和首页读的是同一份数据，各写一遍拼接逻辑的话，同一张预约会两边各说各话
 * （一边「专家号」一边「EXPERT」，或者一边带挂号费一边不带）。所以拼法收在这里一处。
 */

/**
 * 号别的中文说法。
 *
 * 后端是封闭枚举，这里**必须给未知值兜底**：后端以后加新号别时，前端要原样显示那个值，
 * 而不是显示成空白。显示得不好看，也好过把一条已有的信息整块抹掉。
 */
export function slotTypeLabel(slotType: string | null | undefined): string | null {
  if (!slotType) return null;
  if (slotType === 'NORMAL') return '普通号';
  if (slotType === 'EXPERT') return '专家号';
  return slotType;
}

/** 挂号费：后端存的是**分**，这里转成「¥40」。没有费用时返回 null——不显示「¥0」。 */
export function feeLabel(feeCents: number | null | undefined): string | null {
  if (feeCents == null) return null;
  const yuan = feeCents / 100;
  return `¥${Number.isInteger(yuan) ? yuan : yuan.toFixed(2)}`;
}

/** 医生姓名与职称，例如「张建国 主任医师」；两者都没有时返回 null。 */
export function doctorLabel(doctorName: string | null | undefined,
                            doctorTitle: string | null | undefined): string | null {
  const parts = [doctorName, doctorTitle].filter((part): part is string => Boolean(part));
  return parts.length > 0 ? parts.join(' ') : null;
}

type DoctorFields = Pick<AppointmentSummary, 'doctorName' | 'doctorTitle' | 'slotType' | 'feeCents'>;

/**
 * 拼成一行：`张建国 主任医师 · 专家号 · 挂号费 ¥40`。
 *
 * 没有任何医生信息时返回「医生信息未记录」，**不返回空串、也不编一位医生**：
 * 加医生维度之前建的预约本来就没有这个事实，而一个编出来的医生名字会让老人
 * 拿着它去医院找人。号别与挂号费单独存在（没有名字）时也一样照实显示，不整行丢掉。
 */
export function doctorLine(row: DoctorFields): string {
  const fee = feeLabel(row.feeCents);
  const parts = [
    doctorLabel(row.doctorName, row.doctorTitle),
    slotTypeLabel(row.slotType),
    fee ? `挂号费 ${fee}` : null,
  ].filter((part): part is string => Boolean(part));
  return parts.length > 0 ? parts.join(' · ') : '医生信息未记录';
}
