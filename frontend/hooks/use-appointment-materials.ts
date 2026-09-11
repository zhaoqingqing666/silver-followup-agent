'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  confirmMaterialWithPhoto,
  getAppointmentMaterials,
  getMaterialPhoto,
  updateAppointmentMaterial,
} from '@/lib/material-api';
import type { MaterialItem } from '@/types/domain';

export function useAppointmentMaterials(appointmentId: string) {
  const [materials, setMaterials] = useState<MaterialItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [updatingId, setUpdatingId] = useState('');
  const [error, setError] = useState('');

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

  const toggle = async (item: MaterialItem) => {
    const status = item.status === 'NOT_PREPARED' ? 'PREPARED' : 'NOT_PREPARED';
    setUpdatingId(item.id);
    setError('');
    try {
      const updated = await updateAppointmentMaterial(appointmentId, item.id, status);
      setMaterials(rows => rows.map(row => row.id === updated.id ? updated : row));
      window.dispatchEvent(new CustomEvent('silver-agent-materials-updated', {
        detail: { appointmentId },
      }));
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
      window.dispatchEvent(new CustomEvent('silver-agent-materials-updated', {
        detail: { appointmentId },
      }));
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
