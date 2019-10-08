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

package com.android.server.testing.shadows;

import android.annotation.Nullable;

import com.android.server.backup.DataChangedJournal;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.keyvalue.KeyValueBackupReporter;
import com.android.server.backup.keyvalue.KeyValueBackupTask;
import com.android.server.backup.transport.TransportClient;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.List;

@Implements(KeyValueBackupTask.class)
public class ShadowKeyValueBackupTask {
    @Nullable private static ShadowKeyValueBackupTask sLastShadow;

    /**
     * Retrieves the shadow for the last {@link KeyValueBackupTask} object created.
     *
     * @return The shadow or {@code null} if no object created since last {@link #reset()}.
     */
    @Nullable
    public static ShadowKeyValueBackupTask getLastCreated() {
        return sLastShadow;
    }

    public static void reset() {
        sLastShadow = null;
    }

    private OnTaskFinishedListener mListener;
    private List<String> mQueue;
    private List<String> mPendingFullBackups;

    @Implementation
    protected void __constructor__(
            UserBackupManagerService backupManagerService,
            TransportClient transportClient,
            String transportDirName,
            List<String> queue,
            @Nullable DataChangedJournal dataChangedJournal,
            KeyValueBackupReporter reporter,
            OnTaskFinishedListener listener,
            List<String> pendingFullBackups,
            boolean userInitiated,
            boolean nonIncremental) {
        mListener = listener;
        mQueue = queue;
        mPendingFullBackups = pendingFullBackups;
        sLastShadow = this;
    }

    @Implementation
    protected void execute() {
        mListener.onFinished("ShadowKeyValueBackupTask.execute()");
    }

    public List<String> getQueue() {
        return mQueue;
    }

    public List<String> getPendingFullBackups() {
        return mPendingFullBackups;
    }
}
