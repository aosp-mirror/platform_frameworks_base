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
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Build;
import android.provider.DeviceConfig;

import androidx.annotation.NonNull;

import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Class that only runs on debuggable builds with the LatencyTracker setting enabled
 * that listens to broadcasts that simulate actions in the
 * system that are used for testing the latency.
 */
@SysUISingleton
public class LatencyTester implements CoreStartable {
    private static final boolean DEFAULT_ENABLED = Build.IS_ENG;
    private static final String
            ACTION_FINGERPRINT_WAKE =
            "com.android.systemui.latency.ACTION_FINGERPRINT_WAKE";
    private static final String
            ACTION_FACE_WAKE =
            "com.android.systemui.latency.ACTION_FACE_WAKE";
    private final BiometricUnlockController mBiometricUnlockController;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final DeviceConfigProxy mDeviceConfigProxy;

    private boolean mEnabled;

    @Inject
    public LatencyTester(
            BiometricUnlockController biometricUnlockController,
            BroadcastDispatcher broadcastDispatcher,
            DeviceConfigProxy deviceConfigProxy,
            @Main DelayableExecutor mainExecutor
    ) {
        mBiometricUnlockController = biometricUnlockController;
        mBroadcastDispatcher = broadcastDispatcher;
        mDeviceConfigProxy = deviceConfigProxy;

        updateEnabled();
        mDeviceConfigProxy.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_LATENCY_TRACKER,
                mainExecutor, properties -> updateEnabled());
    }

    @Override
    public void start() {
        registerForBroadcasts(mEnabled);
    }

    private void fakeWakeAndUnlock(BiometricSourceType type) {
        if (!mEnabled) {
            return;
        }
        mBiometricUnlockController.onBiometricAcquired(type,
                BiometricConstants.BIOMETRIC_ACQUIRED_GOOD);
        mBiometricUnlockController.onBiometricAuthenticated(
                KeyguardUpdateMonitor.getCurrentUser(), type, true /* isStrongBiometric */);
    }

    private void registerForBroadcasts(boolean register) {
        if (register) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_FINGERPRINT_WAKE);
            filter.addAction(ACTION_FACE_WAKE);
            mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, filter);
        } else {
            mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);
        }
    }

    private void updateEnabled() {
        boolean wasEnabled = mEnabled;
        mEnabled = Build.IS_DEBUGGABLE
                && mDeviceConfigProxy.getBoolean(DeviceConfig.NAMESPACE_LATENCY_TRACKER,
                LatencyTracker.SETTINGS_ENABLED_KEY, DEFAULT_ENABLED);
        if (mEnabled != wasEnabled) {
            registerForBroadcasts(mEnabled);
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("mEnabled=" + mEnabled);
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_FINGERPRINT_WAKE.equals(action)) {
                fakeWakeAndUnlock(BiometricSourceType.FINGERPRINT);
            } else if (ACTION_FACE_WAKE.equals(action)) {
                fakeWakeAndUnlock(BiometricSourceType.FACE);
            }
        }
    };
}
