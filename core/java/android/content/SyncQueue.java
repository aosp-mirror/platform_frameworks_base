package android.content;

import com.google.android.collect.Maps;

import android.os.Bundle;
import android.os.SystemClock;
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
            // -1 is a special value that means expedited
            final int delay = op.expedited ? -1 : 0;
            SyncOperation syncOperation = new SyncOperation(
                    op.account, op.syncSource, op.authority, op.extras, delay);
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

    public void remove(SyncOperation operation) {
        SyncOperation operationToRemove = mOperationsMap.remove(operation.key);
        if (!mSyncStorageEngine.deleteFromPending(operationToRemove.pendingOperation)) {
            final String errorMessage = "unable to find pending row for " + operationToRemove;
            Log.e(TAG, errorMessage, new IllegalStateException(errorMessage));
        }
    }

    /**
     * Find the operation that should run next. Operations are sorted by their earliestRunTime,
     * prioritizing expedited operations. The earliestRunTime is adjusted by the sync adapter's
     * backoff and delayUntil times, if any.
     * @param now the current {@link android.os.SystemClock#elapsedRealtime()}
     * @return the operation that should run next and when it should run. The time may be in
     * the future. It is expressed in milliseconds since boot.
     */
    private Pair<SyncOperation, Long> nextOperation(long now) {
        SyncOperation lowestOp = null;
        long lowestOpRunTime = 0;
        for (SyncOperation op : mOperationsMap.values()) {
            // effectiveRunTime:
            //   - backoffTime > currentTime : backoffTime
            //   - backoffTime <= currentTime : op.runTime
            Pair<Long, Long> backoff = null;
            long delayUntilTime = 0;
            final boolean isManualSync =
                    op.extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false);
            if (!isManualSync) {
                backoff = mSyncStorageEngine.getBackoff(op.account, op.authority);
                delayUntilTime = mSyncStorageEngine.getDelayUntilTime(op.account, op.authority);
            }
            long backoffTime = Math.max(backoff != null ? backoff.first : 0, delayUntilTime);
            long opRunTime = backoffTime > now ? backoffTime : op.earliestRunTime;
            if (lowestOp == null
                    || (lowestOp.expedited == op.expedited
                        ? opRunTime < lowestOpRunTime
                        : op.expedited)) {
                lowestOp = op;
                lowestOpRunTime = opRunTime;
            }
        }
        if (lowestOp == null) {
            return null;
        }
        return Pair.create(lowestOp, lowestOpRunTime);
    }

    /**
     * Return when the next SyncOperation will be ready to run or null if there are
     * none.
     * @param now the current {@link android.os.SystemClock#elapsedRealtime()}, used to
     * decide if the sync operation is ready to run
     * @return when the next SyncOperation will be ready to run, expressed in elapsedRealtime()
     */
    public Long nextRunTime(long now) {
        Pair<SyncOperation, Long> nextOpAndRunTime = nextOperation(now);
        if (nextOpAndRunTime == null) {
            return null;
        }
        return nextOpAndRunTime.second;
    }

    /**
     * Find and return the SyncOperation that should be run next and is ready to run.
     * @param now the current {@link android.os.SystemClock#elapsedRealtime()}, used to
     * decide if the sync operation is ready to run
     * @return the SyncOperation that should be run next and is ready to run.
     */
    public SyncOperation nextReadyToRun(long now) {
        Pair<SyncOperation, Long> nextOpAndRunTime = nextOperation(now);
        if (nextOpAndRunTime == null || nextOpAndRunTime.second > now) {
            return null;
        }
        return nextOpAndRunTime.first;
    }

    public void clear(Account account, String authority) {
        Iterator<Map.Entry<String, SyncOperation>> entries = mOperationsMap.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, SyncOperation> entry = entries.next();
            SyncOperation syncOperation = entry.getValue();
            if (account != null && !syncOperation.account.equals(account)) continue;
            if (authority != null && !syncOperation.authority.equals(authority)) continue;
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
