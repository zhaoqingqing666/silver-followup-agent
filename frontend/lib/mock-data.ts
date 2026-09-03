import type { MaterialItem, PlanStep } from '@/types/domain';

export const mockMaterials: MaterialItem[] = [
  { id: 'identity-card', label: '身份证', prepared: true, required: true },
  { id: 'medical-card', label: '医保卡', prepared: true, required: true },
  { id: 'reports', label: '既往检查报告', prepared: false, required: true },
  { id: 'medicine-list', label: '正在使用的药物清单', prepared: false, required: false },
];

export const mockPlan: PlanStep[] = [
  { id: 'collect', title: '确认复诊需求', detail: '医院、科室和日期已补充', status: 'done' },
  { id: 'slots', title: '查询预约时间', detail: '找到 2 个可选时段', status: 'done' },
  { id: 'schedule', title: '检查日程冲突', detail: '当前时间没有冲突', status: 'done' },
  { id: 'confirm', title: '等待您的确认', detail: '确认后才会提交预约', status: 'active' },
  { id: 'remind', title: '创建提醒并通知家属', detail: '将在预约成功后执行', status: 'pending' },
];

