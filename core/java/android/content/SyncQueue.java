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

package android.content;

import android.os.SystemClock;
import com.google.android.collect.Maps;

import android.util.Pair;
import android.util.Log;
import android.accounts.Account;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Iterator;

/**
 *
 * @hide
 */
public class SyncQueue {
    private static final String TAG = "SyncManager";
    private SyncStorageEngine mSyncStorageEngine;

    // A Map of SyncOperations operationKey -> SyncOperation that is designed for
    // quick lookup of an enqueued SyncOperation.
    private final HashMap<String, SyncOperation> mOperationsMap = Maps.newHashMap();

    public SyncQueue(SyncStorageEngine syncStorageEngine) {
        mSyncStorageEngine = syncStorageEngine;
        ArrayList<SyncStorageEngine.PendingOperation> ops
                = mSyncStorageEngine.getPendingOperations();
        final int N = ops.size();
        for (int i=0; i<N; i++) {
            SyncStorageEngine.PendingOperation op = ops.get(i);
            SyncOperation syncOperation = new SyncOperation(
                    op.account, op.syncSource, op.authority, op.extras, 0 /* delay */);
            syncOperation.expedited = op.expedited;
            syncOperation.pendingOperation = op;
            add(syncOperation, op);
        }
    }

    public boolean add(SyncOperation operation) {
        return add(operation, null /* this is not coming from the database */);
    }

    private boolean add(SyncOperation operation,
            SyncStorageEngine.PendingOperation pop) {
        // - if an operation with the same key exists and this one should run earlier,
        //   update the earliestRunTime of the existing to the new time
        // - if an operation with the same key exists and if this one should run
        //   later, ignore it
        // - if no operation exists then add the new one
        final String operationKey = operation.key;
        final SyncOperation existingOperation = mOperationsMap.get(operationKey);

        if (existingOperation != null) {
            boolean changed = false;
            if (existingOperation.expedited == operation.expedited) {
                final long newRunTime =
                        Math.min(existingOperation.earliestRunTime, operation.earliestRunTime);
                if (existingOperation.earliestRunTime != newRunTime) {
                    existingOperation.earliestRunTime = newRunTime;
                    changed = true;
                }
            } else {
                if (operation.expedited) {
                    existingOperation.expedited = true;
                    changed = true;
                }
            }
            return changed;
        }

        operation.pendingOperation = pop;
        if (operation.pendingOperation == null) {
            pop = new SyncStorageEngine.PendingOperation(
                            operation.account, operation.syncSource,
                            operation.authority, operation.extras, operation.expedited);
            pop = mSyncStorageEngine.insertIntoPending(pop);
            if (pop == null) {
                throw new IllegalStateException("error adding pending sync operation "
                        + operation);
            }
            operation.pendingOperation = pop;
        }

        mOperationsMap.put(operationKey, operation);
        return true;
    }

    /**
     * Remove the specified operation if it is in the queue.
     * @param operation the operation to remove
     */
    public void remove(SyncOperation operation) {
        SyncOperation operationToRemove = mOperationsMap.remove(operation.key);
        if (operationToRemove == null) {
            return;
        }
        if (!mSyncStorageEngine.deleteFromPending(operationToRemove.pendingOperation)) {
            final String errorMessage = "unable to find pending row for " + operationToRemove;
            Log.e(TAG, errorMessage, new IllegalStateException(errorMessage));
        }
    }

    /**
     * Find the operation that should run next. Operations are sorted by their earliestRunTime,
     * prioritizing first those with a syncable state of "unknown" that aren't retries then
     * expedited operations.
     * The earliestRunTime is adjusted by the sync adapter's backoff and delayUntil times, if any.
     * @return the operation that should run next and when it should run. The time may be in
     * the future. It is expressed in milliseconds since boot.
     */
    public Pair<SyncOperation, Long> nextOperation() {
        SyncOperation best = null;
        long bestRunTime = 0;
        boolean bestIsInitial = false;
        for (SyncOperation op : mOperationsMap.values()) {
            final long opRunTime = getOpTime(op);
            final boolean opIsInitial = getIsInitial(op);
            if (isOpBetter(best, bestRunTime, bestIsInitial, op, opRunTime, opIsInitial)) {
                best = op;
                bestIsInitial = opIsInitial;
                bestRunTime = opRunTime;
            }
        }
        if (best == null) {
            return null;
        }
        return Pair.create(best, bestRunTime);
    }

