'use client';

import { useCallback, useEffect, useState } from 'react';
import { getAppointmentMaterials, updateAppointmentMaterial } from '@/lib/material-api';
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

  return { materials, loading, updatingId, error, reload, toggle };
}
