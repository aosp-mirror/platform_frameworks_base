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

package com.android.systemui.statusbar.phone;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.PowerManager;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.doze.AlwaysOnDisplayPolicy;
import com.android.systemui.doze.DozeScreenState;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.tuner.TunerService;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DozeParametersTest extends SysuiTestCase {

    private DozeParameters mDozeParameters;

    @Mock Resources mResources;
    @Mock private AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    @Mock private AlwaysOnDisplayPolicy mAlwaysOnDisplayPolicy;
    @Mock private PowerManager mPowerManager;
    @Mock private TunerService mTunerService;
    @Mock private BatteryController mBatteryController;
    @Mock private FeatureFlags mFeatureFlags;
    @Mock private DumpManager mDumpManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mDozeParameters = new DozeParameters(
            mResources,
            mAmbientDisplayConfiguration,
            mAlwaysOnDisplayPolicy,
            mPowerManager,
            mBatteryController,
            mTunerService,
            mDumpManager,
            mFeatureFlags
        );
    }
    @Test
    public void testSetControlScreenOffAnimation_setsDozeAfterScreenOff_false() {
        mDozeParameters.setControlScreenOffAnimation(true);
        reset(mPowerManager);
        mDozeParameters.setControlScreenOffAnimation(false);
        verify(mPowerManager).setDozeAfterScreenOff(eq(true));
    }

    @Test
    public void testSetControlScreenOffAnimation_setsDozeAfterScreenOff_true() {
        mDozeParameters.setControlScreenOffAnimation(false);
        reset(mPowerManager);
        mDozeParameters.setControlScreenOffAnimation(true);
        verify(mPowerManager).setDozeAfterScreenOff(eq(false));
    }

    @Test
    public void testGetWallpaperAodDuration_when_shouldControlScreenOff() {
        mDozeParameters.setControlScreenOffAnimation(true);
        Assert.assertEquals(
                "wallpaper hides faster when controlling screen off",
                mDozeParameters.getWallpaperAodDuration(),
                DozeScreenState.ENTER_DOZE_HIDE_WALLPAPER_DELAY);
    }

    @Test
    public void testGetAlwaysOn() {
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(true);
        mDozeParameters.onTuningChanged(Settings.Secure.DOZE_ALWAYS_ON, "1");

        assertThat(mDozeParameters.getAlwaysOn()).isTrue();
    }

    @Test
    public void testGetAlwaysOn_whenBatterySaver() {
        when(mBatteryController.isAodPowerSave()).thenReturn(true);
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(true);
        mDozeParameters.onTuningChanged(Settings.Secure.DOZE_ALWAYS_ON, "1");

        assertThat(mDozeParameters.getAlwaysOn()).isFalse();
    }

    @Test
    public void testControlUnlockedScreenOffAnimation_dozeAfterScreenOff_false() {
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(true);
        mDozeParameters.onTuningChanged(Settings.Secure.DOZE_ALWAYS_ON, "1");
        when(mFeatureFlags.useNewLockscreenAnimations()).thenReturn(true);

        assertTrue(mDozeParameters.shouldControlUnlockedScreenOff());

        // Trigger the setter for the current value.
        mDozeParameters.setControlScreenOffAnimation(mDozeParameters.shouldControlScreenOff());

        // We should have asked power manager not to doze after screen off no matter what, since
        // we're animating and controlling screen off.
        verify(mPowerManager).setDozeAfterScreenOff(eq(false));
    }

    @Test
    public void testControlUnlockedScreenOffAnimationDisabled_dozeAfterScreenOff() {
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(true);
        mDozeParameters.onTuningChanged(Settings.Secure.DOZE_ALWAYS_ON, "1");
        when(mFeatureFlags.useNewLockscreenAnimations()).thenReturn(false);

        assertFalse(mDozeParameters.shouldControlUnlockedScreenOff());

        // Trigger the setter for the current value.
        mDozeParameters.setControlScreenOffAnimation(mDozeParameters.shouldControlScreenOff());

        // We should have asked power manager to doze only if we're not controlling screen off
        // normally.
        verify(mPowerManager).setDozeAfterScreenOff(
                eq(!mDozeParameters.shouldControlScreenOff()));
    }
}
