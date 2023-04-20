/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.companion.datatransfer.contextsync;

import android.content.ComponentName;
import android.telecom.ConnectionService;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Service for Telecom to bind to when call metadata is synced between devices. */
public class CallMetadataSyncConnectionService extends ConnectionService {

    private TelecomManager mTelecomManager;
    private final Map<String, PhoneAccountHandle> mPhoneAccountHandles = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        mTelecomManager = getSystemService(TelecomManager.class);
    }

    /**
     * Registers a {@link android.telecom.PhoneAccount} for a given call-capable app on the synced
     * device.
     */
    public void registerPhoneAccount(String packageName, String humanReadableAppName) {
        final PhoneAccount phoneAccount = createPhoneAccount(packageName, humanReadableAppName);
        if (phoneAccount != null) {
            mTelecomManager.registerPhoneAccount(phoneAccount);
            mTelecomManager.enablePhoneAccount(mPhoneAccountHandles.get(packageName), true);
        }
    }

    /**
     * Unregisters a {@link android.telecom.PhoneAccount} for a given call-capable app on the synced
     * device.
     */
    public void unregisterPhoneAccount(String packageName) {
        mTelecomManager.unregisterPhoneAccount(mPhoneAccountHandles.remove(packageName));
    }

    @VisibleForTesting
    PhoneAccount createPhoneAccount(String packageName, String humanReadableAppName) {
        if (mPhoneAccountHandles.containsKey(packageName)) {
            // Already exists!
            return null;
        }
        final PhoneAccountHandle handle = new PhoneAccountHandle(
                new ComponentName(this, CallMetadataSyncConnectionService.class),
                UUID.randomUUID().toString());
        mPhoneAccountHandles.put(packageName, handle);
        return new PhoneAccount.Builder(handle, humanReadableAppName)
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER
                        | PhoneAccount.CAPABILITY_SELF_MANAGED).build();
    }
}