    // VisibleForTesting
    long getOpTime(SyncOperation op) {
        long opRunTime = op.earliestRunTime;
        if (!op.extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, false)) {
            Pair<Long, Long> backoff = mSyncStorageEngine.getBackoff(op.account, op.authority);
            long delayUntil = mSyncStorageEngine.getDelayUntilTime(op.account, op.authority);
            opRunTime = Math.max(
                    Math.max(opRunTime, delayUntil),
                    backoff != null ? backoff.first : 0);
        }
        return opRunTime;
    }

    // VisibleForTesting
    boolean getIsInitial(SyncOperation op) {
        // Initial op is defined as an op with an unknown syncable that is not a retry.
        // We know a sync is a retry if the intialization flag is set, since that will only
        // be set by the sync dispatching code, thus if it is set it must have already been
        // dispatched
        return !op.extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, false)
        && mSyncStorageEngine.getIsSyncable(op.account, op.authority) < 0;
    }

    // return true if op is a better candidate than best. Rules:
    // if the "Initial" state differs, make the current the best if it is "Initial".
    // else, if the expedited state differs, pick the expedited unless it is backed off and the
    // non-expedited isn't
    // VisibleForTesting
    boolean isOpBetter(
            SyncOperation best, long bestRunTime, boolean bestIsInitial,
            SyncOperation op, long opRunTime, boolean opIsInitial) {
        boolean setBest = false;
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG,  "nextOperation: Processing op: " + op);
        }
        if (best == null) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG,  "   First op selected");
            }
            setBest = true;
        } else if (bestIsInitial == opIsInitial) {
            if (best.expedited == op.expedited) {
                if (opRunTime < bestRunTime) {
                    //  if both have same level, earlier time wins
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG,  "   Same expedite level - new op selected");
                    }
                    setBest = true;
                }
            } else {
                final long now = SystemClock.elapsedRealtime();
                if (op.expedited) {
                    if (opRunTime <= now || bestRunTime > now) {
                        // if op is expedited, it wins unless op can't run yet and best can
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG,  "   New op is expedited and can run - new op selected");
                        }
                        setBest = true;
                    } else {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG,  "   New op is expedited but can't run and best can");
                        }
                    }
                } else {
                    if (bestRunTime > now && opRunTime <= now) {
                        // if best is expedited but can't run yet and op can run, op wins
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG,
                                    "   New op is not expedited but can run - new op selected");
                        }
                        setBest = true;
                    }
                }
            }
        } else {
            if (opIsInitial) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG,  "   New op is init - new op selected");
                }
                setBest = true;
            }
        }
        return setBest;
    }

    /**
     * Find and return the SyncOperation that should be run next and is ready to run.
     * @param now the current {@link android.os.SystemClock#elapsedRealtime()}, used to
     * decide if the sync operation is ready to run
     * @return the SyncOperation that should be run next and is ready to run.
     */
    public Pair<SyncOperation, Long> nextReadyToRun(long now) {
        Pair<SyncOperation, Long> nextOpAndRunTime = nextOperation();
        if (nextOpAndRunTime == null || nextOpAndRunTime.second > now) {
            return null;
        }
        return nextOpAndRunTime;
    }

    public void remove(Account account, String authority) {
        Iterator<Map.Entry<String, SyncOperation>> entries = mOperationsMap.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, SyncOperation> entry = entries.next();
            SyncOperation syncOperation = entry.getValue();
            if (account != null && !syncOperation.account.equals(account)) {
                continue;
            }
            if (authority != null && !syncOperation.authority.equals(authority)) {
                continue;
            }
            entries.remove();
            if (!mSyncStorageEngine.deleteFromPending(syncOperation.pendingOperation)) {
                final String errorMessage = "unable to find pending row for " + syncOperation;
                Log.e(TAG, errorMessage, new IllegalStateException(errorMessage));
            }
        }
    }

    public void dump(StringBuilder sb) {
        sb.append("SyncQueue: ").append(mOperationsMap.size()).append(" operation(s)\n");
        for (SyncOperation operation : mOperationsMap.values()) {
            sb.append(operation).append("\n");
        }
    }
}
