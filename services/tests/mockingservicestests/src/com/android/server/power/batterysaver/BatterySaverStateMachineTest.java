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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.os.PowerManager;
import android.provider.Settings.Global;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Objects;

/**
 * atest com.android.server.power.batterysaver.BatterySaverStateMachineTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatterySaverStateMachineTest {

    private Context mMockContext;
    private ContentResolver mMockContextResolver;
    private BatterySaverController mMockBatterySaverController;
    private NotificationManager mMockNotificationManager;
    private Device mDevice;
    private TestableBatterySaverStateMachine mTarget;
    private Resources mMockResources;

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
                    mDevice.getLowPowerModeTriggerLevel(),
                    mPersistedState.global.getOrDefault(
                            Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_ENABLED, 0) != 0,
                    mPersistedState.global.getOrDefault(
                            Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_LEVEL, 90),
                    mPersistedState.global.getOrDefault(Global.AUTOMATIC_POWER_SAVE_MODE, 0),
                    mPersistedState.global.getOrDefault(
                            Global.DYNAMIC_POWER_SAVINGS_ENABLED, 0) != 0,
                    mPersistedState.global.getOrDefault(
                            Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD, 100));
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
        TestableBatterySaverStateMachine() {
            super(new Object(), mMockContext, mMockBatterySaverController);
        }

        @Override
        protected void putGlobalSetting(String key, int value) {
            if (Objects.equals(mPersistedState.global.get(key), value)) {
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

        @Override
        void triggerDynamicModeNotification() {
            // Do nothing
        }
    }

    @Before
    public void setUp() {
        mMockContext = mock(Context.class);
        mMockContextResolver = mock(ContentResolver.class);
        mMockBatterySaverController = mock(BatterySaverController.class);
        mMockNotificationManager = mock(NotificationManager.class);
        mMockResources = mock(Resources.class);

        doReturn(mMockContextResolver).when(mMockContext).getContentResolver();
        doReturn(mMockResources).when(mMockContext).getResources();
        doReturn(mMockNotificationManager).when(mMockContext)
                .getSystemService(NotificationManager.class);
        doAnswer((inv) -> mDevice.batterySaverEnabled = inv.getArgument(0))
                .when(mMockBatterySaverController).enableBatterySaver(anyBoolean(), anyInt());
        doReturn(true).when(mMockBatterySaverController)
                .setAdaptivePolicyLocked(any(BatterySaverPolicy.Policy.class), anyInt());
        when(mMockBatterySaverController.isEnabled())
                .thenAnswer((inv) -> mDevice.batterySaverEnabled);
        when(mMockBatterySaverController.isFullEnabled())
                .thenAnswer((inv) -> mDevice.batterySaverEnabled);
        when(mMockResources.getBoolean(
                com.android.internal.R.bool.config_batterySaverStickyBehaviourDisabled))
                .thenReturn(false);
        when(mMockResources.getInteger(
                com.android.internal.R.integer.config_dynamicPowerSavingsDefaultDisableThreshold))
                .thenReturn(80);

        mPersistedState = new DevicePersistedState();
        initDevice();
    }

    private void initDevice() {
        mDevice = new Device();

        mTarget = new TestableBatterySaverStateMachine();

        mDevice.pushBatteryStatus();
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
        mDevice.putGlobalSetting(Global.AUTOMATIC_POWER_SAVE_MODE, 0);

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

        // Disable auto battery saver.
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);
        mDevice.setBatteryLevel(25);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(25, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // PowerManager sets batteryLow to true at 15% if battery saver trigger level is lower.
        mDevice.setBatteryLevel(15);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(15, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);
    }

    @Test
    public void testAutoBatterySaver_withSticky_withAutoOffDisabled() {
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 50);
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_ENABLED, 0);

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
    public void testAutoBatterySaver_withSticky_withAutoOffEnabled() {
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 50);
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_ENABLED, 1);
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_LEVEL, 90);

        // Scenario 1: User turns BS on manually above the threshold, it shouldn't turn off even
        // with battery level change above threshold.
        mDevice.setBatteryLevel(100);
        mTarget.setBatterySaverEnabledManually(true);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(100, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(97);

        assertEquals(true, mDevice.batterySaverEnabled); // Stays on.
        assertEquals(97, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(95);

        assertEquals(true, mDevice.batterySaverEnabled); // Stays on.
        assertEquals(95, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Scenario 2: User turns BS on manually above the threshold then charges device. BS
        // shouldn't turn back on.
        mDevice.setPowered(true);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(95, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(97);
        mDevice.setPowered(false);

        assertEquals(false, mDevice.batterySaverEnabled); // Sticky BS no longer enabled.
        assertEquals(97, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Scenario 3: User turns BS on manually above the threshold. Device drains below
        // threshold and then charged to below threshold. Sticky BS should activate.
        mTarget.setBatterySaverEnabledManually(true);
        mDevice.setBatteryLevel(30);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setPowered(true);
        mDevice.setBatteryLevel(80);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(80, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled); // Still enabled.
        assertEquals(80, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(30);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        // Scenario 4: User turns BS on manually above the threshold. Device drains below
        // threshold and is eventually charged to above threshold. Sticky BS should turn off.
        mDevice.setPowered(true);
        mDevice.setBatteryLevel(90);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setPowered(false);

        assertEquals(false, mDevice.batterySaverEnabled); // Sticky BS no longer enabled.
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Scenario 5: User turns BS on manually below threshold and charges to below threshold.
        // Sticky BS should activate.
        mDevice.setBatteryLevel(70);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(70, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mTarget.setBatterySaverEnabledManually(true);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(70, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setPowered(true);
        mDevice.setBatteryLevel(80);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(80, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled); // Sticky BS still on.
        assertEquals(80, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Scenario 6: User turns BS on manually below threshold and eventually charges to above
        // threshold. Sticky BS should turn off.

        mDevice.setPowered(true);
        mDevice.setBatteryLevel(95);
        mDevice.setPowered(false);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(95, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Scenario 7: User turns BS on above threshold and then reboots device. Sticky BS
        // shouldn't activate.
        mTarget.setBatterySaverEnabledManually(true);
        mPersistedState.batteryLevel = 93;

        initDevice();

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(93, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Scenario 8: User turns BS on below threshold and then reboots device without charging.
        // Sticky BS should activate.
        mDevice.setBatteryLevel(75);
        mTarget.setBatterySaverEnabledManually(true);
        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(75, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        initDevice();

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(75, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Scenario 9: User turns BS on below threshold and then reboots device after charging
        // above threshold. Sticky BS shouldn't activate.
        mDevice.setBatteryLevel(80);
        mTarget.setBatterySaverEnabledManually(true);
        mPersistedState.batteryLevel = 100;

        initDevice();

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(100, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Scenario 10: Somehow autoDisableLevel is set to a value below lowPowerModeTriggerLevel
        // and then user enables manually above both thresholds, discharges below
        // autoDisableLevel and then charges up to between autoDisableLevel and
        // lowPowerModeTriggerLevel. Sticky BS shouldn't activate, but BS should still be on
        // because the level is below lowPowerModeTriggerLevel.
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 75);
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_ENABLED, 1);
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_LEVEL, 60);
        initDevice();

        mDevice.setBatteryLevel(90);
        mTarget.setBatterySaverEnabledManually(true);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(50);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(50, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setPowered(true);
        mDevice.setBatteryLevel(65);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(65, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(65, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);
    }

    @Test
    public void testAutoBatterySaver_withSticky_withAutoOffToggled() {
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 50);
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_ENABLED, 1);
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_LEVEL, 90);

        // Scenario 1: User turns BS on manually above the threshold, it shouldn't turn off even
        // with battery level change above threshold.
        mDevice.setBatteryLevel(100);
        mTarget.setBatterySaverEnabledManually(true);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(100, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(95);

        assertEquals(true, mDevice.batterySaverEnabled); // Stays on.
        assertEquals(95, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Disable auto disable while in the pending sticky state. BS should reactivate after
        // unplug.
        mDevice.setPowered(true);
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_ENABLED, 0);
        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled); // Sticky BS should activate.
        assertEquals(95, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        // Enable auto disable while in the pending sticky state. Sticky should turn off after
        // unplug.
        mDevice.setPowered(true);
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_ENABLED, 1);
        mDevice.setPowered(false);

        assertEquals(false, mDevice.batterySaverEnabled); // Sticky BS no longer enabled.
        assertEquals(95, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);
    }

    @Test
    public void testAutoBatterySaver_withStickyDisabled() {
        when(mMockResources.getBoolean(
                com.android.internal.R.bool.config_batterySaverStickyBehaviourDisabled))
                .thenReturn(true);
        initDevice();
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 50);
        mDevice.putGlobalSetting(Global.AUTOMATIC_POWER_SAVE_MODE, 0);

        mTarget.setBatterySaverEnabledManually(true);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(100, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);

        mDevice.setBatteryLevel(30);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);
        assertEquals(true, mPersistedState.batteryLow);

        mDevice.setPowered(true);
        mDevice.setBatteryLevel(80);
        mDevice.setPowered(false);

        assertEquals(false, mDevice.batterySaverEnabled); // Not sticky.
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

        assertEquals(false, mDevice.batterySaverEnabled);
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

        // Reboot -- LOW_POWER_MODE shouldn't be persisted.
        initDevice();
        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);
        assertEquals(false, mPersistedState.batteryLow);
    }

    @Test
    public void testAutoBatterySaver_smartBatterySaverEnabled() {
        mDevice.putGlobalSetting(Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD, 50);
        mDevice.putGlobalSetting(Global.AUTOMATIC_POWER_SAVE_MODE,
                PowerManager.POWER_SAVE_MODE_TRIGGER_DYNAMIC);
        mDevice.putGlobalSetting(Global.DYNAMIC_POWER_SAVINGS_ENABLED, 0);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(100, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(90);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(51);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(51, mPersistedState.batteryLevel);

        // Hit the threshold. BS should be disabled since dynamic power savings still off
        mDevice.setBatteryLevel(50);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(50, mPersistedState.batteryLevel);

        // dynamic power savings comes on, battery saver should turn on
        mDevice.putGlobalSetting(Global.DYNAMIC_POWER_SAVINGS_ENABLED, 1);
        mDevice.setBatteryLevel(40);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(40, mPersistedState.batteryLevel);

        mDevice.setPowered(true);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(40, mPersistedState.batteryLevel);

        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(40, mPersistedState.batteryLevel);

        mTarget.setBatterySaverEnabledManually(false); // Manually disable -> snooze.

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(40, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(30);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);

        // Plug in and out, snooze will reset.
        mDevice.setPowered(true);
        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);

        mDevice.setPowered(true);
        mDevice.setBatteryLevel(60);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(60, mPersistedState.batteryLevel);

        mDevice.setPowered(false);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(60, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(40);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(40, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(70);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(70, mPersistedState.batteryLevel);

        // Bump up the threshold.
        mDevice.putGlobalSetting(Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD, 71);
        mDevice.setBatteryLevel(mPersistedState.batteryLevel);

        // Changes should register immediately.
        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(70, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(69);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(69, mPersistedState.batteryLevel);

        // Then down.
        mDevice.putGlobalSetting(Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD, 60);
        mDevice.setBatteryLevel(mPersistedState.batteryLevel);

        // Changes should register immediately.
        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(69, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(68);
        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(68, mPersistedState.batteryLevel);

        // Reboot in low state -> automatically enable BS.
        mDevice.setPowered(false);
        mDevice.setBatteryLevel(30);
        mTarget.setBatterySaverEnabledManually(false);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);

        initDevice();

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);
    }

    @Test
    public void testAutoBatterySaver_snoozed_autoEnabled() {
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 30);
        // Test dynamic threshold higher than automatic to make sure it doesn't interfere when it's
        // not enabled.
        mDevice.putGlobalSetting(Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD, 50);
        mDevice.putGlobalSetting(Global.AUTOMATIC_POWER_SAVE_MODE,
                PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);
        mDevice.putGlobalSetting(Global.DYNAMIC_POWER_SAVINGS_ENABLED, 0);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(100, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(90);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(51);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(51, mPersistedState.batteryLevel);

        // Hit dynamic threshold. BS should be disabled since dynamic is off
        mDevice.setBatteryLevel(50);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(50, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(30);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);

        mDevice.setPowered(true);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);

        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);

        mTarget.setBatterySaverEnabledManually(false); // Manually disable -> snooze.

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(20);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(20, mPersistedState.batteryLevel);

        // Lower threshold. Level is still below, so should still be snoozed.
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 25);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(20, mPersistedState.batteryLevel);

        // Lower threshold even more. Battery no longer considered "low" so snoozing should be
        // disabled.
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 10);
        // "batteryLow" is set in setBatteryLevel.
        mDevice.setBatteryLevel(19);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(19, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(10);

        assertEquals(true, mDevice.batterySaverEnabled); // No longer snoozing.
        assertEquals(10, mPersistedState.batteryLevel);

        mTarget.setBatterySaverEnabledManually(false); // Manually disable -> snooze.

        // Plug in and out, snooze will reset.
        mDevice.setPowered(true);
        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(10, mPersistedState.batteryLevel);

        mDevice.setPowered(true);
        mDevice.setBatteryLevel(60);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(60, mPersistedState.batteryLevel);

        // Test toggling resets snooze.
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 50);
        mDevice.setPowered(false);
        mDevice.setBatteryLevel(45);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(45, mPersistedState.batteryLevel);

        mTarget.setBatterySaverEnabledManually(false); // Manually disable -> snooze.
        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(45, mPersistedState.batteryLevel);

        // Disable and re-enable.
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 50);

        assertEquals(true, mDevice.batterySaverEnabled); // Snooze reset
        assertEquals(45, mPersistedState.batteryLevel);
    }

    @Test
    public void testAutoBatterySaver_snoozed_dynamicEnabled() {
        // Test auto threshold higher than dynamic to make sure it doesn't interfere when it's
        // not enabled.
        mDevice.putGlobalSetting(Global.LOW_POWER_MODE_TRIGGER_LEVEL, 50);
        mDevice.putGlobalSetting(Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD, 30);
        mDevice.putGlobalSetting(Global.AUTOMATIC_POWER_SAVE_MODE,
                PowerManager.POWER_SAVE_MODE_TRIGGER_DYNAMIC);
        mDevice.putGlobalSetting(Global.DYNAMIC_POWER_SAVINGS_ENABLED, 1);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(100, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(90);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(90, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(51);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(51, mPersistedState.batteryLevel);

        // Hit automatic threshold. BS should be disabled since automatic is off
        mDevice.setBatteryLevel(50);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(50, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(30);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);

        mDevice.setPowered(true);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);

        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);

        mTarget.setBatterySaverEnabledManually(false); // Manually disable -> snooze.

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(30, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(20);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(20, mPersistedState.batteryLevel);

        // Lower threshold. Level is still below, so should still be snoozed.
        mDevice.putGlobalSetting(Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD, 25);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(20, mPersistedState.batteryLevel);

        // Lower threshold even more. Battery no longer considered "low" so snoozing should be
        // disabled.
        mDevice.putGlobalSetting(Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD, 10);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(20, mPersistedState.batteryLevel);

        mDevice.setBatteryLevel(10);

        assertEquals(true, mDevice.batterySaverEnabled); // No longer snoozing.
        assertEquals(10, mPersistedState.batteryLevel);

        mTarget.setBatterySaverEnabledManually(false); // Manually disable -> snooze.

        // Plug in and out, snooze will reset.
        mDevice.setPowered(true);
        mDevice.setPowered(false);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(10, mPersistedState.batteryLevel);

        mDevice.setPowered(true);
        mDevice.setBatteryLevel(60);

        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(60, mPersistedState.batteryLevel);

        // Test toggling resets snooze.
        mDevice.putGlobalSetting(Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD, 50);
        mDevice.setPowered(false);
        mDevice.setBatteryLevel(45);

        assertEquals(true, mDevice.batterySaverEnabled);
        assertEquals(45, mPersistedState.batteryLevel);

        mTarget.setBatterySaverEnabledManually(false); // Manually disable -> snooze.
        assertEquals(false, mDevice.batterySaverEnabled);
        assertEquals(45, mPersistedState.batteryLevel);

        // Disable and re-enable.
        mDevice.putGlobalSetting(Global.DYNAMIC_POWER_SAVINGS_ENABLED, 0);
        mDevice.putGlobalSetting(Global.DYNAMIC_POWER_SAVINGS_ENABLED, 1);

        assertEquals(true, mDevice.batterySaverEnabled); // Snooze reset
        assertEquals(45, mPersistedState.batteryLevel);
    }
}
