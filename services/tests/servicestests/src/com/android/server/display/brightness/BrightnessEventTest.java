/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.display.brightness;

import static org.junit.Assert.assertEquals;

import android.hardware.display.BrightnessInfo;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public final class BrightnessEventTest {
    private BrightnessEvent mBrightnessEvent;

    @Before
    public void setUp() {
        mBrightnessEvent = new BrightnessEvent(1);
        mBrightnessEvent.setReason(
                getReason(BrightnessReason.REASON_DOZE, BrightnessReason.MODIFIER_LOW_POWER));
        mBrightnessEvent.setLux(100.0f);
        mBrightnessEvent.setPreThresholdLux(150.0f);
        mBrightnessEvent.setTime(System.currentTimeMillis());
        mBrightnessEvent.setBrightness(0.6f);
        mBrightnessEvent.setRecommendedBrightness(0.6f);
        mBrightnessEvent.setHbmMax(0.62f);
        mBrightnessEvent.setThermalMax(0.65f);
        mBrightnessEvent.setHbmMode(BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF);
        mBrightnessEvent.setFlags(0);
        mBrightnessEvent.setAdjustmentFlags(0);
    }

    @Test
    public void testEqualsMainDataComparesAllFieldsExceptTime() {
        BrightnessEvent secondBrightnessEvent = new BrightnessEvent(1);
        secondBrightnessEvent.copyFrom(mBrightnessEvent);
        secondBrightnessEvent.setTime(0);
        assertEquals(secondBrightnessEvent.equalsMainData(mBrightnessEvent), true);
    }

    @Test
    public void testToStringWorksAsExpected() {
        String actualString = mBrightnessEvent.toString(false);
        String expectedString =
                "BrightnessEvent: disp=1, brt=0.6, rcmdBrt=0.6, preBrt=NaN, lux=100.0, preLux=150"
                        + ".0, hbmMax=0.62, hbmMode=off, thrmMax=0.65, flags=, reason=doze [ "
                        + "low_pwr ]";
        assertEquals(actualString, expectedString);
    }

    private BrightnessReason getReason(int reason, int modifier) {
        BrightnessReason brightnessReason = new BrightnessReason();
        brightnessReason.setReason(reason);
        brightnessReason.setModifier(modifier);
        return brightnessReason;
    }
}
