'use client';

import { useCallback, useEffect, useState } from 'react';
import { getAppointmentMaterials, updateAppointmentMaterial } from '@/lib/material-api';
import type { MaterialItem } from '@/types/domain';

/**
 * 材料状态变化事件。
 * 助手页常驻不卸载，事项页每次切换标签都会重新挂载，两边会同时存在同一个
 * appointmentId 的清单实例；靠这个事件让其余实例重新拉取，避免相互看到陈旧状态。
 */
export const MATERIALS_UPDATED_EVENT = 'silver-agent-materials-updated';

interface MaterialsUpdatedDetail {
  appointmentId?: string;
  /** 派发方的实例标识；用来跳过自己发出的事件，避免多余的一次重载闪烁。 */
  source?: string;
}

let instanceSequence = 0;

export function useAppointmentMaterials(appointmentId: string) {
  const [materials, setMaterials] = useState<MaterialItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [updatingId, setUpdatingId] = useState('');
  const [error, setError] = useState('');
  const [instanceId] = useState(() => `materials-${++instanceSequence}`);

  const reload = useCallback(async () => {
    if (!appointmentId) {
      setMaterials([]);
      return;
    }
    setLoading(true);
    setError('');
    try {
      setMaterials(await getAppointmentMaterials(appointmentId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法读取材料状态');
    } finally {
      setLoading(false);
    }
  }, [appointmentId]);

  useEffect(() => { void reload(); }, [reload]);

  useEffect(() => {
    const onMaterialsUpdated = (event: Event) => {
      const detail = (event as CustomEvent<MaterialsUpdatedDetail>).detail;
      if (detail?.appointmentId !== appointmentId || !appointmentId) return;
      if (detail?.source === instanceId) return;
      void reload();
    };
    window.addEventListener(MATERIALS_UPDATED_EVENT, onMaterialsUpdated);
    return () => window.removeEventListener(MATERIALS_UPDATED_EVENT, onMaterialsUpdated);
  }, [appointmentId, instanceId, reload]);

  const toggle = async (item: MaterialItem) => {
    const status = item.status === 'NOT_PREPARED' ? 'PREPARED' : 'NOT_PREPARED';
    setUpdatingId(item.id);
    setError('');
    try {
      const updated = await updateAppointmentMaterial(appointmentId, item.id, status);
      setMaterials(rows => rows.map(row => row.id === updated.id ? updated : row));
      window.dispatchEvent(new CustomEvent<MaterialsUpdatedDetail>(MATERIALS_UPDATED_EVENT, {
        detail: { appointmentId, source: instanceId },
      }));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '材料状态没有保存成功');
    } finally {
      setUpdatingId('');
    }
  };

  return { materials, loading, updatingId, error, reload, toggle };
}
