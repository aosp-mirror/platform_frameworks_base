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

package com.android.internal.os;

import static org.junit.Assert.assertTrue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.ConditionVariable;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.SystemUtil;

import com.google.android.collect.Sets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class BatteryInputSuspendTest {

    public static final Set<String> SUPPORTED_DEVICES = Sets.newArraySet(
            "blueline",
            "crosshatch",
            "coral"
    );

    private ChargerStateMonitor mChargerStateMonitor;

    @Before
    public void verifyCharging() {
        if (!SUPPORTED_DEVICES.contains(Build.DEVICE)) {
            return;
        }

        mChargerStateMonitor = new ChargerStateMonitor();

        assertTrue("Device must be connected to USB", mChargerStateMonitor.isCharging());
    }

    @Test
    public void testSuspendInput() {
        if (!SUPPORTED_DEVICES.contains(Build.DEVICE)) {
            return;
        }

        SystemUtil.runShellCommand("dumpsys battery suspend_input");

        mChargerStateMonitor.waitForChargerState(/* isPluggedIn */false);
    }

    @After
    public void reenableCharging() {
        if (!SUPPORTED_DEVICES.contains(Build.DEVICE)) {
            return;
        }

        mChargerStateMonitor.reset();

        SystemUtil.runShellCommand("dumpsys battery reset");

        mChargerStateMonitor.waitForChargerState(/* isPluggedIn */true);
    }

    private static class ChargerStateMonitor {
        private final Intent mBatteryMonitor;
        private final ConditionVariable mReady = new ConditionVariable();
        private boolean mExpectedChargingState;

        ChargerStateMonitor() {
            Context context = InstrumentationRegistry.getInstrumentation().getContext();
            mBatteryMonitor = context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (isCharging(intent) == mExpectedChargingState) {
                        mReady.open();
                    }
                    context.unregisterReceiver(this);
                }
            }, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }

        public boolean isCharging() {
            return isCharging(mBatteryMonitor);
        }

        private boolean isCharging(Intent batteryMonitor) {
            return batteryMonitor.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0;
        }

        void waitForChargerState(boolean isPluggedIn) {
            mExpectedChargingState = isPluggedIn;

            boolean charging = isCharging();
            if (charging == mExpectedChargingState) {
                return;
            }

            boolean success = mReady.block(100000);
            assertTrue("Timed out waiting for charging state to change", success);
        }

        void reset() {
            mReady.close();
        }
    }
}
