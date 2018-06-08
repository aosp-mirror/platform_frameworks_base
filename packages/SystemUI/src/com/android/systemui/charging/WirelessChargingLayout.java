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

package com.android.systemui.charging;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.systemui.Interpolators;
import com.android.systemui.R;

import java.text.NumberFormat;

/**
 * @hide
 */
public class WirelessChargingLayout extends FrameLayout {
    private final static int UNKNOWN_BATTERY_LEVEL = -1;

    public WirelessChargingLayout(Context context) {
        super(context);
        init(context, null, false);
    }

    public WirelessChargingLayout(Context context, int batteryLevel, boolean isDozing) {
        super(context);
        init(context, null, batteryLevel, isDozing);
    }

    public WirelessChargingLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, false);
    }

    private void init(Context c, AttributeSet attrs, boolean isDozing) {
        init(c, attrs, -1, false);
    }

    private void init(Context context, AttributeSet attrs, int batteryLevel, boolean isDozing) {
        final int mBatteryLevel = batteryLevel;
        inflate(context, R.layout.wireless_charging_layout, this);

        // where the circle animation occurs:
        final WirelessChargingView mChargingView = findViewById(R.id.wireless_charging_view);

        // amount of battery:
        final TextView mPercentage = findViewById(R.id.wireless_charging_percentage);

        if (isDozing) {
            mChargingView.setPaintColor(Color.WHITE);
            mPercentage.setTextColor(Color.WHITE);
        }

        if (batteryLevel != UNKNOWN_BATTERY_LEVEL) {
            mPercentage.setText(NumberFormat.getPercentInstance().format(mBatteryLevel / 100f));
            mPercentage.setAlpha(0);
        }

        final long chargingAnimationFadeStartOffset = (long) context.getResources().getInteger(
                R.integer.wireless_charging_fade_offset);
        final long chargingAnimationFadeDuration = (long) context.getResources().getInteger(
                R.integer.wireless_charging_fade_duration);
        final float batteryLevelTextSizeStart = context.getResources().getFloat(
                R.dimen.wireless_charging_anim_battery_level_text_size_start);
        final float batteryLevelTextSizeEnd = context.getResources().getFloat(
                R.dimen.wireless_charging_anim_battery_level_text_size_end);

        // Animation Scale: battery percentage text scales from 0% to 100%
        ValueAnimator textSizeAnimator = ObjectAnimator.ofFloat(mPercentage, "textSize",
                batteryLevelTextSizeStart, batteryLevelTextSizeEnd);
        textSizeAnimator.setInterpolator(new PathInterpolator(0, 0, 0, 1));
        textSizeAnimator.setDuration((long) context.getResources().getInteger(
                R.integer.wireless_charging_battery_level_text_scale_animation_duration));

        // Animation Opacity: battery percentage text transitions from 0 to 1 opacity
        ValueAnimator textOpacityAnimator = ObjectAnimator.ofFloat(mPercentage, "alpha", 0, 1);
        textOpacityAnimator.setInterpolator(Interpolators.LINEAR);
        textOpacityAnimator.setDuration((long) context.getResources().getInteger(
                R.integer.wireless_charging_battery_level_text_opacity_duration));
        textOpacityAnimator.setStartDelay((long) context.getResources().getInteger(
                R.integer.wireless_charging_anim_opacity_offset));

        // Animation Opacity: battery percentage text fades from 1 to 0 opacity
        ValueAnimator textFadeAnimator = ObjectAnimator.ofFloat(mPercentage, "alpha", 1, 0);
        textFadeAnimator.setDuration(chargingAnimationFadeDuration);
        textFadeAnimator.setInterpolator(Interpolators.LINEAR);
        textFadeAnimator.setStartDelay(chargingAnimationFadeStartOffset);

        // Animation Opacity: wireless charging circle animation fades from 1 to 0 opacity
        ValueAnimator circleFadeAnimator = ObjectAnimator.ofFloat(mChargingView, "alpha",
                1, 0);
        circleFadeAnimator.setDuration(chargingAnimationFadeDuration);
        circleFadeAnimator.setInterpolator(Interpolators.LINEAR);
        circleFadeAnimator.setStartDelay(chargingAnimationFadeStartOffset);

        // play all animations together
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(textSizeAnimator, textOpacityAnimator, textFadeAnimator,
                circleFadeAnimator);
        animatorSet.start();
    }
}