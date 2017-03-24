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
package com.android.server.power;

import android.os.PowerSaveState;
import android.os.Handler;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.power.BatterySaverPolicy.ServiceType;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static com.google.common.truth.Truth.assertThat;

/**
 * Tests for {@link com.android.server.power.BatterySaverPolicy}
 */
public class BatterySaverPolicyTest extends AndroidTestCase {
    private static final boolean BATTERY_SAVER_ON = true;
    private static final boolean BATTERY_SAVER_OFF = false;
    private static final float BRIGHTNESS_FACTOR = 0.7f;
    private static final float DEFAULT_BRIGHTNESS_FACTOR = 0.5f;
    private static final float PRECISION = 0.001f;
    private static final int GPS_MODE = 0;
    private static final int DEFAULT_GPS_MODE = 1;
    private static final String BATTERY_SAVER_CONSTANTS = "vibration_disabled=true,"
            + "animation_disabled=false,"
            + "soundtrigger_disabled=true,"
            + "firewall_disabled=false,"
            + "datasaver_disabled=false,"
            + "adjust_brightness_disabled=true,"
            + "adjust_brightness_factor=0.7,"
            + "fullbackup_deferred=true,"
            + "keyvaluebackup_deferred=false,"
            + "gps_mode=0";
    private static final String BATTERY_SAVER_INCORRECT_CONSTANTS = "vi*,!=,,true";

    @Mock
    Handler mHandler;
    private BatterySaverPolicy mBatterySaverPolicy;

    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mBatterySaverPolicy = new BatterySaverPolicy(mHandler);
        mBatterySaverPolicy.start(getContext().getContentResolver());
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyNull_DefaultValueCorrect() {
        testServiceDefaultValue(ServiceType.NULL);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyVibration_DefaultValueCorrect() {
        testServiceDefaultValue(ServiceType.VIBRATION);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicySound_DefaultValueCorrect() {
        testServiceDefaultValue(ServiceType.SOUND);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyFullBackup_DefaultValueCorrect() {
        testServiceDefaultValue(ServiceType.FULL_BACKUP);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyKeyValueBackup_DefaultValueCorrect() {
        testServiceDefaultValue(ServiceType.KEYVALUE_BACKUP);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyAnimation_DefaultValueCorrect() {
        testServiceDefaultValue(ServiceType.ANIMATION);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyBatteryStats_DefaultValueCorrect() {
        testServiceDefaultValue(ServiceType.BATTERY_STATS);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyNetworkFirewall_DefaultValueCorrect() {
        testServiceDefaultValue(ServiceType.NETWORK_FIREWALL);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyDataSaver_DefaultValueCorrect() {
        mBatterySaverPolicy.updateConstants("");
        final PowerSaveState batterySaverStateOn =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.DATA_SAVER, BATTERY_SAVER_ON);
        assertThat(batterySaverStateOn.batterySaverEnabled).isFalse();

        final PowerSaveState batterySaverStateOff = mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.DATA_SAVER, BATTERY_SAVER_OFF);
        assertThat(batterySaverStateOff.batterySaverEnabled).isFalse();
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyScreenBrightness_DefaultValueCorrect() {
        testServiceDefaultValue(ServiceType.SCREEN_BRIGHTNESS);

        PowerSaveState stateOn =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.SCREEN_BRIGHTNESS, true);
        assertThat(stateOn.brightnessFactor).isWithin(PRECISION).of(DEFAULT_BRIGHTNESS_FACTOR);
    }

    @SmallTest
    public void testGetBatterySaverPolicy_PolicyGps_DefaultValueCorrect() {
        testServiceDefaultValue(ServiceType.GPS);

        PowerSaveState stateOn =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.GPS, true);
        assertThat(stateOn.gpsMode).isEqualTo(DEFAULT_GPS_MODE);
    }

    @SmallTest
    public void testUpdateConstants_getCorrectData() {
        mBatterySaverPolicy.updateConstants(BATTERY_SAVER_CONSTANTS);

        final PowerSaveState vibrationState =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.VIBRATION, BATTERY_SAVER_ON);
        assertThat(vibrationState.batterySaverEnabled).isTrue();

        final PowerSaveState animationState =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.ANIMATION, BATTERY_SAVER_ON);
        assertThat(animationState.batterySaverEnabled).isFalse();

        final PowerSaveState soundState =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.SOUND, BATTERY_SAVER_ON);
        assertThat(soundState.batterySaverEnabled).isTrue();

        final PowerSaveState networkState = mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.NETWORK_FIREWALL, BATTERY_SAVER_ON);
        assertThat(networkState.batterySaverEnabled).isTrue();

        final PowerSaveState screenState =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.SCREEN_BRIGHTNESS,
                        BATTERY_SAVER_ON);
        assertThat(screenState.batterySaverEnabled).isFalse();
        assertThat(screenState.brightnessFactor).isWithin(PRECISION).of(BRIGHTNESS_FACTOR);

        final PowerSaveState fullBackupState = mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.FULL_BACKUP, BATTERY_SAVER_ON);
        assertThat(fullBackupState.batterySaverEnabled).isTrue();

        final PowerSaveState keyValueBackupState = mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.KEYVALUE_BACKUP, BATTERY_SAVER_ON);
        assertThat(keyValueBackupState.batterySaverEnabled).isFalse();

        final PowerSaveState dataSaverState = mBatterySaverPolicy.getBatterySaverPolicy(
                ServiceType.DATA_SAVER, BATTERY_SAVER_ON);
        assertThat(dataSaverState.batterySaverEnabled).isTrue();

        final PowerSaveState gpsState =
                mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.GPS, BATTERY_SAVER_ON);
        assertThat(gpsState.batterySaverEnabled).isTrue();
        assertThat(gpsState.gpsMode).isEqualTo(GPS_MODE);
    }

    @SmallTest
    public void testUpdateConstants_IncorrectData_NotCrash() {
        //Should not crash
        mBatterySaverPolicy.updateConstants(BATTERY_SAVER_INCORRECT_CONSTANTS);
        mBatterySaverPolicy.updateConstants(null);
    }

    private void testServiceDefaultValue(@ServiceType int type) {
        mBatterySaverPolicy.updateConstants("");
        final PowerSaveState batterySaverStateOn =
                mBatterySaverPolicy.getBatterySaverPolicy(type, BATTERY_SAVER_ON);
        assertThat(batterySaverStateOn.batterySaverEnabled).isTrue();

        final PowerSaveState batterySaverStateOff =
                mBatterySaverPolicy.getBatterySaverPolicy(type, BATTERY_SAVER_OFF);
        assertThat(batterySaverStateOff.batterySaverEnabled).isFalse();
    }
}
