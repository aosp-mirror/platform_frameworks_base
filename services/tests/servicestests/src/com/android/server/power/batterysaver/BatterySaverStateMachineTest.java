/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.power.batterysaver;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.provider.Settings.Global;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContext;

import com.google.common.base.Objects;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;

/**
 atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server/power/batterysaver/BatterySaverStateMachineTest.java
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatterySaverStateMachineTest {

    private MyMockContext mMockContext;
    private ContentResolver mMockContextResolver;
    private BatterySaverController mMockBatterySaverController;
    private Device mDevice;
    private TestableBatterySaverStateMachine mTarget;

    private class MyMockContext extends MockContext {
        @Override
        public ContentResolver getContentResolver() {
            return mMockContextResolver;
        }
    }

    private DevicePersistedState mPersistedState;

    private class DevicePersistedState {
        // Current battery level.
        public int batteryLevel = 100;

        // Whether battery level is currently low or not.
        public boolean batteryLow = false;

        // Whether the device is plugged in or not.
        public boolean powered = false;

        // Global settings.
        public final HashMap<String, Integer> global = new HashMap<>();
    }

    /**
     * This class simulates a device's volatile status that will be reset by {@link #initDevice()}.
     */
    private class Device {
        public boolean batterySaverEnabled = false;

        public int getLowPowerModeTriggerLevel() {
            return mPersistedState.global.getOrDefault(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);
        }

        public void setBatteryLevel(int level) {
            mPersistedState.batteryLevel = level;
            if (mPersistedState.batteryLevel <= Math.max(15, getLowPowerModeTriggerLevel())) {
                mPersistedState.batteryLow = true;
            } else if (mPersistedState.batteryLow
                    && (mPersistedState.batteryLevel >= (getLowPowerModeTriggerLevel() + 5))) {
                mPersistedState.batteryLow = false;
            }
            pushBatteryStatus();
        }

        public void setPowered(boolean newPowered) {
            mPersistedState.powered = newPowered;
            pushBatteryStatus();
        }

        public void pushBatteryStatus() {
            mTarget.setBatteryStatus(mPersistedState.powered, mPersistedState.batteryLevel,
                    mPersistedState.batteryLow);
        }

        public void pushGlobalSettings() {
            mTarget.setSettingsLocked(
                    mPersistedState.global.getOrDefault(Global.LOW_POWER_MODE, 0) != 0,
                    mPersistedState.global.getOrDefault(Global.LOW_POWER_MODE_STICKY, 0) != 0,
                    mDevice.getLowPowerModeTriggerLevel());
        }

        public void putGlobalSetting(String key, int value) {
            mPersistedState.global.put(key, value);
            pushGlobalSettings();
        }

        public int getGlobalSetting(String key, int defValue) {
            return mPersistedState.global.getOrDefault(key, defValue);
        }
    }

    /**
     * Test target class.
     */
    private class TestableBatterySaverStateMachine extends BatterySaverStateMachine {
        public TestableBatterySaverStateMachine() {
            super(new Object(), mMockContext, mMockBatterySaverController);
        }

        @Override
        protected void putGlobalSetting(String key, int value) {
            if (Objects.equal(mPersistedState.global.get(key), value)) {
                return;
            }
            mDevice.putGlobalSetting(key, value);
        }

        @Override
        protected int getGlobalSetting(String key, int defValue) {
            return mDevice.getGlobalSetting(key, defValue);
        }

        @Override
        void runOnBgThread(Runnable r) {
            r.run();
        }

        @Override
        void runOnBgThreadLazy(Runnable r, int delayMillis) {
            r.run();
        }
    }

    @Before
    public void setUp() {
        mMockContext = new MyMockContext();
        mMockContextResolver = mock(ContentResolver.class);
        mMockBatterySaverController = mock(BatterySaverController.class);

        doAnswer((inv) -> mDevice.batterySaverEnabled = inv.getArgument(0))
                .when(mMockBatterySaverController).enableBatterySaver(anyBoolean(), anyInt());
        when(mMockBatterySaverController.isEnabled())
                .thenAnswer((inv) -> mDevice.batterySaverEnabled);

        mPersistedState = new DevicePersistedState();
        initDevice();
    }

    private void initDevice() {
        mDevice = new Device();

        mTarget = new TestableBatterySaverStateMachine();

        mDevice.pushBatteryStatus();
        mDevice.pushGlobalSettings();
        mTarget.onBootCompleted();
    }

    @Test
    public void testNoAutoBatterySaver() {
        assertEquals(0, mDevice.getLowPowerModeTriggerLevel());

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(100, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(90);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(50);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(50, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(16);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(16, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // When LOW_POWER_MODE_TRIGGER_LEVEL is 0, 15% will still trigger low-battery, but
        // BS wont be enabled.
        mDevice.setBatteryLevel(15);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(15, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(10);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(10, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        // Manually enable BS.
        mTarget.setBatterySaverEnabledManually(true);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(10, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(50);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(50, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Start charging. It'll disable BS.
        mDevice.setPowered(true);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(50, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(60);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(60, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Unplug.
        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(60, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(10);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(10, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(80);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(80, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Reboot the device.
        initDevice();

        assertEquals(true, mDevice.batterySaverEnabled); // Sticky.
        assertEquals(80, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(30);
        initDevice();

        assertEquals(true, mDevice.batterySaverEnabled); // Still sticky.
        assertEquals(30, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mTarget.setBatterySaverEnabledManually(false);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        initDevice(); // reboot.

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);
    }

    @Test
    public void testAutoBatterySaver() {
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 50);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(100, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(90);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(51);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(51, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Hit the threshold. BS should be enabled.
        mDevice.setBatteryLevel(50);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(50, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        // Battery goes up, but until it hits 55%, we still keep BS on.
        mDevice.setBatteryLevel(54);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(54, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        // 50% + 5%, now BS will be off.
        mDevice.setBatteryLevel(55);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(55, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(40);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(40, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setPowered(true);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(40, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(40, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mTarget.setBatterySaverEnabledManually(false); // Manually disable -> snooze.

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(40, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(30);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        // Plug in and out, snooze will reset.
        mDevice.setPowered(true);
        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setPowered(true);
        mDevice.setBatteryLevel(60);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(60, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setPowered(false);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(60, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(50);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(50, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(70);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(70, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Bump ump the threshold.
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 70);
        mDevice.setBatteryLevel(mPersistedState.batteryLevel);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(70, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        // Then down.
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 60);
        mDevice.setBatteryLevel(mPersistedState.batteryLevel);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(70, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Reboot in low state -> automatically enable BS.
        mDevice.setPowered(false);
        mDevice.setBatteryLevel(30);
        mTarget.setBatterySaverEnabledManually(false);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        initDevice();

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);
    }

    @Test
    public void testAutoBatterySaver_withSticky() {
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 50);

        mTarget.setBatterySaverEnabledManually(true);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(100, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(30);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(80);

        assertEquals(true, mDevice.batterySaverEnabled); // Still enabled.
        assertEquals(80, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setPowered(true);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(80, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(30);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled); // Restores BS.
        assertEquals(30, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setPowered(true);
        mDevice.setBatteryLevel(90);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        initDevice();

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mTarget.setBatterySaverEnabledManually(false);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        initDevice();

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);
    }

    @Test
    public void testNoAutoBatterySaver_fromAdb() {

        assertEquals(0, mDevice.getLowPowerModeTriggerLevel());

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(100, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(90);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Enable
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE, 1);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Disable
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE, 0);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Enable again
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE, 1);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Reboot -- setting BS from adb is also sticky.
        initDevice();

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);
    }
}
