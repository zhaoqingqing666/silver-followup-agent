'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  confirmMaterialWithPhoto,
  getAppointmentMaterials,
  getMaterialPhoto,
  updateAppointmentMaterial,
} from '@/lib/material-api';
import type { MaterialItem } from '@/types/domain';

/**
 * 材料状态变化事件。
 *
 * 同一份预约的材料清单会同时挂在两处：助手页的确认卡里（常驻，切标签不卸载）和事项页里
 * （每次切回来都重新挂载）。一边勾了、另一边还显示「未准备」，评审看到的就是自相矛盾的两屏。
 * 所以改动的一方广播一次，其余实例重新拉取。
 */
export const MATERIALS_UPDATED_EVENT = 'silver-agent-materials-updated';

interface MaterialsUpdatedDetail {
  appointmentId?: string;
  /** 派发方自己的实例编号：跳过自己发出的那条，省掉一次多余的重载。 */
  source?: string;
}

let instanceSequence = 0;

export function useAppointmentMaterials(appointmentId: string) {
  const [materials, setMaterials] = useState<MaterialItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [updatingId, setUpdatingId] = useState('');
  const [error, setError] = useState('');
  const [instanceId] = useState(() => `materials-${++instanceSequence}`);

  /** silent：这是别处改动后的一次后台同步，不要把列表换成「正在读取」，那会闪一下。 */
  const reload = useCallback(async (options?: { silent?: boolean }) => {
    if (!appointmentId) {
      setMaterials([]);
      return;
    }
    if (!options?.silent) setLoading(true);
    setError('');
    try {
      setMaterials(await getAppointmentMaterials(appointmentId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法读取材料状态');
    } finally {
      if (!options?.silent) setLoading(false);
    }
  }, [appointmentId]);

  useEffect(() => { void reload(); }, [reload]);

  useEffect(() => {
    const onMaterialsUpdated = (event: Event) => {
      const detail = (event as CustomEvent<MaterialsUpdatedDetail>).detail;
      if (!appointmentId || detail?.appointmentId !== appointmentId) return;
      if (detail?.source === instanceId) return;
      void reload({ silent: true });
    };
    window.addEventListener(MATERIALS_UPDATED_EVENT, onMaterialsUpdated);
    return () => window.removeEventListener(MATERIALS_UPDATED_EVENT, onMaterialsUpdated);
  }, [appointmentId, instanceId, reload]);

  /** 本次改动已经写进库，通知别处的同一份清单重新拉取。 */
  const broadcast = () => window.dispatchEvent(new CustomEvent<MaterialsUpdatedDetail>(
    MATERIALS_UPDATED_EVENT, { detail: { appointmentId, source: instanceId } }));

  const toggle = async (item: MaterialItem) => {
    const status = item.status === 'NOT_PREPARED' ? 'PREPARED' : 'NOT_PREPARED';
    setUpdatingId(item.id);
    setError('');
    try {
      const updated = await updateAppointmentMaterial(appointmentId, item.id, status);
      setMaterials(rows => rows.map(row => row.id === updated.id ? updated : row));
      broadcast();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '材料状态没有保存成功');
    } finally {
      setUpdatingId('');
    }
  };

  /** 拍照确认：把压缩后的照片交给后端，成功后就地替换这一行。 */
  const confirmWithPhoto = async (item: MaterialItem, dataUrl: string) => {
    setUpdatingId(item.id);
    setError('');
    try {
      const updated = await confirmMaterialWithPhoto(appointmentId, item.id, dataUrl);
      setMaterials(rows => rows.map(row => row.id === updated.id ? updated : row));
      broadcast();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '照片确认没有保存成功');
    } finally {
      setUpdatingId('');
    }
  };

  /** 取回这一行拍过的照片；没拍过返回 null（不算错误，页面上是「还没有照片」）。 */
  const loadPhoto = async (item: MaterialItem): Promise<string | null> => {
    setError('');
    try {
      return await getMaterialPhoto(appointmentId, item.id);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '照片没有读到');
      return null;
    }
  };

  return { materials, loading, updatingId, error, reload, toggle, confirmWithPhoto, loadPhoto };
}
