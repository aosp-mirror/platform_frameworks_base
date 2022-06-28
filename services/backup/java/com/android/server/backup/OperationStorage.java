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

package com.android.server.backup;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

/**
 * OperationStorage is an abstraction around a set of active operations.
 *
 * Operations are registered with a token that must first be obtained from
 * {@link UserBackupManagerService#generateRandomIntegerToken()}.  When
 * registering, the caller may also associate a set of package names with
 * the operation.
 *
 * TODO(b/208442527): have the token be generated within and returned by
 *                    registerOperation, as it should be an internal detail.
 *
 * Operations have a type and a state.  Although ints, the values that can
 * be used are defined in {@link UserBackupManagerService}.  If the type of
 * an operation is OP_BACKUP, then it represents a task running backups. The
 * task is provided when registering the operation because it provides a
 * handle to cancel the backup.
 */
public interface OperationStorage {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        OpState.PENDING,
        OpState.ACKNOWLEDGED,
        OpState.TIMEOUT
    })
    public @interface OpState {
        // The operation is in progress.
        int PENDING = 0;
        // The operation has been acknowledged.
        int ACKNOWLEDGED = 1;
        // The operation has timed out.
        int TIMEOUT = -1;
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        OpType.BACKUP_WAIT,
        OpType.RESTORE_WAIT,
        OpType.BACKUP,
    })
    public @interface OpType {
        // Waiting for backup agent to respond during backup operation.
        int BACKUP_WAIT = 0;
        // Waiting for backup agent to respond during restore operation.
        int RESTORE_WAIT = 1;
        // An entire backup operation spanning multiple packages.
        int BACKUP = 2;
    }

    /**
     * Record an ongoing operation of given type and in the given initial
     * state. The associated task is used as a callback.
     *
     * @param token        an operation token issued by
     *                     {@link UserBackupManagerService#generateRandomIntegerToken()}
     * @param initialState the state that the operation starts in
     * @param task         the {@link BackupRestoreTask} that is expected to
     *                     remove the operation on completion, and which may
     *                     be notified if the operation requires cancelling.
     * @param type         the type of the operation.
     */
    void registerOperation(int token, @OpState int initialState,
            BackupRestoreTask task, @OpType int type);

    /**
     * See {@link #registerOperation()}.  In addition this method accepts a set
     * of package names which are associated with the operation.
     *
     * @param token        See {@link #registerOperation()}
     * @param initialState See {@link #registerOperation()}
     * @param packageNames the package names to associate with the operation.
     * @param task         See {@link #registerOperation()}
     * @param type         See {@link #registerOperation()}
     */
    void registerOperationForPackages(int token, @OpState int initialState,
            Set<String> packageNames, BackupRestoreTask task, @OpType int type);

    /**
     * Remove the operation identified by token.  This is called when the
     * operation is no longer in progress and should be dropped. Any association
     * with package names provided in {@link #registerOperation()} is dropped as
     * well.
     *
     * @param token the operation token specified when registering the operation.
     */
    void removeOperation(int token);

    /**
     * Obtain the number of currently registered operations.
     *
     * @return the number of currently registered operations.
     */
    int numOperations();

    /**
     * Determine if a backup operation is in progress or not.
     *
     * @return true if any operation is registered of type BACKUP and in
     *         state PENDING.
     */
    boolean isBackupOperationInProgress();

    /**
     * Obtain a set of operation tokens for all pending operations that were
     * registered with an association to the specified package name.
     *
     * @param packageName the name of the package used at registration time
     *
     * @return a set of operation tokens associated to package name.
     */
    Set<Integer> operationTokensForPackage(String packageName);

    /**
     * Obtain a set of operation tokens for all pending operations that are
     * of the specified operation type.
     *
     * @param type the type of the operation provided at registration time.
     *
     * @return a set of operation tokens for operations of that type.
     */
    Set<Integer> operationTokensForOpType(@OpType int type);

    /**
     * Obtain a set of operation tokens for all pending operations that are
     * currently in the specified operation state.
     *
     * @param state the state of the operation.
     *
     * @return a set of operation tokens for operations in that state.
     */
    Set<Integer> operationTokensForOpState(@OpState int state);
};
