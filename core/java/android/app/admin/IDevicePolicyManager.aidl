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

    void setPasswordMinimumUpperCase(in ComponentName who, int length);
    int getPasswordMinimumUpperCase(in ComponentName who);

    void setPasswordMinimumLowerCase(in ComponentName who, int length);
    int getPasswordMinimumLowerCase(in ComponentName who);

    void setPasswordMinimumLetters(in ComponentName who, int length);
    int getPasswordMinimumLetters(in ComponentName who);

    void setPasswordMinimumNumeric(in ComponentName who, int length);
    int getPasswordMinimumNumeric(in ComponentName who);

    void setPasswordMinimumSymbols(in ComponentName who, int length);
    int getPasswordMinimumSymbols(in ComponentName who);

    void setPasswordMinimumNonLetter(in ComponentName who, int length);
    int getPasswordMinimumNonLetter(in ComponentName who);
    
    void setPasswordHistoryLength(in ComponentName who, int length);
    int getPasswordHistoryLength(in ComponentName who);

    void setPasswordExpirationTimeout(in ComponentName who, long expiration);
    long getPasswordExpirationTimeout(in ComponentName who);

    long getPasswordExpiration(in ComponentName who);

    boolean isActivePasswordSufficient();
    int getCurrentFailedPasswordAttempts();
    
    void setMaximumFailedPasswordsForWipe(in ComponentName admin, int num);
    int getMaximumFailedPasswordsForWipe(in ComponentName admin);
    
    boolean resetPassword(String password, int flags);
    
    void setMaximumTimeToLock(in ComponentName who, long timeMs);
    long getMaximumTimeToLock(in ComponentName who);
    
    void lockNow();
    
    void wipeData(int flags);

    ComponentName setGlobalProxy(in ComponentName admin, String proxySpec, String exclusionList);
    ComponentName getGlobalProxyAdmin();

    int setStorageEncryption(in ComponentName who, boolean encrypt);
    boolean getStorageEncryption(in ComponentName who);
    int getStorageEncryptionStatus();

    void setCameraDisabled(in ComponentName who, boolean disabled);
    boolean getCameraDisabled(in ComponentName who);

    void setActiveAdmin(in ComponentName policyReceiver, boolean refreshing);
    boolean isAdminActive(in ComponentName policyReceiver);
    List<ComponentName> getActiveAdmins();
    boolean packageHasActiveAdmins(String packageName);
    void getRemoveWarning(in ComponentName policyReceiver, in RemoteCallback result);
    void removeActiveAdmin(in ComponentName policyReceiver);
    boolean hasGrantedPolicy(in ComponentName policyReceiver, int usesPolicy);
    
    void setActivePasswordState(int quality, int length, int letters, int uppercase, int lowercase,
        int numbers, int symbols, int nonletter);
    void reportFailedPasswordAttempt();
    void reportSuccessfulPasswordAttempt();
}
