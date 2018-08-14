/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.service.trust;

import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;

/**
 * Communication channel from the TrustAgentService back to TrustManagerService.
 * @hide
 */
oneway interface ITrustAgentServiceCallback {
    void grantTrust(CharSequence message, long durationMs, int flags);
    void revokeTrust();
    void setManagingTrust(boolean managingTrust);
    void onConfigureCompleted(boolean result, IBinder token);
    void addEscrowToken(in byte[] token, int userId);
    void isEscrowTokenActive(long handle, int userId);
    void removeEscrowToken(long handle, int userId);
    void unlockUserWithToken(long handle, in byte[] token, int userId);
    void showKeyguardErrorMessage(in CharSequence message);
}
