/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Build;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.phone.BiometricUnlockController;

import javax.inject.Inject;

/**
 * Class that only runs on debuggable builds that listens to broadcasts that simulate actions in the
 * system that are used for testing the latency.
 */
@SysUISingleton
public class LatencyTester extends SystemUI {

    private static final String
            ACTION_FINGERPRINT_WAKE =
            "com.android.systemui.latency.ACTION_FINGERPRINT_WAKE";
    private static final String
            ACTION_FACE_WAKE =
            "com.android.systemui.latency.ACTION_FACE_WAKE";
    private final BiometricUnlockController mBiometricUnlockController;
    private final BroadcastDispatcher mBroadcastDispatcher;

    @Inject
    public LatencyTester(Context context, BiometricUnlockController biometricUnlockController,
            BroadcastDispatcher broadcastDispatcher) {
        super(context);

        mBiometricUnlockController = biometricUnlockController;
        mBroadcastDispatcher = broadcastDispatcher;
    }

    @Override
    public void start() {
        if (!Build.IS_DEBUGGABLE) {
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_FINGERPRINT_WAKE);
        filter.addAction(ACTION_FACE_WAKE);
        mBroadcastDispatcher.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_FINGERPRINT_WAKE.equals(action)) {
                    fakeWakeAndUnlock(BiometricSourceType.FINGERPRINT);
                } else if (ACTION_FACE_WAKE.equals(action)) {
                    fakeWakeAndUnlock(BiometricSourceType.FACE);
                }
            }
        }, filter);
    }

    private void fakeWakeAndUnlock(BiometricSourceType type) {
        mBiometricUnlockController.onBiometricAcquired(type);
        mBiometricUnlockController.onBiometricAuthenticated(
                KeyguardUpdateMonitor.getCurrentUser(), type, true /* isStrongBiometric */);
    }
}
