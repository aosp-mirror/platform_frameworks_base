/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.server.power.batterysaver.BatterySaverPolicy.POLICY_LEVEL_ADAPTIVE;
import static com.android.server.power.batterysaver.BatterySaverPolicy.POLICY_LEVEL_FULL;
import static com.android.server.power.batterysaver.BatterySaverPolicy.POLICY_LEVEL_OFF;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerSaveState;
import android.provider.Settings.Global;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.ArrayMap;

import com.android.frameworks.servicestests.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.power.batterysaver.BatterySaverPolicy.Policy;

import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link com.android.server.power.batterysaver.BatterySaverPolicy}
 */
public class BatterySaverPolicyTest extends AndroidTestCase {
    private static final int MAX_SERVICE_TYPE = 16;
    private static final float BRIGHTNESS_FACTOR = 0.7f;
    private static final float DEFAULT_BRIGHTNESS_FACTOR = 0.5f;
    private static final float PRECISION = 0.001f;
    private static final int GPS_MODE = 0; // LOCATION_MODE_NO_CHANGE
    private static final int DEFAULT_GPS_MODE =
            PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF;
    private static final String BATTERY_SAVER_CONSTANTS = "vibration_disabled=true,"
            + "advertise_is_enabled=true,"
            + "animation_disabled=false,"
            + "soundtrigger_disabled=true,"
            + "firewall_disabled=false,"
            + "datasaver_disabled=false,"
            + "adjust_brightness_disabled=true,"
            + "adjust_brightness_factor=0.7,"
            + "fullbackup_deferred=true,"
            + "keyvaluebackup_deferred=false,"
            + "gps_mode=0," // LOCATION_MODE_NO_CHANGE
            + "enable_night_mode=false,"
            + "quick_doze_enabled=true";
    private static final String BATTERY_SAVER_INCORRECT_CONSTANTS = "vi*,!=,,true";

    private class BatterySaverPolicyForTest extends BatterySaverPolicy {
        BatterySaverPolicyForTest(Object lock, Context context,
                BatterySavingStats batterySavingStats) {
            super(lock, context, batterySavingStats);
        }

        @Override
        String getGlobalSetting(String key) {
            return mMockGlobalSettings.get(key);
        }

        @Override
        int getDeviceSpecificConfigResId() {
            return mDeviceSpecificConfigResId;
        }

        @Override
        void invalidatePowerSaveModeCaches() {
            // Avoids an SELinux denial.
        }

        @VisibleForTesting
        void onChange() {
            onChange(true, null);
        }
    }

    private BatterySaverPolicyForTest mBatterySaverPolicy;

