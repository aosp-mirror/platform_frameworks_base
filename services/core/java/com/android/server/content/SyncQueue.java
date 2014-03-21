/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.content;

import android.content.pm.PackageManager;
import android.content.SyncAdapterType;
import android.content.SyncAdaptersCache;
import android.content.pm.RegisteredServicesCache.ServiceInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Pair;

import com.google.android.collect.Maps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Queue of pending sync operations. Not inherently thread safe, external
 * callers are responsible for locking.
 *
 * @hide
 */
public class SyncQueue {
    private static final String TAG = "SyncManager";
    private final SyncStorageEngine mSyncStorageEngine;
    private final SyncAdaptersCache mSyncAdapters;
    private final PackageManager mPackageManager;

    // A Map of SyncOperations operationKey -> SyncOperation that is designed for
    // quick lookup of an enqueued SyncOperation.
    private final HashMap<String, SyncOperation> mOperationsMap = Maps.newHashMap();

    public SyncQueue(PackageManager packageManager, SyncStorageEngine syncStorageEngine,
            final SyncAdaptersCache syncAdapters) {
        mPackageManager = packageManager;
        mSyncStorageEngine = syncStorageEngine;
        mSyncAdapters = syncAdapters;
    }

    public void addPendingOperations(int userId) {
        for (SyncStorageEngine.PendingOperation op : mSyncStorageEngine.getPendingOperations()) {
            final SyncStorageEngine.EndPoint info = op.target;
            if (info.userId != userId) continue;

            final Pair<Long, Long> backoff = mSyncStorageEngine.getBackoff(info);
            SyncOperation operationToAdd;
            if (info.target_provider) {
                final ServiceInfo<SyncAdapterType> syncAdapterInfo = mSyncAdapters.getServiceInfo(
                        SyncAdapterType.newKey(info.provider, info.account.type), info.userId);
                if (syncAdapterInfo == null) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Missing sync adapter info for authority " + op.target);
                    }
                    continue;
                }
                operationToAdd = new SyncOperation(
                        info.account, info.userId, op.reason, op.syncSource, info.provider,
                        op.extras,
                        op.expedited ? -1 : 0 /* delay */,
                        0 /* flex */,
                        backoff != null ? backoff.first : 0L,
                        mSyncStorageEngine.getDelayUntilTime(info),
                        syncAdapterInfo.type.allowParallelSyncs());
                operationToAdd.pendingOperation = op;
                add(operationToAdd, op);
            } else if (info.target_service) {
                try {
                    mPackageManager.getServiceInfo(info.service, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.w(TAG, "Missing sync service for authority " + op.target);
                    }
                    continue;
                }
                operationToAdd = new SyncOperation(
                        info.service, info.userId, op.reason, op.syncSource,
                        op.extras,
                        op.expedited ? -1 : 0 /* delay */,
                        0 /* flex */,
                        backoff != null ? backoff.first : 0,
                        mSyncStorageEngine.getDelayUntilTime(info));
                operationToAdd.pendingOperation = op;
                add(operationToAdd, op);
            }
        }
    }

    public boolean add(SyncOperation operation) {
        return add(operation, null /* this is not coming from the database */);
    }

    /**
     * Adds a SyncOperation to the queue and creates a PendingOperation object to track that sync.
     * If an operation is added that already exists, the existing operation is updated if the newly
     * added operation occurs before (or the interval overlaps).
     */
    private boolean add(SyncOperation operation,
            SyncStorageEngine.PendingOperation pop) {
        // If an operation with the same key exists and this one should run sooner/overlaps,
        // replace the run interval of the existing operation with this new one.
        // Complications: what if the existing operation is expedited but the new operation has an
        // earlier run time? Will not be a problem for periodic syncs (no expedited flag), and for
        // one-off syncs we only change it if the new sync is sooner.
        final String operationKey = operation.key;
        final SyncOperation existingOperation = mOperationsMap.get(operationKey);

        if (existingOperation != null) {
            boolean changed = false;
            if (operation.compareTo(existingOperation) <= 0 ) {
                long newRunTime =
                        Math.min(existingOperation.latestRunTime, operation.latestRunTime);
                // Take smaller runtime.
                existingOperation.latestRunTime = newRunTime;
                // Take newer flextime.
                existingOperation.flexTime = operation.flexTime;
                changed = true;
            }
            return changed;
        }

        operation.pendingOperation = pop;
        // Don't update the PendingOp if one already exists. This really is just a placeholder,
        // no actual scheduling info is placed here.
        if (operation.pendingOperation == null) {
            pop = mSyncStorageEngine.insertIntoPending(operation);
            if (pop == null) {
                throw new IllegalStateException("error adding pending sync operation "
                        + operation);
            }
            operation.pendingOperation = pop;
        }

        mOperationsMap.put(operationKey, operation);
        return true;
    }

    public void removeUserLocked(int userId) {
        ArrayList<SyncOperation> opsToRemove = new ArrayList<SyncOperation>();
        for (SyncOperation op : mOperationsMap.values()) {
            if (op.target.userId == userId) {
                opsToRemove.add(op);
            }
        }
            for (SyncOperation op : opsToRemove) {
                remove(op);
            }
    }

    /**
     * Remove the specified operation if it is in the queue.
     * @param operation the operation to remove
     */
    public void remove(SyncOperation operation) {
        boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
        SyncOperation operationToRemove = mOperationsMap.remove(operation.key);
        if (isLoggable) {
            Log.v(TAG, "Attempting to remove: " + operation.key);
        }
        if (operationToRemove == null) {
            if (isLoggable) {
                Log.v(TAG, "Could not find: " + operation.key);
            }
            return;
        }
        if (!mSyncStorageEngine.deleteFromPending(operationToRemove.pendingOperation)) {
            final String errorMessage = "unable to find pending row for " + operationToRemove;
            Log.e(TAG, errorMessage, new IllegalStateException(errorMessage));
        }
    }

    /** Reset backoffs for all operations in the queue. */
    public void clearBackoffs() {
        for (SyncOperation op : mOperationsMap.values()) {
            op.backoff = 0L;
            op.updateEffectiveRunTime();
        }
    }

    public void onBackoffChanged(SyncStorageEngine.EndPoint target, long backoff) {
        // For each op that matches the target of the changed op, update its
        // backoff and effectiveStartTime
        for (SyncOperation op : mOperationsMap.values()) {
            if (op.target.matchesSpec(target)) {
                op.backoff = backoff;
                op.updateEffectiveRunTime();
            }
        }
    }

    public void onDelayUntilTimeChanged(SyncStorageEngine.EndPoint target, long delayUntil) {
        // for each op that matches the target info of the provided op, change the delay time.
        for (SyncOperation op : mOperationsMap.values()) {
            if (op.target.matchesSpec(target)) {
                op.delayUntil = delayUntil;
                op.updateEffectiveRunTime();
            }
        }
    }

    /**
     * Remove all of the SyncOperations associated with a given target.
     *
     * @param info target object provided here can have null Account/provider. This is the case
     * where you want to remove all ops associated with a provider (null Account) or all ops
     * associated with an account (null provider).
     * @param extras option bundle to include to further specify which operation to remove. If this
     * bundle contains sync settings flags, they are ignored.
     */
    public void remove(final SyncStorageEngine.EndPoint info, Bundle extras) {
        Iterator<Map.Entry<String, SyncOperation>> entries = mOperationsMap.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, SyncOperation> entry = entries.next();
            SyncOperation syncOperation = entry.getValue();
            final SyncStorageEngine.EndPoint opInfo = syncOperation.target;
            if (!opInfo.matchesSpec(info)) {
                continue;
            }
            if (extras != null
                    && !SyncManager.syncExtrasEquals(
                        syncOperation.extras,
                        extras,
                        false /* no config flags*/)) {
                continue;
            }
            entries.remove();
            if (!mSyncStorageEngine.deleteFromPending(syncOperation.pendingOperation)) {
                final String errorMessage = "unable to find pending row for " + syncOperation;
                Log.e(TAG, errorMessage, new IllegalStateException(errorMessage));
            }
        }
    }

    public Collection<SyncOperation> getOperations() {
        return mOperationsMap.values();
    }

    public void dump(StringBuilder sb) {
        final long now = SystemClock.elapsedRealtime();
        sb.append("SyncQueue: ").append(mOperationsMap.size()).append(" operation(s)\n");
        for (SyncOperation operation : mOperationsMap.values()) {
            sb.append("  ");
            if (operation.effectiveRunTime <= now) {
                sb.append("READY");
            } else {
                sb.append(DateUtils.formatElapsedTime((operation.effectiveRunTime - now) / 1000));
            }
            sb.append(" - ");
            sb.append(operation.dump(mPackageManager, false)).append("\n");
        }
    }
}
