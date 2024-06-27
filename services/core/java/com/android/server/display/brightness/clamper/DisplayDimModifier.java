/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.brightness.clamper;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManagerInternal;
import android.os.PowerManager;
import android.util.IndentingPrintWriter;

import com.android.internal.R;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.BrightnessUtils;

import java.io.PrintWriter;
import java.util.Objects;

class DisplayDimModifier extends BrightnessModifier {

    // The dim screen brightness.
    private final float mScreenBrightnessDimConfig;

    // The minimum dim amount to use if the screen brightness is already below
    // mScreenBrightnessDimConfig.
    private final float mScreenBrightnessMinimumDimAmount;

    DisplayDimModifier(Context context) {
        PowerManager pm = Objects.requireNonNull(context.getSystemService(PowerManager.class));
        Resources resources = context.getResources();

        mScreenBrightnessDimConfig = BrightnessUtils.clampAbsoluteBrightness(
                pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DIM));
        mScreenBrightnessMinimumDimAmount = resources.getFloat(
                R.dimen.config_screenBrightnessMinimumDimAmountFloat);
    }


    @Override
    boolean shouldApply(DisplayManagerInternal.DisplayPowerRequest request) {
        return request.policy == DisplayManagerInternal.DisplayPowerRequest.POLICY_DIM;
    }

    @Override
    float getBrightnessAdjusted(float currentBrightness,
            DisplayManagerInternal.DisplayPowerRequest request) {
        return Math.max(
                Math.min(currentBrightness - mScreenBrightnessMinimumDimAmount,
                        mScreenBrightnessDimConfig),
                PowerManager.BRIGHTNESS_MIN);
    }

    @Override
    int getModifier() {
        return BrightnessReason.MODIFIER_DIMMED;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("DisplayDimModifier:");
        pw.println("  mScreenBrightnessDimConfig=" + mScreenBrightnessDimConfig);
        pw.println("  mScreenBrightnessMinimumDimAmount=" + mScreenBrightnessMinimumDimAmount);
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ");
        super.dump(ipw);
    }

    @Override
    public boolean shouldListenToLightSensor() {
        return false;
    }

    @Override
    public void setAmbientLux(float lux) {
        // unused
    }
}
