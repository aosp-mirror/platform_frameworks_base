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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.PowerManager;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.doze.DozeScreenState;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DozeParametersTest extends SysuiTestCase {

    @Test
    public void test_setControlScreenOffAnimation_setsDozeAfterScreenOff_false() {
        TestableDozeParameters dozeParameters = new TestableDozeParameters(getContext());
        PowerManager mockedPowerManager = dozeParameters.getPowerManager();
        dozeParameters.setControlScreenOffAnimation(true);
        reset(mockedPowerManager);
        dozeParameters.setControlScreenOffAnimation(false);
        verify(mockedPowerManager).setDozeAfterScreenOff(eq(true));
    }

    @Test
    public void test_setControlScreenOffAnimation_setsDozeAfterScreenOff_true() {
        TestableDozeParameters dozeParameters = new TestableDozeParameters(getContext());
        PowerManager mockedPowerManager = dozeParameters.getPowerManager();
        dozeParameters.setControlScreenOffAnimation(false);
        reset(mockedPowerManager);
        dozeParameters.setControlScreenOffAnimation(true);
        verify(dozeParameters.getPowerManager()).setDozeAfterScreenOff(eq(false));
    }

    @Test
    public void test_getWallpaperAodDuration_when_shouldControlScreenOff() {
        TestableDozeParameters dozeParameters = new TestableDozeParameters(getContext());
        dozeParameters.setControlScreenOffAnimation(true);
        Assert.assertEquals("wallpaper hides faster when controlling screen off",
                dozeParameters.getWallpaperAodDuration(),
                DozeScreenState.ENTER_DOZE_HIDE_WALLPAPER_DELAY);
    }

    private class TestableDozeParameters extends DozeParameters {
        private PowerManager mPowerManager;

        TestableDozeParameters(Context context) {
            super(context);
            mPowerManager = mock(PowerManager.class);
        }

        @Override
        protected PowerManager getPowerManager() {
            return mPowerManager;
        }
    }

}
