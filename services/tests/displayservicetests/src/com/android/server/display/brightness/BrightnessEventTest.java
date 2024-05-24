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

import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;

import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_IDLE;

import static org.junit.Assert.assertEquals;

import android.hardware.display.BrightnessInfo;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class BrightnessEventTest {
    private static final String DISPLAY_BRIGHTNESS_STRATEGY_NAME = "strategy_name";
    private BrightnessEvent mBrightnessEvent;

    @Before
    public void setUp() {
        mBrightnessEvent = new BrightnessEvent(1);
        mBrightnessEvent.setReason(
                getReason(BrightnessReason.REASON_DOZE, BrightnessReason.MODIFIER_LOW_POWER));
        mBrightnessEvent.setPhysicalDisplayId("test");
        mBrightnessEvent.setDisplayState(Display.STATE_ON);
        mBrightnessEvent.setDisplayPolicy(POLICY_BRIGHT);
        mBrightnessEvent.setLux(100.0f);
        mBrightnessEvent.setPreThresholdLux(150.0f);
        mBrightnessEvent.setTime(System.currentTimeMillis());
        mBrightnessEvent.setInitialBrightness(25.0f);
        mBrightnessEvent.setBrightness(0.6f);
        mBrightnessEvent.setRecommendedBrightness(0.6f);
        mBrightnessEvent.setHbmMax(0.62f);
        mBrightnessEvent.setRbcStrength(-1);
        mBrightnessEvent.setThermalMax(0.65f);
        mBrightnessEvent.setPowerFactor(0.2f);
        mBrightnessEvent.setWasShortTermModelActive(true);
        mBrightnessEvent.setHbmMode(BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF);
        mBrightnessEvent.setFlags(0);
        mBrightnessEvent.setAdjustmentFlags(0);
        mBrightnessEvent.setAutomaticBrightnessEnabled(true);
        mBrightnessEvent.setDisplayBrightnessStrategyName(DISPLAY_BRIGHTNESS_STRATEGY_NAME);
        mBrightnessEvent.setAutoBrightnessMode(AUTO_BRIGHTNESS_MODE_IDLE);
    }

    @Test
    public void testEqualsMainDataComparesAllFieldsExceptTime() {
        BrightnessEvent secondBrightnessEvent = new BrightnessEvent(1);
        secondBrightnessEvent.copyFrom(mBrightnessEvent);
        secondBrightnessEvent.setTime(0);
        assertEquals(true, secondBrightnessEvent.equalsMainData(mBrightnessEvent));
    }

    @Test
    public void testToStringWorksAsExpected() {
        String actualString = mBrightnessEvent.toString(false);
        String expectedString =
                "BrightnessEvent: disp=1, physDisp=test, displayState=ON, displayPolicy=BRIGHT,"
                + " brt=0.6, initBrt=25.0, rcmdBrt=0.6, preBrt=NaN, lux=100.0, preLux=150.0,"
                + " hbmMax=0.62, hbmMode=off, rbcStrength=-1, thrmMax=0.65, powerFactor=0.2,"
                + " wasShortTermModelActive=true, flags=, reason=doze [ low_pwr ],"
                + " autoBrightness=true, strategy=" + DISPLAY_BRIGHTNESS_STRATEGY_NAME
                + ", autoBrightnessMode=idle";
        assertEquals(expectedString, actualString);
    }

    @Test
    public void testFlagsToString() {
        mBrightnessEvent.reset();
        mBrightnessEvent.setFlags(mBrightnessEvent.getFlags() | BrightnessEvent.FLAG_RBC);
        String actualString = mBrightnessEvent.flagsToString();
        String expectedString = "rbc ";
        assertEquals(expectedString, actualString);
    }

    @Test
    public void testFlagsToString_multipleFlags() {
        mBrightnessEvent.reset();
        mBrightnessEvent.setFlags(mBrightnessEvent.getFlags()
                    | BrightnessEvent.FLAG_RBC
                    | BrightnessEvent.FLAG_LOW_POWER_MODE);
        String actualString = mBrightnessEvent.flagsToString();
        String expectedString = "rbc low_power_mode ";
        assertEquals(expectedString, actualString);
    }


    private BrightnessReason getReason(int reason, int modifier) {
        BrightnessReason brightnessReason = new BrightnessReason();
        brightnessReason.setReason(reason);
        brightnessReason.setModifier(modifier);
        return brightnessReason;
    }
}
