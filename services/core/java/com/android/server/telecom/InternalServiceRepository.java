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

package com.android.server.telecom;

import static android.os.PowerWhitelistManager.REASON_UNKNOWN;

import android.content.Context;
import android.os.Binder;
import android.os.Process;

import com.android.internal.telecom.IDeviceIdleControllerAdapter;
import com.android.internal.telecom.IInternalServiceRetriever;
import com.android.server.DeviceIdleInternal;

/**
 * The Telecom APK can not access services stored in LocalService directly and since it is in the
 * SYSTEM process, it also can not use the *Manager interfaces
 * (see {@link Context#enforceCallingPermission(String, String)}). Instead, we must wrap these local
 * services in binder interfaces to allow Telecom access.
 */
public class InternalServiceRepository extends IInternalServiceRetriever.Stub {

    private final IDeviceIdleControllerAdapter.Stub mDeviceIdleControllerAdapter =
            new IDeviceIdleControllerAdapter.Stub() {
        @Override
        public void exemptAppTemporarilyForEvent(String packageName, long duration, int userHandle,
                String reason) {
            mDeviceIdleController.addPowerSaveTempWhitelistApp(Process.myUid(), packageName,
                    duration, userHandle, true /*sync*/, REASON_UNKNOWN, reason);
        }
    };

    private final DeviceIdleInternal mDeviceIdleController;

    public InternalServiceRepository(DeviceIdleInternal deviceIdleController) {
        mDeviceIdleController = deviceIdleController;
    }

    @Override
    public IDeviceIdleControllerAdapter getDeviceIdleController() {
        ensureSystemProcess();
        return mDeviceIdleControllerAdapter;
    }

    private void ensureSystemProcess() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            // Correctness check - this should never happen.
            throw new SecurityException("SYSTEM ONLY API.");
        }
    }
}
