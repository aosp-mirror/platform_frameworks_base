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

package android.app;

import android.content.ComponentName;
import android.os.RemoteCallback;

/**
 * Internal IPC interface to the device policy service.
 * {@hide}
 */
interface IDevicePolicyManager {
    void setPasswordMode(in ComponentName who, int mode);
    int getPasswordMode();
    
    void setMinimumPasswordLength(in ComponentName who, int length);
    int getMinimumPasswordLength();
    
    boolean isActivePasswordSufficient();
    int getCurrentFailedPasswordAttempts();
    void setMaximumFailedPasswordsForWipe(in ComponentName admin, int num);
    
    boolean resetPassword(String password);
    
    void setMaximumTimeToLock(in ComponentName who, long timeMs);
    long getMaximumTimeToLock();
    
    void lockNow();
    
    void wipeData(int flags);
    
    void setActiveAdmin(in ComponentName policyReceiver);
    ComponentName getActiveAdmin();
    void getRemoveWarning(in ComponentName policyReceiver, in RemoteCallback result);
    void removeActiveAdmin(in ComponentName policyReceiver);
    
    void setActivePasswordState(int mode, int length);
    void reportFailedPasswordAttempt();
    void reportSuccessfulPasswordAttempt();
}
