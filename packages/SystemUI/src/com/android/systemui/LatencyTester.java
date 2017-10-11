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
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.LatencyTracker;
import com.android.systemui.statusbar.phone.FingerprintUnlockController;
import com.android.systemui.statusbar.phone.StatusBar;

/**
 * Class that only runs on debuggable builds that listens to broadcasts that simulate actions in the
 * system that are used for testing the latency.
 */
public class LatencyTester extends SystemUI {

    private static final String ACTION_FINGERPRINT_WAKE =
            "com.android.systemui.latency.ACTION_FINGERPRINT_WAKE";
    private static final String ACTION_TURN_ON_SCREEN =
            "com.android.systemui.latency.ACTION_TURN_ON_SCREEN";

    @Override
    public void start() {
        if (!Build.IS_DEBUGGABLE) {
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_FINGERPRINT_WAKE);
        filter.addAction(ACTION_TURN_ON_SCREEN);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_FINGERPRINT_WAKE.equals(action)) {
                    fakeWakeAndUnlock();
                } else if (ACTION_TURN_ON_SCREEN.equals(action)) {
                    fakeTurnOnScreen();
                }
            }
        }, filter);
    }

    private void fakeTurnOnScreen() {
        PowerManager powerManager = mContext.getSystemService(PowerManager.class);
        if (LatencyTracker.isEnabled(mContext)) {
            LatencyTracker.getInstance(mContext).onActionStart(
                    LatencyTracker.ACTION_TURN_ON_SCREEN);
        }
        powerManager.wakeUp(SystemClock.uptimeMillis(), "android.policy:LATENCY_TESTS");
    }

    private void fakeWakeAndUnlock() {
        FingerprintUnlockController fingerprintUnlockController = getComponent(StatusBar.class)
                .getFingerprintUnlockController();
        fingerprintUnlockController.onFingerprintAcquired();
        fingerprintUnlockController.onFingerprintAuthenticated(
                KeyguardUpdateMonitor.getCurrentUser());
    }
}
