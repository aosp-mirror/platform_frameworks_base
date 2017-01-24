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

import android.os.PersistableBundle;
import android.os.UserHandle;
import android.service.trust.ITrustAgentServiceCallback;

/**
 * Communication channel from TrustManagerService to the TrustAgent.
 * @hide
 */
interface ITrustAgentService {
    oneway void onUnlockAttempt(boolean successful);
    oneway void onUnlockLockout(int timeoutMs);
    oneway void onTrustTimeout();
    oneway void onDeviceLocked();
    oneway void onDeviceUnlocked();
    oneway void onConfigure(in List<PersistableBundle> options, IBinder token);
    oneway void setCallback(ITrustAgentServiceCallback callback);
    oneway void onEscrowTokenAdded(in byte[] token, long handle, in UserHandle user);
    oneway void onTokenStateReceived(long handle, int tokenState);
    oneway void onEscrowTokenRemoved(long handle, boolean successful);
}
