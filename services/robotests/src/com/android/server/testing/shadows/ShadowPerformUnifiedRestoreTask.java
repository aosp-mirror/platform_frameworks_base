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
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IRestoreObserver;
import android.content.pm.PackageInfo;

import com.android.server.backup.OperationStorage;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.restore.PerformUnifiedRestoreTask;
import com.android.server.backup.transport.TransportConnection;
import com.android.server.backup.utils.BackupEligibilityRules;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(PerformUnifiedRestoreTask.class)
public class ShadowPerformUnifiedRestoreTask {
    @Nullable private static ShadowPerformUnifiedRestoreTask sLastShadow;

    /**
     * Retrieves the shadow for the last {@link PerformUnifiedRestoreTask} object created.
     *
     * @return The shadow or {@code null} if no object created since last {@link #reset()}.
     */
    @Nullable
    public static ShadowPerformUnifiedRestoreTask getLastCreated() {
        return sLastShadow;
    }

    public static void reset() {
        sLastShadow = null;
    }

    private UserBackupManagerService mBackupManagerService;
    @Nullable private PackageInfo mPackage;
    private boolean mIsFullSystemRestore;
    @Nullable private String[] mFilterSet;
    private OnTaskFinishedListener mListener;

    @Implementation
    protected void __constructor__(
            UserBackupManagerService backupManagerService,
            OperationStorage operationStorage,
            TransportConnection transportConnection,
            IRestoreObserver observer,
            IBackupManagerMonitor monitor,
            long restoreSetToken,
            @Nullable PackageInfo targetPackage,
            int pmToken,
            boolean isFullSystemRestore,
            @Nullable String[] filterSet,
            OnTaskFinishedListener listener,
            BackupEligibilityRules backupEligibilityRules) {
        mBackupManagerService = backupManagerService;
        mPackage = targetPackage;
        mIsFullSystemRestore = isFullSystemRestore;
        mFilterSet = filterSet;
        mListener = listener;
        sLastShadow = this;
    }

    @Implementation
    protected void execute() {
        mBackupManagerService.setRestoreInProgress(false);
        mListener.onFinished("ShadowPerformUnifiedRestoreTask.execute()");
    }

    public PackageInfo getPackage() {
        return mPackage;
    }

    public String[] getFilterSet() {
        return mFilterSet;
    }

    public boolean isFullSystemRestore() {
        return mIsFullSystemRestore;
    }
}
