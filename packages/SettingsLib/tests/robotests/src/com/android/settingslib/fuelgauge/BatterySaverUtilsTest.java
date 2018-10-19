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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;

import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@RunWith(SettingsLibRobolectricTestRunner.class)
public class BatterySaverUtilsTest {
    final int BATTERY_SAVER_THRESHOLD_1 = 15;
    final int BATTERY_SAVER_THRESHOLD_2 = 20;

    @Mock
    Context mMockContext;

    @Mock
    ContentResolver mMockResolver;

    @Mock
    PowerManager mMockPowerManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mMockContext.getContentResolver()).thenReturn(mMockResolver);
        when(mMockContext.getSystemService(eq(PowerManager.class))).thenReturn(mMockPowerManager);
        when(mMockPowerManager.setPowerSaveMode(anyBoolean())).thenReturn(true);
    }

    @Test
    public void testSetPowerSaveMode_enable_firstCall_needWarning() throws Exception {
        Secure.putString(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, "null");

        assertEquals(false, BatterySaverUtils.setPowerSaveMode(mMockContext, true, true));

        verify(mMockContext, times(1)).sendBroadcast(any(Intent.class));
        verify(mMockPowerManager, times(0)).setPowerSaveMode(anyBoolean());

        // They shouldn't have changed.
        assertEquals(-1,
                Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(-2,
                Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void testSetPowerSaveMode_enable_secondCall_needWarning() throws Exception {
        Secure.putInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, 1); // Already acked.
        Secure.putString(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, "null");

        assertEquals(true, BatterySaverUtils.setPowerSaveMode(mMockContext, true, true));

        verify(mMockContext, times(0)).sendBroadcast(any(Intent.class));
        verify(mMockPowerManager, times(1)).setPowerSaveMode(eq(true));

        assertEquals(1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(1, Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void testSetPowerSaveMode_enable_thridCall_needWarning() throws Exception {
        Secure.putInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, 1); // Already acked.
        Secure.putInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, 1);

        assertEquals(true, BatterySaverUtils.setPowerSaveMode(mMockContext, true, true));

        verify(mMockContext, times(0)).sendBroadcast(any(Intent.class));
        verify(mMockPowerManager, times(1)).setPowerSaveMode(eq(true));

        assertEquals(1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(2, Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void testSetPowerSaveMode_enable_firstCall_noWarning() throws Exception {
        Secure.putString(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, "null");

        assertEquals(true, BatterySaverUtils.setPowerSaveMode(mMockContext, true, false));

        verify(mMockContext, times(0)).sendBroadcast(any(Intent.class));
        verify(mMockPowerManager, times(1)).setPowerSaveMode(eq(true));

        assertEquals(1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(1, Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void testSetPowerSaveMode_disable_firstCall_noWarning() throws Exception {
        Secure.putString(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, "null");

        // When disabling, needFirstTimeWarning doesn't matter.
        assertEquals(true, BatterySaverUtils.setPowerSaveMode(mMockContext, false, false));

        verify(mMockContext, times(0)).sendBroadcast(any(Intent.class));
        verify(mMockPowerManager, times(1)).setPowerSaveMode(eq(false));

        assertEquals(-1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(-2,
                Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void testSetPowerSaveMode_disable_firstCall_needWarning() throws Exception {
        Secure.putString(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, "null");
        Secure.putString(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, "null");

        // When disabling, needFirstTimeWarning doesn't matter.
        assertEquals(true, BatterySaverUtils.setPowerSaveMode(mMockContext, false, true));

        verify(mMockContext, times(0)).sendBroadcast(any(Intent.class));
        verify(mMockPowerManager, times(1)).setPowerSaveMode(eq(false));

        assertEquals(-1, Secure.getInt(mMockResolver, Secure.LOW_POWER_WARNING_ACKNOWLEDGED, -1));
        assertEquals(-2,
                Secure.getInt(mMockResolver, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, -2));
    }

    @Test
    public void testEnsureAutoBatterysaver_setNewPositiveValue_doNotOverwrite() throws Exception {
        Global.putString(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, "null");

        BatterySaverUtils.ensureAutoBatterySaver(mMockContext, BATTERY_SAVER_THRESHOLD_1);

        assertThat(Secure.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(BATTERY_SAVER_THRESHOLD_1);

        // Once a positive number is set, ensureAutoBatterySaver() won't overwrite it.
        BatterySaverUtils.ensureAutoBatterySaver(mMockContext, BATTERY_SAVER_THRESHOLD_2);
        assertThat(Secure.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(BATTERY_SAVER_THRESHOLD_1);
    }

    @Test
    public void testSetAutoBatterySaverTriggerLevel_setSuppressSuggestion() throws Exception {
        Global.putString(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, "null");
        Secure.putString(mMockResolver, Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION, "null");

        BatterySaverUtils.setAutoBatterySaverTriggerLevel(mMockContext, 0);
        assertThat(Global.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(0);
        assertThat(Secure.getInt(mMockResolver, Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION, -1))
                .isEqualTo(-1); // not set.

        BatterySaverUtils.setAutoBatterySaverTriggerLevel(mMockContext, BATTERY_SAVER_THRESHOLD_1 );
        assertThat( Global.getInt(mMockResolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, -1))
                .isEqualTo(BATTERY_SAVER_THRESHOLD_1);
        assertThat(Secure.getInt(mMockResolver, Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION, -1))
                .isEqualTo(1);
    }
}
