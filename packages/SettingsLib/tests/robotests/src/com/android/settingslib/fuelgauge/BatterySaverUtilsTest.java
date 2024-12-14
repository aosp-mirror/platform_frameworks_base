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

package com.android.settingslib.fuelgauge;

import static com.android.settingslib.fuelgauge.BatterySaverLogging.ACTION_SAVER_STATE_MANUAL_UPDATE;
import static com.android.settingslib.fuelgauge.BatterySaverLogging.SAVER_ENABLED_UNKNOWN;
import static com.android.settingslib.fuelgauge.BatterySaverUtils.ACTION_SHOW_AUTO_SAVER_SUGGESTION;
import static com.android.settingslib.fuelgauge.BatterySaverUtils.ACTION_SHOW_START_SAVER_CONFIRMATION;
import static com.android.settingslib.fuelgauge.BatterySaverUtils.KEY_NO_SCHEDULE;
import static com.android.settingslib.fuelgauge.BatterySaverUtils.KEY_PERCENTAGE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;

import com.android.settingslib.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class BatterySaverUtilsTest {
    private static final int BATTERY_SAVER_THRESHOLD_1 = 15;
    private static final int BATTERY_SAVER_THRESHOLD_2 = 20;

    @Rule(order = 0) public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule(order = 1) public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private Context mMockContext;
    @Mock private ContentResolver mMockResolver;
    @Mock private PowerManager mMockPowerManager;

    @Before
    public void setUp() throws Exception {
        when(mMockContext.getContentResolver()).thenReturn(mMockResolver);
        when(mMockContext.getSystemService(eq(PowerManager.class))).thenReturn(mMockPowerManager);
        when(mMockPowerManager.setPowerSaveModeEnabled(anyBoolean())).thenReturn(true);
    }

    @Test
    public void setPowerSaveMode_enableWithWarning_firstCall_needConfirmationWarning() {
        Secure.putString(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, "null");

        assertThat(BatterySaverUtils.setPowerSaveMode(mMockContext, true, true,
                SAVER_ENABLED_UNKNOWN)).isFalse();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockContext, times(1)).sendBroadcast(intentCaptor.capture());
        verify(mMockPowerManager, times(0)).setPowerSaveModeEnabled(anyBoolean());

        assertThat(intentCaptor.getValue().getAction()).isEqualTo(
                ACTION_SHOW_START_SAVER_CONFIRMATION);
        // They shouldn't have changed.
        assertEquals(-1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(-1,
                Secure.getInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(-2,
                Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void setPowerSaveMode_enableWithWarning_secondCall_expectUpdateIntent() {
        // Already acked.
        Secure.putInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, 1);
        Secure.putInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, 1);
        Secure.putString(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, "null");

        assertThat(BatterySaverUtils.setPowerSaveMode(mMockContext, true, true,
                SAVER_ENABLED_UNKNOWN)).isTrue();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockContext, times(1)).sendBroadcast(intentCaptor.capture());
        verify(mMockPowerManager, times(1)).setPowerSaveModeEnabled(eq(true));

        assertThat(intentCaptor.getValue().getAction()).isEqualTo(
                ACTION_SAVER_STATE_MANUAL_UPDATE);
        assertEquals(1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(1,
                Secure.getInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(1,
                Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void setPowerSaveMode_enableWithWarning_thirdCall_expectUpdateIntent() {
        // Already acked.
        Secure.putInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, 1);
        Secure.putInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, 1);
        Secure.putInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, 1);

        assertThat(BatterySaverUtils.setPowerSaveMode(mMockContext, true, true,
                SAVER_ENABLED_UNKNOWN)).isTrue();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockContext, times(1)).sendBroadcast(intentCaptor.capture());
        verify(mMockPowerManager, times(1)).setPowerSaveModeEnabled(eq(true));

        assertThat(intentCaptor.getValue().getAction()).isEqualTo(
                ACTION_SAVER_STATE_MANUAL_UPDATE);
        assertEquals(1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(1,
                Secure.getInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(2,
                Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void setPowerSaveMode_enableWithWarning_5thCall_needAutoSuggestionWarning() {
        // Already acked.
        Secure.putInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, 1);
        Secure.putInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, 1);
        Secure.putInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, 3);

        assertThat(BatterySaverUtils.setPowerSaveMode(mMockContext, true, true,
                SAVER_ENABLED_UNKNOWN)).isTrue();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockContext, times(2)).sendBroadcast(intentCaptor.capture());
        verify(mMockPowerManager, times(1)).setPowerSaveModeEnabled(eq(true));

        List<Intent> values = intentCaptor.getAllValues();
        assertThat(values.get(0).getAction()).isEqualTo(ACTION_SHOW_AUTO_SAVER_SUGGESTION);
        assertThat(values.get(1).getAction()).isEqualTo(ACTION_SAVER_STATE_MANUAL_UPDATE);
        assertEquals(1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(1,
                Secure.getInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(4,
                Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void setPowerSaveMode_enableWithoutWarning_expectUpdateIntent() {
        Secure.putString(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, "null");

        assertThat(BatterySaverUtils.setPowerSaveMode(mMockContext, true, false,
                SAVER_ENABLED_UNKNOWN)).isTrue();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockContext, times(1)).sendBroadcast(intentCaptor.capture());
        verify(mMockPowerManager, times(1)).setPowerSaveModeEnabled(eq(true));

        assertThat(intentCaptor.getValue().getAction()).isEqualTo(
                ACTION_SAVER_STATE_MANUAL_UPDATE);
        assertEquals(1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(1,
                Secure.getInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(1, Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void setPowerSaveMode_disableWithoutWarning_expectUpdateIntent() {
        verifyDisablePowerSaveMode(/* needFirstTimeWarning= */ false);
    }

    @Test
    public void setPowerSaveMode_disableWithWarning_expectUpdateIntent() {
        verifyDisablePowerSaveMode(/* needFirstTimeWarning= */ true);
    }

    @Test
    public void ensureAutoBatterysaver_setNewPositiveValue_doNotOverwrite() {
        Global.putInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);

        BatterySaverUtils.ensureAutoBatterySaver(mMockContext, BATTERY_SAVER_THRESHOLD_1);

        assertThat(Global.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(BATTERY_SAVER_THRESHOLD_1);

        // Once a positive number is set, ensureAutoBatterySaver() won't overwrite it.
        BatterySaverUtils.ensureAutoBatterySaver(mMockContext, BATTERY_SAVER_THRESHOLD_2);
        assertThat(Global.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(BATTERY_SAVER_THRESHOLD_1);
    }

    @Test
    public void setAutoBatterySaverTriggerLevel_setSuppressSuggestion() {
        Global.putString(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, "null");
        Secure.putString(mMockResolver, Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION, "null");

        BatterySaverUtils.setAutoBatterySaverTriggerLevel(mMockContext, 0);
        assertThat(Global.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(0);
        assertThat(Secure.getInt(mMockResolver, Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION, -1))
                .isEqualTo(-1); // not set.

        BatterySaverUtils.setAutoBatterySaverTriggerLevel(mMockContext, BATTERY_SAVER_THRESHOLD_1);
        assertThat(Global.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(BATTERY_SAVER_THRESHOLD_1);
        assertThat(Secure.getInt(mMockResolver, Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION, -1))
                .isEqualTo(1);
    }

    @Test
    public void getBatterySaverScheduleKey_returnExpectedKey() {
        Global.putInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);
        Global.putInt(mMockResolver, Global.AUTOMATIC_POWER_SAVE_MODE,
                PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);

        assertThat(BatterySaverUtils.getBatterySaverScheduleKey(mMockContext)).isEqualTo(
                KEY_NO_SCHEDULE);

        Global.putInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, 20);
        Global.putInt(mMockResolver, Global.AUTOMATIC_POWER_SAVE_MODE,
                PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);

        assertThat(BatterySaverUtils.getBatterySaverScheduleKey(mMockContext)).isEqualTo(
                KEY_PERCENTAGE);

        Global.putInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, 20);
        Global.putInt(mMockResolver, Global.AUTOMATIC_POWER_SAVE_MODE,
                PowerManager.POWER_SAVE_MODE_TRIGGER_DYNAMIC);

        assertThat(BatterySaverUtils.getBatterySaverScheduleKey(mMockContext)).isEqualTo(
                KEY_NO_SCHEDULE);
    }

    @EnableFlags(Flags.FLAG_EXTREME_POWER_LOW_STATE_VULNERABILITY)
    @Test
    public void setPowerSaveMode_1stTimeAndDeviceLocked_enableBatterySaver() {
        var keyguardManager = mock(KeyguardManager.class);
        when(mMockContext.getSystemService(KeyguardManager.class)).thenReturn(keyguardManager);
        when(keyguardManager.isDeviceLocked()).thenReturn(true);
        when(mMockPowerManager.setPowerSaveModeEnabled(true)).thenReturn(true);

        var enableResult = BatterySaverUtils.setPowerSaveMode(
                mMockContext,
                /* enable= */ true,
                /* needFirstTimeWarning= */ true,
                /* reason= */ 0);

        assertThat(enableResult).isTrue();
    }

    @Test
    public void setBatterySaverScheduleMode_setSchedule() {
        BatterySaverUtils.setBatterySaverScheduleMode(mMockContext, KEY_NO_SCHEDULE, -1);

        assertThat(Global.getInt(mMockResolver, Global.AUTOMATIC_POWER_SAVE_MODE, -1))
                .isEqualTo(PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);
        assertThat(Global.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(0);

        BatterySaverUtils.setBatterySaverScheduleMode(mMockContext, KEY_PERCENTAGE, 20);

        assertThat(Global.getInt(mMockResolver, Global.AUTOMATIC_POWER_SAVE_MODE, -1))
                .isEqualTo(PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);
        assertThat(Global.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(20);

    }

    private void verifyDisablePowerSaveMode(boolean needFirstTimeWarning) {
        Secure.putString(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, "null");

        // When disabling, needFirstTimeWarning doesn't matter.
        assertThat(BatterySaverUtils.setPowerSaveMode(mMockContext, false, needFirstTimeWarning,
                SAVER_ENABLED_UNKNOWN)).isTrue();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockContext, times(1)).sendBroadcast(intentCaptor.capture());
        verify(mMockPowerManager, times(1)).setPowerSaveModeEnabled(eq(false));

        assertThat(intentCaptor.getValue().getAction()).isEqualTo(
                ACTION_SAVER_STATE_MANUAL_UPDATE);
        assertEquals(-1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(-1,
                Secure.getInt(mMockResolver, Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(-2,
                Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }
}
