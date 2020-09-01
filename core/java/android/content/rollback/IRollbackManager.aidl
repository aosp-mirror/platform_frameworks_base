/**
 * Copyright (c) 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content.rollback;

import android.content.pm.ParceledListSlice;
import android.content.rollback.RollbackInfo;
import android.content.IntentSender;

/** {@hide} */
interface IRollbackManager {

    ParceledListSlice getAvailableRollbacks();
    ParceledListSlice getRecentlyCommittedRollbacks();

    void commitRollback(int rollbackId, in ParceledListSlice causePackages,
            String callerPackageName, in IntentSender statusReceiver);

    // Exposed for use from the system server only. Callback from the package
    // manager during the install flow when user data can be backed up and restored for a given
    // package.
    void snapshotAndRestoreUserData(String packageName, in int[] userIds, int appId, long ceDataInode,
            String seInfo, int token);

    // Exposed for test purposes only.
    void reloadPersistedData();

    // Exposed for test purposes only.
    void expireRollbackForPackage(String packageName);

    // Used by the staging manager to notify the RollbackManager that a session is
    // being staged. In the case of multi-package sessions, the specified sessionId
    // is that of the parent session.
    // Returns the rollback id if rollback was enabled successfully, or -1 if not.
    //
    // NOTE: This call is synchronous.
    int notifyStagedSession(int sessionId);

    // Used by the staging manager to notify the RollbackManager of the apk
    // session for a staged session.
    void notifyStagedApkSession(int originalSessionId, int apkSessionId);

    // For test purposes only.
    void blockRollbackManager(long millis);
}
