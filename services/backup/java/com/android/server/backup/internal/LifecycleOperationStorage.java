/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.backup.internal;

import static com.android.server.backup.BackupManagerService.DEBUG;
import static com.android.server.backup.BackupManagerService.MORE_DEBUG;

import android.annotation.UserIdInt;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.OperationStorage;

import com.google.android.collect.Sets;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * LifecycleOperationStorage is responsible for maintaining a set of currently
 * active operations.  Each operation has a type and state, and a callback that
 * can receive events upon operation completion or cancellation.  It may also
 * be associated with one or more package names.
 *
 * An operation wraps a {@link BackupRestoreTask} within it.
 * It's the responsibility of this task to remove the operation from this array.
 *
 * If type of operation is {@code OP_TYPE_WAIT}, it is waiting for an ACK or
 * timeout.
 *
 * A BackupRestore task gets notified of AVK/timeout for the operation via
 * {@link BackupRestoreTask#handleCancel()},
 * {@link BackupRestoreTask#operationComplete()} and {@code notifyAll} called
 * on the {@code mCurrentOpLock}.
 *
 * {@link LifecycleOperationStorage#waitUntilOperationComplete(int)} is used in
 * various places to 'wait' for notifyAll and detect change of pending state of
 * an operation. So typically, an operation will be removed from this array by:
 * - {@link BackupRestoreTask#handleCancel()} and
 * - {@link BackupRestoreTask#operationComplete()} OR
 *   {@link BackupRestoreTask#waitUntilOperationComplete()}.
 * Do not remove at both these places because {@code waitUntilOperationComplete}
 * relies on the operation being present to determine its completion status.
 *
 * If type of operation is {@code OP_BACKUP}, it is a task running backups. It
 * provides a handle to cancel backup tasks.
 */
public class LifecycleOperationStorage implements OperationStorage {
    private static final String TAG = "LifecycleOperationStorage";

    private final int mUserId;

    private final Object mOperationsLock = new Object();

    // Bookkeeping of in-flight operations. The operation token is the index of
    // the entry in the pending operations list.
    @GuardedBy("mOperationsLock")
    private final SparseArray<Operation> mOperations = new SparseArray<>();

    // Association from package name to one or more operations relating to that
    // package.
    @GuardedBy("mOperationsLock")
    private final Map<String, Set<Integer>> mOpTokensByPackage = new HashMap<>();

    public LifecycleOperationStorage(@UserIdInt int userId) {
        this.mUserId = userId;
    }

    /** See {@link OperationStorage#registerOperation()} */
    @Override
    public void registerOperation(int token, @OpState int initialState,
            BackupRestoreTask task, @OpType int type) {
        registerOperationForPackages(token, initialState, Sets.newHashSet(), task, type);
    }

    /** See {@link OperationStorage#registerOperationForPackages()} */
    @Override
    public void registerOperationForPackages(int token, @OpState int initialState,
            Set<String> packageNames, BackupRestoreTask task, @OpType int type) {
        synchronized (mOperationsLock) {
            mOperations.put(token, new Operation(initialState, task, type));
            for (String packageName : packageNames) {
                Set<Integer> tokens = mOpTokensByPackage.get(packageName);
                if (tokens == null) {
                    tokens = new HashSet<Integer>();
                }
                tokens.add(token);
                mOpTokensByPackage.put(packageName, tokens);
            }
        }
    }

    /** See {@link OperationStorage#removeOperation()} */
    @Override
    public void removeOperation(int token) {
        synchronized (mOperationsLock) {
            mOperations.remove(token);
            Set<String> packagesWithTokens = mOpTokensByPackage.keySet();
            for (String packageName : packagesWithTokens) {
                Set<Integer> tokens = mOpTokensByPackage.get(packageName);
                if (tokens == null) {
                    continue;
                }
                tokens.remove(token);
                mOpTokensByPackage.put(packageName, tokens);
            }
        }
    }

    /** See {@link OperationStorage#numOperations()}. */
    @Override
    public int numOperations() {
        synchronized (mOperationsLock) {
            return mOperations.size();
        }
    }

    /** See {@link OperationStorage#isBackupOperationInProgress()}. */
    @Override
    public boolean isBackupOperationInProgress() {
        synchronized (mOperationsLock) {
            for (int i = 0; i < mOperations.size(); i++) {
                Operation op = mOperations.valueAt(i);
                if (op.type == OpType.BACKUP && op.state == OpState.PENDING) {
                    return true;
                }
            }
            return false;
        }
    }

    /** See {@link OperationStorage#operationTokensForPackage()} */
    @Override
    public Set<Integer> operationTokensForPackage(String packageName) {
        synchronized (mOperationsLock) {
            final Set<Integer> tokens = mOpTokensByPackage.get(packageName);
            Set<Integer> result = Sets.newHashSet();
            if (tokens != null) {
                result.addAll(tokens);
            }
            return result;
        }
    }

    /** See {@link OperationStorage#operationTokensForOpType()} */
    @Override
    public Set<Integer> operationTokensForOpType(@OpType int type) {
        Set<Integer> tokens = Sets.newHashSet();
        synchronized (mOperationsLock) {
            for (int i = 0; i < mOperations.size(); i++) {
                final Operation op = mOperations.valueAt(i);
                final int token = mOperations.keyAt(i);
                if (op.type == type) {
                    tokens.add(token);
                }
            }
            return tokens;
        }
    }

    /** See {@link OperationStorage#operationTokensForOpState()} */
    @Override
    public Set<Integer> operationTokensForOpState(@OpState int state) {
        Set<Integer> tokens = Sets.newHashSet();
        synchronized (mOperationsLock) {
            for (int i = 0; i < mOperations.size(); i++) {
                final Operation op = mOperations.valueAt(i);
                final int token = mOperations.keyAt(i);
                if (op.state == state) {
                    tokens.add(token);
                }
            }
            return tokens;
        }
    }

    /**
     * A blocking function that blocks the caller until the operation identified
     * by {@code token} is complete - either via a message from the backup,
     * agent or through cancellation.
     *
     * @param token the operation token specified when registering the operation
     * @param callback a lambda which is invoked once only when the operation
     *                 completes - ie. if this method is called twice for the
     *                 same token, the lambda is not invoked the second time.
     * @return true if the operation was ACKed prior to or during this call.
     */
    public boolean waitUntilOperationComplete(int token, IntConsumer callback) {
        if (MORE_DEBUG) {
            Slog.i(TAG, "[UserID:" + mUserId + "] Blocking until operation complete for "
                    + Integer.toHexString(token));
        }
        @OpState int finalState = OpState.PENDING;
        Operation op = null;
        synchronized (mOperationsLock) {
            while (true) {
                op = mOperations.get(token);
                if (op == null) {
                    // mysterious disappearance: treat as success with no callback
                    break;
                } else {
                    if (op.state == OpState.PENDING) {
                        try {
                            mOperationsLock.wait();
                        } catch (InterruptedException e) {
                            Slog.w(TAG, "Waiting on mOperationsLock: ", e);
                        }
                        // When the wait is notified we loop around and recheck the current state
                    } else {
                        if (MORE_DEBUG) {
                            Slog.d(TAG, "[UserID:" + mUserId
                                    + "] Unblocked waiting for operation token="
                                    + Integer.toHexString(token));
                        }
                        // No longer pending; we're done
                        finalState = op.state;
                        break;
                    }
                }
            }
        }

        removeOperation(token);
        if (op != null) {
            callback.accept(op.type);
        }
        if (MORE_DEBUG) {
            Slog.v(TAG, "[UserID:" + mUserId + "] operation " + Integer.toHexString(token)
                    + " complete: finalState=" + finalState);
        }
        return finalState == OpState.ACKNOWLEDGED;
    }

    /**
     * Signals that an ongoing operation is complete: after a currently-active
     * backup agent has notified us that it has completed the outstanding
     * asynchronous backup/restore operation identified by the supplied
     * {@code} token.
     *
     * @param token the operation token specified when registering the operation
     * @param result a result code or error code for the completed operation
     * @param callback a lambda that is invoked if the completion moves the
     *                 operation from PENDING to ACKNOWLEDGED state.
     */
    public void onOperationComplete(int token, long result, Consumer<BackupRestoreTask> callback) {
        if (MORE_DEBUG) {
            Slog.v(TAG, "[UserID:" + mUserId + "] onOperationComplete: "
                    + Integer.toHexString(token) + " result=" + result);
        }
        Operation op = null;
        synchronized (mOperationsLock) {
            op = mOperations.get(token);
            if (op != null) {
                if (op.state == OpState.TIMEOUT) {
                    // The operation already timed out, and this is a late response.  Tidy up
                    // and ignore it; we've already dealt with the timeout.
                    op = null;
                    mOperations.remove(token);
                } else if (op.state == OpState.ACKNOWLEDGED) {
                    if (DEBUG) {
                        Slog.w(TAG, "[UserID:" + mUserId + "] Received duplicate ack for token="
                                + Integer.toHexString(token));
                    }
                    op = null;
                    mOperations.remove(token);
                } else if (op.state == OpState.PENDING) {
                    // Can't delete op from mOperations. waitUntilOperationComplete can be
                    // called after we we receive this call.
                    op.state = OpState.ACKNOWLEDGED;
                }
            }
            mOperationsLock.notifyAll();
        }

        // Invoke the operation's completion callback, if there is one.
        if (op != null && op.callback != null) {
            callback.accept(op.callback);
        }
    }

    /**
     * Cancel the operation associated with {@code token}.  Cancellation may be
     * propagated to the operation's callback (a {@link BackupRestoreTask}) if
     * the operation has one, and the cancellation is due to the operation
     * timing out.
     *
     * @param token the operation token specified when registering the operation
     * @param cancelAll this is passed on when propagating the cancellation
     * @param operationTimedOutCallback a lambda that is invoked with the
     *                                  operation type where the operation is
     *                                  cancelled due to timeout, allowing the
     *                                  caller to do type-specific clean-ups.
     */
    public void cancelOperation(
            int token, boolean cancelAll, IntConsumer operationTimedOutCallback) {
        // Notify any synchronous waiters
        Operation op = null;
        synchronized (mOperationsLock) {
            op = mOperations.get(token);
            if (MORE_DEBUG) {
                if (op == null) {
                    Slog.w(TAG, "[UserID:" + mUserId + "] Cancel of token "
                            + Integer.toHexString(token) + " but no op found");
                }
            }
            int state = (op != null) ? op.state : OpState.TIMEOUT;
            if (state == OpState.ACKNOWLEDGED) {
                // The operation finished cleanly, so we have nothing more to do.
                if (DEBUG) {
                    Slog.w(TAG, "[UserID:" + mUserId + "] Operation already got an ack."
                            + "Should have been removed from mCurrentOperations.");
                }
                op = null;
                mOperations.delete(token);
            } else if (state == OpState.PENDING) {
                if (DEBUG) {
                    Slog.v(TAG, "[UserID:" + mUserId + "] Cancel: token="
                            + Integer.toHexString(token));
                }
                op.state = OpState.TIMEOUT;
                // Can't delete op from mOperations here. waitUntilOperationComplete may be
                // called after we receive cancel here. We need this op's state there.
                operationTimedOutCallback.accept(op.type);
            }
            mOperationsLock.notifyAll();
        }

        // If there's a TimeoutHandler for this event, call it
        if (op != null && op.callback != null) {
            if (MORE_DEBUG) {
                Slog.v(TAG, "[UserID:" + mUserId + "   Invoking cancel on " + op.callback);
            }
            op.callback.handleCancel(cancelAll);
        }
    }
}
