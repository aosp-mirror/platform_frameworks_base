/*
**
** Copyright 2010, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.app.admin;

import android.content.ComponentName;
import android.os.RemoteCallback;

/**
 * Internal IPC interface to the device policy service.
 * {@hide}
 */
interface IDevicePolicyManager {
    void setPasswordQuality(in ComponentName who, int quality);
    int getPasswordQuality(in ComponentName who);
    
    void setPasswordMinimumLength(in ComponentName who, int length);
    int getPasswordMinimumLength(in ComponentName who);
    
    boolean isActivePasswordSufficient();
    int getCurrentFailedPasswordAttempts();
    
    void setMaximumFailedPasswordsForWipe(in ComponentName admin, int num);
    int getMaximumFailedPasswordsForWipe(in ComponentName admin);
    
    boolean resetPassword(String password, int flags);
    
    void setMaximumTimeToLock(in ComponentName who, long timeMs);
    long getMaximumTimeToLock(in ComponentName who);
    
    void lockNow();
    
    void wipeData(int flags);
    
    void setActiveAdmin(in ComponentName policyReceiver);
    boolean isAdminActive(in ComponentName policyReceiver);
    List<ComponentName> getActiveAdmins();
    boolean packageHasActiveAdmins(String packageName);
    void getRemoveWarning(in ComponentName policyReceiver, in RemoteCallback result);
    void removeActiveAdmin(in ComponentName policyReceiver);
    
    void setActivePasswordState(int quality, int length);
    void reportFailedPasswordAttempt();
    void reportSuccessfulPasswordAttempt();
}
