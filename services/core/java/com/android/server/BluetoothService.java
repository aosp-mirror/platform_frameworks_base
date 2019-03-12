/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.os.SystemProperties;

class BluetoothService extends SystemService {
    private static final String HEADLESS_SYSTEM_USER = "android.car.systemuser.headless";

    private BluetoothManagerService mBluetoothManagerService;
    private boolean mInitialized = false;

    public BluetoothService(Context context) {
        super(context);
        mBluetoothManagerService = new BluetoothManagerService(context);
    }

    private void initialize() {
        if (!mInitialized) {
            mBluetoothManagerService.handleOnBootPhase();
            mInitialized = true;
        }
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            publishBinderService(BluetoothAdapter.BLUETOOTH_MANAGER_SERVICE,
                    mBluetoothManagerService);
        } else if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY &&
                !SystemProperties.getBoolean(HEADLESS_SYSTEM_USER, false)) {
            initialize();
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        initialize();
        mBluetoothManagerService.handleOnSwitchUser(userHandle);
    }

    @Override
    public void onUnlockUser(int userHandle) {
        mBluetoothManagerService.handleOnUnlockUser(userHandle);
    }
}
