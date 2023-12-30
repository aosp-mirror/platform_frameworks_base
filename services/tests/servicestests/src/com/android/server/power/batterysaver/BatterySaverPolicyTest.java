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
import android.os.BatterySaverPolicyConfig;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerSaveState;
import android.provider.DeviceConfig;
import android.provider.Settings.Global;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;
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
    private static final int SOUND_TRIGGER_MODE = 0; // SOUND_TRIGGER_MODE_ALL_ENABLED
    private static final String BATTERY_SAVER_CONSTANTS = "disable_vibration=false,"
            + "advertise_is_enabled=true,"
            + "disable_animation=false,"
            + "enable_firewall=true,"
            + "enable_datasaver=true,"
            + "enable_brightness_adjustment=false,"
            + "adjust_brightness_factor=0.7,"
            + "defer_full_backup=true,"
            + "defer_keyvalue_backup=false,"
            + "location_mode=0," // LOCATION_MODE_NO_CHANGE
            + "soundtrigger_mode=0," // SOUND_TRIGGER_MODE_ALL_ENABLE
            + "enable_night_mode=false,"
            + "enable_quick_doze=true";
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

    @Suppress
    @SmallTest
    public void testGetBatterySaverPolicy_PolicyVibration_DefaultValueCorrect() {
        testDefaultValue(
                com.android.internal.R.bool.config_batterySaver_full_disableVibration,
                ServiceType.VIBRATION);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyVibration_WithAccessibilityEnabled() {
        mBatterySaverPolicy.mAccessibilityEnabled.update(true);
        testServiceDefaultValue_Off(ServiceType.VIBRATION);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicySound_DefaultValueCorrect() {
        final int defaultMode = getContext().getResources().getInteger(
                com.android.internal.R.integer.config_batterySaver_full_soundTriggerMode);
        if (defaultMode == PowerManager.SOUND_TRIGGER_MODE_ALL_ENABLED) {
            testServiceDefaultValue_Off(ServiceType.SOUND);
        } else {
            testServiceDefaultValue_On(ServiceType.SOUND);
        }

        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_FULL);
        PowerSaveState stateOn =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.SOUND);
        assertThat(stateOn.soundTriggerMode).isEqualTo(defaultMode);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyFullBackup_DefaultValueCorrect() {
        testDefaultValue(
                com.android.internal.R.bool.config_batterySaver_full_deferFullBackup,
                ServiceType.FULL_BACKUP);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyKeyValueBackup_DefaultValueCorrect() {
        testDefaultValue(
                com.android.internal.R.bool.config_batterySaver_full_deferKeyValueBackup,
                ServiceType.KEYVALUE_BACKUP);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyAnimation_DefaultValueCorrect() {
        testDefaultValue(
                com.android.internal.R.bool.config_batterySaver_full_disableAnimation,
                ServiceType.ANIMATION);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyBatteryStats_DefaultValueCorrect() {
        testServiceDefaultValue_On(ServiceType.BATTERY_STATS);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyNetworkFirewall_DefaultValueCorrect() {
        testDefaultValue(
                com.android.internal.R.bool.config_batterySaver_full_enableFirewall,
                ServiceType.NETWORK_FIREWALL);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyNightMode_DefaultValueCorrect() {
        testDefaultValue(
                com.android.internal.R.bool.config_batterySaver_full_enableNightMode,
                ServiceType.NIGHT_MODE);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyDataSaver_DefaultValueCorrect() {
        testDefaultValue(
                com.android.internal.R.bool.config_batterySaver_full_enableDataSaver,
                ServiceType.DATA_SAVER);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyScreenBrightness_DefaultValueCorrect() {
        testDefaultValue(
                com.android.internal.R.bool.config_batterySaver_full_enableAdjustBrightness,
                ServiceType.SCREEN_BRIGHTNESS);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyLocation_DefaultValueCorrect() {
        final int defaultMode = getContext().getResources()
                .getInteger(com.android.internal.R.integer.config_batterySaver_full_locationMode);
        if (defaultMode == PowerManager.LOCATION_MODE_NO_CHANGE) {
            testServiceDefaultValue_Off(ServiceType.LOCATION);
        } else {
            testServiceDefaultValue_On(ServiceType.LOCATION);
        }

        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_FULL);
        PowerSaveState stateOn =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION);
        assertThat(stateOn.locationMode).isEqualTo(defaultMode);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyQuickDoze_DefaultValueCorrect() {
        testDefaultValue(
                com.android.internal.R.bool.config_batterySaver_full_enableQuickDoze,
                ServiceType.QUICK_DOZE);
    }

    @Suppress
    @SmallTest
    public void testUpdateConstants_getCorrectData() {
        mBatterySaverPolicy.updateConstantsLocked(BATTERY_SAVER_CONSTANTS, "");

        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_FULL);
        verifyBatterySaverConstantsUpdated();
    }

    private void verifyBatterySaverConstantsUpdated() {
        final PowerSaveState vibrationState =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.VIBRATION);
        assertThat(vibrationState.batterySaverEnabled).isFalse();

        final PowerSaveState animationState =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.ANIMATION);
        assertThat(animationState.batterySaverEnabled).isFalse();

        final PowerSaveState soundState =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.SOUND);
        assertThat(soundState.batterySaverEnabled).isTrue();
        assertThat(soundState.soundTriggerMode).isEqualTo(SOUND_TRIGGER_MODE);

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

    private void testDefaultValue(int boolResId, @ServiceType int type) {
        if (getContext().getResources().getBoolean(boolResId)) {
            testServiceDefaultValue_On(type);
        } else {
            testServiceDefaultValue_Off(type);
        }
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
                Policy.fromSettings(BATTERY_SAVER_CONSTANTS, "",
                        new DeviceConfig.Properties.Builder(
                                DeviceConfig.NAMESPACE_BATTERY_SAVER).build(), null));
        verifyBatterySaverConstantsUpdated();
    }

    public void testAutomotiveProjectionChanges_Full() {
        mBatterySaverPolicy.updateConstantsLocked(
                "location_mode=" + PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF
                        + ",enable_night_mode=true", "");
        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_FULL);
        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isEqualTo(PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF);
        assertTrue(mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NIGHT_MODE).batterySaverEnabled);

        mBatterySaverPolicy.mAutomotiveProjectionActive.update(true);

        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isAnyOf(PowerManager.LOCATION_MODE_NO_CHANGE,
                        PowerManager.LOCATION_MODE_FOREGROUND_ONLY);
        assertFalse(mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NIGHT_MODE).batterySaverEnabled);

        mBatterySaverPolicy.mAutomotiveProjectionActive.update(false);

        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isEqualTo(PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF);
        assertTrue(mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NIGHT_MODE).batterySaverEnabled);
    }

    public void testAutomotiveProjectionChanges_Adaptive() {
        mBatterySaverPolicy.setAdaptivePolicyLocked(
                Policy.fromSettings(
                        "location_mode=" + PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF
                                + ",enable_night_mode=true", "",
                        new DeviceConfig.Properties.Builder(
                                DeviceConfig.NAMESPACE_BATTERY_SAVER).build(), null));
        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_ADAPTIVE);
        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isEqualTo(PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF);
        assertTrue(mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NIGHT_MODE).batterySaverEnabled);

        mBatterySaverPolicy.mAutomotiveProjectionActive.update(true);

        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isAnyOf(PowerManager.LOCATION_MODE_NO_CHANGE,
                        PowerManager.LOCATION_MODE_FOREGROUND_ONLY);
        assertFalse(mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NIGHT_MODE).batterySaverEnabled);

        mBatterySaverPolicy.mAutomotiveProjectionActive.update(false);

        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isEqualTo(PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF);
        assertTrue(mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NIGHT_MODE).batterySaverEnabled);
    }

    public void testUserSettingsOverrideDeviceConfig() {
        Policy policy = Policy.fromSettings(
                BatterySaverPolicy.KEY_ADJUST_BRIGHTNESS_FACTOR + "=.1"
                        + "," + BatterySaverPolicy.KEY_ADVERTISE_IS_ENABLED + "=true"
                        + "," + BatterySaverPolicy.KEY_DEFER_FULL_BACKUP + "=true"
                        + "," + BatterySaverPolicy.KEY_DEFER_KEYVALUE_BACKUP + "=true"
                        + "," + BatterySaverPolicy.KEY_DISABLE_ANIMATION + "=true"
                        + "," + BatterySaverPolicy.KEY_DISABLE_AOD + "=true"
                        + "," + BatterySaverPolicy.KEY_DISABLE_LAUNCH_BOOST + "=true"
                        + "," + BatterySaverPolicy.KEY_DISABLE_OPTIONAL_SENSORS + "=true"
                        + "," + BatterySaverPolicy.KEY_DISABLE_VIBRATION + "=true"
                        + "," + BatterySaverPolicy.KEY_ENABLE_BRIGHTNESS_ADJUSTMENT + "=true"
                        + "," + BatterySaverPolicy.KEY_ENABLE_DATASAVER + "=true"
                        + "," + BatterySaverPolicy.KEY_ENABLE_FIREWALL + "=true"
                        + "," + BatterySaverPolicy.KEY_ENABLE_NIGHT_MODE + "=true"
                        + "," + BatterySaverPolicy.KEY_ENABLE_QUICK_DOZE + "=true"
                        + "," + BatterySaverPolicy.KEY_FORCE_ALL_APPS_STANDBY + "=true"
                        + "," + BatterySaverPolicy.KEY_FORCE_BACKGROUND_CHECK + "=true"
                        + "," + BatterySaverPolicy.KEY_LOCATION_MODE
                        + "=" + PowerManager.LOCATION_MODE_FOREGROUND_ONLY
                        + "," + BatterySaverPolicy.KEY_SOUNDTRIGGER_MODE
                        + "=" + PowerManager.SOUND_TRIGGER_MODE_CRITICAL_ONLY,
                "",
                new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_BATTERY_SAVER)
                        .setFloat(BatterySaverPolicy.KEY_ADJUST_BRIGHTNESS_FACTOR, .5f)
                        .setBoolean(BatterySaverPolicy.KEY_ADVERTISE_IS_ENABLED, false)
                        .setBoolean(BatterySaverPolicy.KEY_DEFER_FULL_BACKUP, false)
                        .setBoolean(BatterySaverPolicy.KEY_DEFER_KEYVALUE_BACKUP, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_ANIMATION, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_AOD, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_LAUNCH_BOOST, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_OPTIONAL_SENSORS, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_VIBRATION, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_BRIGHTNESS_ADJUSTMENT, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_DATASAVER, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_FIREWALL, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_NIGHT_MODE, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_QUICK_DOZE, false)
                        .setBoolean(BatterySaverPolicy.KEY_FORCE_ALL_APPS_STANDBY, false)
                        .setBoolean(BatterySaverPolicy.KEY_FORCE_BACKGROUND_CHECK, false)
                        .setInt(BatterySaverPolicy.KEY_LOCATION_MODE,
                                PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF)
                        .setInt(BatterySaverPolicy.KEY_SOUNDTRIGGER_MODE,
                                PowerManager.SOUND_TRIGGER_MODE_ALL_DISABLED)
                        .build(),
                null);
        assertEquals(.1f, policy.adjustBrightnessFactor);
        assertTrue(policy.advertiseIsEnabled);
        assertTrue(policy.deferFullBackup);
        assertTrue(policy.deferKeyValueBackup);
        assertTrue(policy.disableAnimation);
        assertTrue(policy.disableAod);
        assertTrue(policy.disableLaunchBoost);
        assertTrue(policy.disableOptionalSensors);
        assertTrue(policy.disableVibration);
        assertTrue(policy.enableAdjustBrightness);
        assertTrue(policy.enableDataSaver);
        assertTrue(policy.enableFirewall);
        assertTrue(policy.enableNightMode);
        assertTrue(policy.enableQuickDoze);
        assertTrue(policy.forceAllAppsStandby);
        assertTrue(policy.forceBackgroundCheck);
        assertEquals(PowerManager.LOCATION_MODE_FOREGROUND_ONLY, policy.locationMode);
        assertEquals(PowerManager.SOUND_TRIGGER_MODE_CRITICAL_ONLY, policy.soundTriggerMode);
    }

    public void testDeviceConfigOverridesDefaults() {
        Policy policy = Policy.fromSettings(
                "", "",
                new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_BATTERY_SAVER)
                        .setFloat(BatterySaverPolicy.KEY_ADJUST_BRIGHTNESS_FACTOR, .5f)
                        .setBoolean(BatterySaverPolicy.KEY_ADVERTISE_IS_ENABLED, false)
                        .setBoolean(BatterySaverPolicy.KEY_DEFER_FULL_BACKUP, false)
                        .setBoolean(BatterySaverPolicy.KEY_DEFER_KEYVALUE_BACKUP, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_ANIMATION, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_AOD, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_LAUNCH_BOOST, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_OPTIONAL_SENSORS, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_VIBRATION, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_BRIGHTNESS_ADJUSTMENT, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_DATASAVER, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_FIREWALL, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_NIGHT_MODE, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_QUICK_DOZE, false)
                        .setBoolean(BatterySaverPolicy.KEY_FORCE_ALL_APPS_STANDBY, false)
                        .setBoolean(BatterySaverPolicy.KEY_FORCE_BACKGROUND_CHECK, false)
                        .setInt(BatterySaverPolicy.KEY_LOCATION_MODE,
                                PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF)
                        .setInt(BatterySaverPolicy.KEY_SOUNDTRIGGER_MODE,
                                PowerManager.SOUND_TRIGGER_MODE_ALL_DISABLED)
                        .build(),
                null);
        assertEquals(.5f, policy.adjustBrightnessFactor);
        assertFalse(policy.advertiseIsEnabled);
        assertFalse(policy.deferFullBackup);
        assertFalse(policy.deferKeyValueBackup);
        assertFalse(policy.disableAnimation);
        assertFalse(policy.disableAod);
        assertFalse(policy.disableLaunchBoost);
        assertFalse(policy.disableOptionalSensors);
        assertFalse(policy.disableVibration);
        assertFalse(policy.enableAdjustBrightness);
        assertFalse(policy.enableDataSaver);
        assertFalse(policy.enableFirewall);
        assertFalse(policy.enableNightMode);
        assertFalse(policy.enableQuickDoze);
        assertFalse(policy.forceAllAppsStandby);
        assertFalse(policy.forceBackgroundCheck);
        assertEquals(PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF,
                policy.locationMode);
        assertEquals(PowerManager.SOUND_TRIGGER_MODE_ALL_DISABLED,
                policy.soundTriggerMode);
    }

    public void testDeviceConfig_AdaptiveValues() {
        final String adaptiveSuffix = "_adaptive";
        Policy policy = Policy.fromSettings(
                "", "",
                new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_BATTERY_SAVER)
                        .setFloat(BatterySaverPolicy.KEY_ADJUST_BRIGHTNESS_FACTOR, .5f)
                        .setBoolean(BatterySaverPolicy.KEY_ADVERTISE_IS_ENABLED, false)
                        .setBoolean(BatterySaverPolicy.KEY_DEFER_FULL_BACKUP, false)
                        .setBoolean(BatterySaverPolicy.KEY_DEFER_KEYVALUE_BACKUP, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_ANIMATION, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_AOD, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_LAUNCH_BOOST, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_OPTIONAL_SENSORS, false)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_VIBRATION, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_BRIGHTNESS_ADJUSTMENT, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_DATASAVER, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_FIREWALL, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_NIGHT_MODE, false)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_QUICK_DOZE, false)
                        .setBoolean(BatterySaverPolicy.KEY_FORCE_ALL_APPS_STANDBY, false)
                        .setBoolean(BatterySaverPolicy.KEY_FORCE_BACKGROUND_CHECK, false)
                        .setInt(BatterySaverPolicy.KEY_LOCATION_MODE,
                                PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF)
                        .setInt(BatterySaverPolicy.KEY_SOUNDTRIGGER_MODE,
                                PowerManager.SOUND_TRIGGER_MODE_ALL_DISABLED)
                        .setFloat(BatterySaverPolicy.KEY_ADJUST_BRIGHTNESS_FACTOR + adaptiveSuffix,
                                .9f)
                        .setBoolean(BatterySaverPolicy.KEY_ADVERTISE_IS_ENABLED + adaptiveSuffix,
                                true)
                        .setBoolean(BatterySaverPolicy.KEY_DEFER_FULL_BACKUP + adaptiveSuffix, true)
                        .setBoolean(BatterySaverPolicy.KEY_DEFER_KEYVALUE_BACKUP + adaptiveSuffix,
                                true)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_ANIMATION + adaptiveSuffix, true)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_AOD + adaptiveSuffix, true)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_LAUNCH_BOOST + adaptiveSuffix,
                                true)
                        .setBoolean(
                                BatterySaverPolicy.KEY_DISABLE_OPTIONAL_SENSORS + adaptiveSuffix,
                                true)
                        .setBoolean(BatterySaverPolicy.KEY_DISABLE_VIBRATION + adaptiveSuffix, true)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_BRIGHTNESS_ADJUSTMENT
                                        + adaptiveSuffix, true)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_DATASAVER + adaptiveSuffix, true)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_FIREWALL + adaptiveSuffix, true)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_NIGHT_MODE + adaptiveSuffix, true)
                        .setBoolean(BatterySaverPolicy.KEY_ENABLE_QUICK_DOZE + adaptiveSuffix, true)
                        .setBoolean(BatterySaverPolicy.KEY_FORCE_ALL_APPS_STANDBY + adaptiveSuffix,
                                true)
                        .setBoolean(BatterySaverPolicy.KEY_FORCE_BACKGROUND_CHECK + adaptiveSuffix,
                                true)
                        .setInt(BatterySaverPolicy.KEY_LOCATION_MODE + adaptiveSuffix,
                                PowerManager.LOCATION_MODE_FOREGROUND_ONLY)
                        .setInt(BatterySaverPolicy.KEY_SOUNDTRIGGER_MODE + adaptiveSuffix,
                                PowerManager.SOUND_TRIGGER_MODE_CRITICAL_ONLY)
                        .build(), adaptiveSuffix);
        assertEquals(.9f, policy.adjustBrightnessFactor);
        assertTrue(policy.advertiseIsEnabled);
        assertTrue(policy.deferFullBackup);
        assertTrue(policy.deferKeyValueBackup);
        assertTrue(policy.disableAnimation);
        assertTrue(policy.disableAod);
        assertTrue(policy.disableLaunchBoost);
        assertTrue(policy.disableOptionalSensors);
        assertTrue(policy.disableVibration);
        assertTrue(policy.enableAdjustBrightness);
        assertTrue(policy.enableDataSaver);
        assertTrue(policy.enableFirewall);
        assertTrue(policy.enableNightMode);
        assertTrue(policy.enableQuickDoze);
        assertTrue(policy.forceAllAppsStandby);
        assertTrue(policy.forceBackgroundCheck);
        assertEquals(PowerManager.LOCATION_MODE_FOREGROUND_ONLY, policy.locationMode);
        assertEquals(PowerManager.SOUND_TRIGGER_MODE_CRITICAL_ONLY, policy.soundTriggerMode);
    }

    public void testSetFullPolicy_overridesSettingsAndDeviceConfig_clearOnFullExit() {
        mDeviceSpecificConfigResId = R.string.config_batterySaverDeviceSpecificConfig_1;
        mMockGlobalSettings.put(Global.BATTERY_SAVER_CONSTANTS,
                "location_mode=" + PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF);
        mMockGlobalSettings.put(Global.BATTERY_SAVER_DEVICE_SPECIFIC_CONSTANTS, "");

        mBatterySaverPolicy.onChange();
        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isEqualTo(PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF);

        Policy currentFullPolicy = mBatterySaverPolicy.getPolicyLocked(POLICY_LEVEL_FULL);
        BatterySaverPolicyConfig currentFullPolicyConfig = currentFullPolicy.toConfig();
        BatterySaverPolicyConfig newFullPolicyConfig =
                new BatterySaverPolicyConfig.Builder(currentFullPolicyConfig)
                        .setLocationMode(PowerManager.LOCATION_MODE_FOREGROUND_ONLY)
                        .build();
        mBatterySaverPolicy.setFullPolicyLocked(Policy.fromConfig(newFullPolicyConfig));
        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isEqualTo(PowerManager.LOCATION_MODE_FOREGROUND_ONLY);

        // Any policy settings set through #setFullPolicy will be cleared when exiting full policy.
        // Default policy settings will be used on the next full policy mode enter unless
        // #setFullPolicy is called again.
        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_OFF);
        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isEqualTo(PowerManager.LOCATION_MODE_NO_CHANGE);

        mBatterySaverPolicy.setPolicyLevel(POLICY_LEVEL_FULL);
        assertThat(mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.LOCATION).locationMode)
                .isEqualTo(PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF);
    }
}
