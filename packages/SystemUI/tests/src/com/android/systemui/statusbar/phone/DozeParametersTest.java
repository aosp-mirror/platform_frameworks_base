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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.content.res.Resources;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.PowerManager;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.doze.AlwaysOnDisplayPolicy;
import com.android.systemui.doze.DozeScreenState;
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

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mDozeParameters = new DozeParameters(
            mResources,
            mAmbientDisplayConfiguration,
            mAlwaysOnDisplayPolicy,
            mPowerManager,
            mTunerService
        );
    }
    @Test
    public void test_setControlScreenOffAnimation_setsDozeAfterScreenOff_false() {
        mDozeParameters.setControlScreenOffAnimation(true);
        reset(mPowerManager);
        mDozeParameters.setControlScreenOffAnimation(false);
        verify(mPowerManager).setDozeAfterScreenOff(eq(true));
    }

    @Test
    public void test_setControlScreenOffAnimation_setsDozeAfterScreenOff_true() {
        mDozeParameters.setControlScreenOffAnimation(false);
        reset(mPowerManager);
        mDozeParameters.setControlScreenOffAnimation(true);
        verify(mPowerManager).setDozeAfterScreenOff(eq(false));
    }

    @Test
    public void test_getWallpaperAodDuration_when_shouldControlScreenOff() {
        mDozeParameters.setControlScreenOffAnimation(true);
        Assert.assertEquals(
                "wallpaper hides faster when controlling screen off",
                mDozeParameters.getWallpaperAodDuration(),
                DozeScreenState.ENTER_DOZE_HIDE_WALLPAPER_DELAY);
    }
}
