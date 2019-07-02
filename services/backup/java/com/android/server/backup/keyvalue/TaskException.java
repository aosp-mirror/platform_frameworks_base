/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.keyvalue;

import android.app.backup.BackupTransport;

import com.android.internal.util.Preconditions;

/**
 * The key-value backup task has failed, no more packages will be processed and we shouldn't attempt
 * any more backups now. These can be caused by transport failures (as opposed to agent failures).
 *
 * @see KeyValueBackupTask
 * @see AgentException
 */
class TaskException extends BackupException {
    private static final int DEFAULT_STATUS = BackupTransport.TRANSPORT_ERROR;

    static TaskException stateCompromised() {
        return new TaskException(/* stateCompromised */ true, DEFAULT_STATUS);
    }

    static TaskException stateCompromised(Exception cause) {
        if (cause instanceof TaskException) {
            TaskException exception = (TaskException) cause;
            return new TaskException(cause, /* stateCompromised */ true, exception.getStatus());
        }
        return new TaskException(cause, /* stateCompromised */ true, DEFAULT_STATUS);
    }

    static TaskException forStatus(int status) {
        Preconditions.checkArgument(
                status != BackupTransport.TRANSPORT_OK, "Exception based on TRANSPORT_OK");
        return new TaskException(/* stateCompromised */ false, status);
    }

    static TaskException causedBy(Exception cause) {
        if (cause instanceof TaskException) {
            return (TaskException) cause;
        }
        return new TaskException(cause, /* stateCompromised */ false, DEFAULT_STATUS);
    }

    static TaskException create() {
        return new TaskException(/* stateCompromised */ false, DEFAULT_STATUS);
    }

    private final boolean mStateCompromised;
    private final int mStatus;

    private TaskException(Exception cause, boolean stateCompromised, int status) {
        super(cause);
        mStateCompromised = stateCompromised;
        mStatus = status;
    }

    private TaskException(boolean stateCompromised, int status) {
        mStateCompromised = stateCompromised;
        mStatus = status;
    }

    boolean isStateCompromised() {
        return mStateCompromised;
    }

    int getStatus() {
        return mStatus;
    }
}
