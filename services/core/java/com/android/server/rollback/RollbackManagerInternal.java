/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.rollback;

import android.annotation.NonNull;
import android.os.UserHandle;

import java.util.List;

/**
 * A partial interface of IRollbackManager used by the system server only.
 *
 * @hide
 */
public interface RollbackManagerInternal {
    /**
     * Exposed for use from the system server only. Callback from the package
     * manager during the install flow when user data can be backed up and restored for a given
     * package.
     *
     * @param packageName Name of the package to restore/backup user data for
     * @param users Users whose data to be restored/backed up
     * @param appId ID of the package to restore/backup user data for
     * @param ceDataInode The index node of CE data to restore/backup
     * @param seInfo The seinfo tag used by SELinux policy
     * @param token Used to inform the package manager that the pending package install is finished
     */
    void snapshotAndRestoreUserData(@NonNull String packageName, @NonNull List<UserHandle> users,
            int appId, long ceDataInode, @NonNull String seInfo, int token);

    /**
     * Used by the staging manager to notify the RollbackManager that a session is
     * being staged. In the case of multi-package sessions, the specified sessionId
     * is that of the parent session.
     *
     * NOTE: This call is synchronous.
     *
     * @param sessionId The session ID that is being staged
     * @return The rollback id if rollback was enabled successfully, or -1 if not.
     */
    int notifyStagedSession(int sessionId);

    /**
     * Used by the staging manager to notify the RollbackManager of the apk
     * session for a staged session.
     *
     * @param originalSessionId The original session ID where this apk session belongs
     * @param apkSessionId The ID of this apk session
     */
    void notifyStagedApkSession(int originalSessionId, int apkSessionId);
}