    private final ArrayMap<String, String> mMockGlobalSettings = new ArrayMap<>();
    private int mDeviceSpecificConfigResId = R.string.config_batterySaverDeviceSpecificConfig_1;

    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        final Object lock = new Object();
        mBatterySaverPolicy = new BatterySaverPolicyForTest(lock, getContext(),
                new BatterySavingStats(lock));
        mBatterySaverPolicy.systemReady();

        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_FULL);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyNull_DefaultValueCorrect() {
        testServiceDefaultValue_On(ServiceType.NULL);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyVibration_DefaultValueCorrect() {
        testServiceDefaultValue_On(ServiceType.VIBRATION);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyVibration_WithAccessibilityEnabled() {
        mBatterySaverPolicy.setAccessibilityEnabled(true);
        testServiceDefaultValue_Off(ServiceType.VIBRATION);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicySound_DefaultValueCorrect() {
        testServiceDefaultValue_On(ServiceType.SOUND);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyFullBackup_DefaultValueCorrect() {
        testServiceDefaultValue_On(ServiceType.FULL_BACKUP);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyKeyValueBackup_DefaultValueCorrect() {
        testServiceDefaultValue_On(ServiceType.KEYVALUE_BACKUP);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyAnimation_DefaultValueCorrect() {
        testServiceDefaultValue_Off(ServiceType.ANIMATION);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyBatteryStats_DefaultValueCorrect() {
        testServiceDefaultValue_On(ServiceType.BATTERY_STATS);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyNetworkFirewall_DefaultValueCorrect() {
        testServiceDefaultValue_On(ServiceType.NETWORK_FIREWALL);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyNightMode_DefaultValueCorrect() {
        testServiceDefaultValue_On(ServiceType.NIGHT_MODE);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyDataSaver_DefaultValueCorrect() {
        mBatterySaverPolicy.updateConstantsLocked("", "");
        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_FULL);
        final PowerSaveState batterySaverStateOn =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.DATA_SAVER);
        assertThat(batterySaverStateOn.batterySaverEnabled).isFalse();

        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_OFF);
        final PowerSaveState batterySaverStateOff = mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.DATA_SAVER);
        assertThat(batterySaverStateOff.batterySaverEnabled).isFalse();
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyScreenBrightness_DefaultValueCorrect() {
        testServiceDefaultValue_Off(ServiceType.SCREEN_BRIGHTNESS);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyGps_DefaultValueCorrect() {
        testServiceDefaultValue_On(ServiceType.LOCATION);

        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_FULL);
        PowerSaveState stateOn =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION);
        assertThat(stateOn.locationMode).isEqualTo(DEFAULT_GPS_MODE);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyQuickDoze_DefaultValueCorrect() {
        testServiceDefaultValue_On(ServiceType.QUICK_DOZE);
    }

    @SmallTest
    public void testUpdateConstants_getCorrectData() {
        mBatterySaverPolicy.updateConstantsLocked(BATTERY_SAVER_CONSTANTS, "");

        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_FULL);
        verifyBatterySaverConstantsUpdated();
    }

    private void verifyBatterySaverConstantsUpdated() {
        final PowerSaveState vibrationState =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.VIBRATION);
        assertThat(vibrationState.batterySaverEnabled).isTrue();

        final PowerSaveState animationState =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.ANIMATION);
        assertThat(animationState.batterySaverEnabled).isFalse();

        final PowerSaveState soundState =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.SOUND);
        assertThat(soundState.batterySaverEnabled).isTrue();

        final PowerSaveState networkState = mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NETWORK_FIREWALL);
        assertThat(networkState.batterySaverEnabled).isTrue();

        final PowerSaveState screenState =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.SCREEN_BRIGHTNESS);
        assertThat(screenState.batterySaverEnabled).isFalse();
        assertThat(screenState.brightnessFactor).isWithin(PRECISION).of(BRIGHTNESS_FACTOR);

        final PowerSaveState fullBackupState = mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.FULL_BACKUP);
        assertThat(fullBackupState.batterySaverEnabled).isTrue();

        final PowerSaveState keyValueBackupState = mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.KEYVALUE_BACKUP);
        assertThat(keyValueBackupState.batterySaverEnabled).isFalse();

        final PowerSaveState dataSaverState = mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.DATA_SAVER);
        assertThat(dataSaverState.batterySaverEnabled).isTrue();

        final PowerSaveState gpsState =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION);
        assertThat(gpsState.batterySaverEnabled).isTrue();
        assertThat(gpsState.locationMode).isEqualTo(GPS_MODE);

        final PowerSaveState quickDozeState = mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.QUICK_DOZE);
        assertThat(quickDozeState.batterySaverEnabled).isTrue();

        final PowerSaveState nightModeState = mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NIGHT_MODE);
        assertThat(nightModeState.batterySaverEnabled).isFalse();
    }

    @SmallTest
    public void testUpdateConstants_IncorrectData_NotCrash() {
        //Should not crash
        mBatterySaverPolicy.updateConstantsLocked(BATTERY_SAVER_INCORRECT_CONSTANTS, "");
        mBatterySaverPolicy.updateConstantsLocked(null, "");
    }

    private void testServiceDefaultValue_On(@ServiceType int type) {
        mBatterySaverPolicy.updateConstantsLocked("", "");
        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_FULL);
        final PowerSaveState batterySaverStateOn =
                mBatterySaverPolicy.getBatterySaverPolicy(type);
        assertThat(batterySaverStateOn.batterySaverEnabled).isTrue();

        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_OFF);
        final PowerSaveState batterySaverStateOff =
                mBatterySaverPolicy.getBatterySaverPolicy(type);
        assertThat(batterySaverStateOff.batterySaverEnabled).isFalse();
    }

    private void testServiceDefaultValue_Off(@ServiceType int type) {
        mBatterySaverPolicy.updateConstantsLocked("", "");
        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_FULL);
        final PowerSaveState batterySaverStateOn =
                mBatterySaverPolicy.getBatterySaverPolicy(type);
        assertThat(batterySaverStateOn.batterySaverEnabled).isFalse();

        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_OFF);
        final PowerSaveState batterySaverStateOff =
                mBatterySaverPolicy.getBatterySaverPolicy(type);
        assertThat(batterySaverStateOff.batterySaverEnabled).isFalse();
    }

    public void testDeviceSpecific() {
        mDeviceSpecificConfigResId = R.string.config_batterySaverDeviceSpecificConfig_1;
        mMockGlobalSettings.put(Global.BATTERY_SAVER_CONSTANTS, "");
        mMockGlobalSettings.put(Global.BATTERY_SAVER_DEVICE_SPECIFIC_CONSTANTS, "");

        mBatterySaverPolicy.onChange();
        assertThat(mBatterySaverPolicy.getFileValues(true).toString()).isEqualTo("{}");
        assertThat(mBatterySaverPolicy.getFileValues(false).toString()).isEqualTo("{}");


        mDeviceSpecificConfigResId = R.string.config_batterySaverDeviceSpecificConfig_2;

        mBatterySaverPolicy.onChange();
        assertThat(mBatterySaverPolicy.getFileValues(true).toString()).isEqualTo("{}");
        assertThat(mBatterySaverPolicy.getFileValues(false).toString())
                .isEqualTo("{/sys/devices/system/cpu/cpu1/cpufreq/scaling_max_freq=123, "
                        + "/sys/devices/system/cpu/cpu2/cpufreq/scaling_max_freq=456}");

        mDeviceSpecificConfigResId = R.string.config_batterySaverDeviceSpecificConfig_3;

        mBatterySaverPolicy.onChange();
        assertThat(mBatterySaverPolicy.getFileValues(true).toString())
                .isEqualTo("{/sys/devices/system/cpu/cpu3/cpufreq/scaling_max_freq=333, "
                        + "/sys/devices/system/cpu/cpu4/cpufreq/scaling_max_freq=444}");
        assertThat(mBatterySaverPolicy.getFileValues(false).toString())
                .isEqualTo("{/sys/devices/system/cpu/cpu2/cpufreq/scaling_max_freq=222}");


        mMockGlobalSettings.put(Global.BATTERY_SAVER_DEVICE_SPECIFIC_CONSTANTS,
                "cpufreq-i=3:1234567890/4:014/5:015");

        mBatterySaverPolicy.onChange();
        assertThat(mBatterySaverPolicy.getFileValues(true).toString())
                .isEqualTo("{/sys/devices/system/cpu/cpu3/cpufreq/scaling_max_freq=1234567890, "
                        + "/sys/devices/system/cpu/cpu4/cpufreq/scaling_max_freq=14, "
                        + "/sys/devices/system/cpu/cpu5/cpufreq/scaling_max_freq=15}");
        assertThat(mBatterySaverPolicy.getFileValues(false).toString()).isEqualTo("{}");
    }

    public void testSetPolicyLevel_Off() {
        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_OFF);

        // +1 to make sure the default value is off as well.
        for (int i = 0; i < MAX_SERVICE_TYPE + 1; ++i) {
            assertThat(mBatterySaverPolicy.getBatterySaverPolicy(i).batterySaverEnabled).isFalse();
        }
    }

    public void testSetPolicyLevel_Adaptive() {
        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_ADAPTIVE);

        mBatterySaverPolicy.setAdaptivePolicyLocked(BatterySaverPolicy.OFF_POLICY);
        for (int i = 0; i < MAX_SERVICE_TYPE + 1; ++i) {
            assertThat(mBatterySaverPolicy.getBatterySaverPolicy(i).batterySaverEnabled).isFalse();
        }

        mBatterySaverPolicy.setAdaptivePolicyLocked(
                Policy.fromSettings(BATTERY_SAVER_CONSTANTS, ""));
        verifyBatterySaverConstantsUpdated();
    }

    public void testCarModeChanges_Full() {
        mBatterySaverPolicy.updateConstantsLocked(
                "gps_mode=" + PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF
                        + ",enable_night_mode=true", "");
        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_FULL);
        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isEqualTo(PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF);
        assertTrue(mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NIGHT_MODE).batterySaverEnabled);

        mBatterySaverPolicy.setCarModeEnabled(true);

        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isAnyOf(PowerManager.LOCATION_MODE_NO_CHANGE,
                        PowerManager.LOCATION_MODE_FOREGROUND_ONLY);
        assertFalse(mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NIGHT_MODE).batterySaverEnabled);

        mBatterySaverPolicy.setCarModeEnabled(false);

        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isEqualTo(PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF);
        assertTrue(mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NIGHT_MODE).batterySaverEnabled);
    }

    public void testCarModeChanges_Adaptive() {
        mBatterySaverPolicy.setAdaptivePolicyLocked(
                Policy.fromSettings(
                        "gps_mode=" + PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF
                                + ",enable_night_mode=true", ""));
        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_ADAPTIVE);
        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isEqualTo(PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF);
        assertTrue(mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NIGHT_MODE).batterySaverEnabled);

        mBatterySaverPolicy.setCarModeEnabled(true);

        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isAnyOf(PowerManager.LOCATION_MODE_NO_CHANGE,
                        PowerManager.LOCATION_MODE_FOREGROUND_ONLY);
        assertFalse(mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NIGHT_MODE).batterySaverEnabled);

        mBatterySaverPolicy.setCarModeEnabled(false);

        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isEqualTo(PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF);
        assertTrue(mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NIGHT_MODE).batterySaverEnabled);
    }
}
