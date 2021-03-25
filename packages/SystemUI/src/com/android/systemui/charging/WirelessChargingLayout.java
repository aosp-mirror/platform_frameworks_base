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
import android.graphics.PointF;
import android.graphics.drawable.Animatable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.charging.ChargingRippleView;

import java.text.NumberFormat;

/**
 * @hide
 */
public class WirelessChargingLayout extends FrameLayout {
    public static final int UNKNOWN_BATTERY_LEVEL = -1;
    private static final long RIPPLE_ANIMATION_DURATION = 2000;
    private ChargingRippleView mRippleView;

    public WirelessChargingLayout(Context context) {
        super(context);
        init(context, null, false);
    }

    public WirelessChargingLayout(Context context, int transmittingBatteryLevel, int batteryLevel,
            boolean isDozing) {
        super(context);
        init(context, null, transmittingBatteryLevel, batteryLevel, isDozing);
    }

    public WirelessChargingLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, false);
    }

    private void init(Context c, AttributeSet attrs, boolean isDozing) {
        init(c, attrs, -1, -1, false);
    }

    private void init(Context context, AttributeSet attrs, int transmittingBatteryLevel,
            int batteryLevel, boolean isDozing) {
        final boolean showTransmittingBatteryLevel =
                (transmittingBatteryLevel != UNKNOWN_BATTERY_LEVEL);

        // set style based on background
        int style = R.style.ChargingAnim_WallpaperBackground;
        if (isDozing) {
            style = R.style.ChargingAnim_DarkBackground;
        }

        inflate(new ContextThemeWrapper(context, style), R.layout.wireless_charging_layout, this);

        // where the circle animation occurs:
        final ImageView chargingView = findViewById(R.id.wireless_charging_view);
        final Animatable chargingAnimation = (Animatable) chargingView.getDrawable();

        // amount of battery:
        final TextView percentage = findViewById(R.id.wireless_charging_percentage);

        if (batteryLevel != UNKNOWN_BATTERY_LEVEL) {
            percentage.setText(NumberFormat.getPercentInstance().format(batteryLevel / 100f));
            percentage.setAlpha(0);
        }

        final long chargingAnimationFadeStartOffset = context.getResources().getInteger(
                R.integer.wireless_charging_fade_offset);
        final long chargingAnimationFadeDuration = context.getResources().getInteger(
                R.integer.wireless_charging_fade_duration);
        final float batteryLevelTextSizeStart = context.getResources().getFloat(
                R.dimen.wireless_charging_anim_battery_level_text_size_start);
        final float batteryLevelTextSizeEnd = context.getResources().getFloat(
                R.dimen.wireless_charging_anim_battery_level_text_size_end) * (
                showTransmittingBatteryLevel ? 0.75f : 1.0f);

        // Animation Scale: battery percentage text scales from 0% to 100%
        ValueAnimator textSizeAnimator = ObjectAnimator.ofFloat(percentage, "textSize",
                batteryLevelTextSizeStart, batteryLevelTextSizeEnd);
        textSizeAnimator.setInterpolator(new PathInterpolator(0, 0, 0, 1));
        textSizeAnimator.setDuration(context.getResources().getInteger(
                R.integer.wireless_charging_battery_level_text_scale_animation_duration));

        // Animation Opacity: battery percentage text transitions from 0 to 1 opacity
        ValueAnimator textOpacityAnimator = ObjectAnimator.ofFloat(percentage, "alpha", 0, 1);
        textOpacityAnimator.setInterpolator(Interpolators.LINEAR);
        textOpacityAnimator.setDuration(context.getResources().getInteger(
                R.integer.wireless_charging_battery_level_text_opacity_duration));
        textOpacityAnimator.setStartDelay(context.getResources().getInteger(
                R.integer.wireless_charging_anim_opacity_offset));

        // Animation Opacity: battery percentage text fades from 1 to 0 opacity
        ValueAnimator textFadeAnimator = ObjectAnimator.ofFloat(percentage, "alpha", 1, 0);
        textFadeAnimator.setDuration(chargingAnimationFadeDuration);
        textFadeAnimator.setInterpolator(Interpolators.LINEAR);
        textFadeAnimator.setStartDelay(chargingAnimationFadeStartOffset);

        // play all animations together
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(textSizeAnimator, textOpacityAnimator, textFadeAnimator);

        mRippleView = findViewById(R.id.wireless_charging_ripple);

        if (!showTransmittingBatteryLevel) {
            chargingAnimation.start();
            animatorSet.start();
            return;
        }

        // amount of transmitting battery:
        final TextView transmittingPercentage = findViewById(
                R.id.reverse_wireless_charging_percentage);
        transmittingPercentage.setVisibility(VISIBLE);
        transmittingPercentage.setText(
                NumberFormat.getPercentInstance().format(transmittingBatteryLevel / 100f));
        transmittingPercentage.setAlpha(0);

        // Animation Scale: transmitting battery percentage text scales from 0% to 100%
        ValueAnimator textSizeAnimatorTransmitting = ObjectAnimator.ofFloat(transmittingPercentage,
                "textSize", batteryLevelTextSizeStart, batteryLevelTextSizeEnd);
        textSizeAnimatorTransmitting.setInterpolator(new PathInterpolator(0, 0, 0, 1));
        textSizeAnimatorTransmitting.setDuration(context.getResources().getInteger(
                R.integer.wireless_charging_battery_level_text_scale_animation_duration));

        // Animation Opacity: transmitting battery percentage text transitions from 0 to 1 opacity
        ValueAnimator textOpacityAnimatorTransmitting = ObjectAnimator.ofFloat(
                transmittingPercentage, "alpha", 0, 1);
        textOpacityAnimatorTransmitting.setInterpolator(Interpolators.LINEAR);
        textOpacityAnimatorTransmitting.setDuration(context.getResources().getInteger(
                R.integer.wireless_charging_battery_level_text_opacity_duration));
        textOpacityAnimatorTransmitting.setStartDelay(
                context.getResources().getInteger(R.integer.wireless_charging_anim_opacity_offset));

        // Animation Opacity: transmitting battery percentage text fades from 1 to 0 opacity
        ValueAnimator textFadeAnimatorTransmitting = ObjectAnimator.ofFloat(transmittingPercentage,
                "alpha", 1, 0);
        textFadeAnimatorTransmitting.setDuration(chargingAnimationFadeDuration);
        textFadeAnimatorTransmitting.setInterpolator(Interpolators.LINEAR);
        textFadeAnimatorTransmitting.setStartDelay(chargingAnimationFadeStartOffset);

        // play all animations together
        AnimatorSet animatorSetTransmitting = new AnimatorSet();
        animatorSetTransmitting.playTogether(textSizeAnimatorTransmitting,
                textOpacityAnimatorTransmitting, textFadeAnimatorTransmitting);

        // transmitting battery icon
        final ImageView chargingViewIcon = findViewById(R.id.reverse_wireless_charging_icon);
        chargingViewIcon.setVisibility(VISIBLE);
        final int padding = Math.round(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, batteryLevelTextSizeEnd,
                        getResources().getDisplayMetrics()));
        chargingViewIcon.setPadding(padding, 0, padding, 0);

        // Animation Opacity: transmitting battery icon transitions from 0 to 1 opacity
        ValueAnimator textOpacityAnimatorIcon = ObjectAnimator.ofFloat(chargingViewIcon, "alpha", 0,
                1);
        textOpacityAnimatorIcon.setInterpolator(Interpolators.LINEAR);
        textOpacityAnimatorIcon.setDuration(context.getResources().getInteger(
                R.integer.wireless_charging_battery_level_text_opacity_duration));
        textOpacityAnimatorIcon.setStartDelay(
                context.getResources().getInteger(R.integer.wireless_charging_anim_opacity_offset));

        // Animation Opacity: transmitting battery icon fades from 1 to 0 opacity
        ValueAnimator textFadeAnimatorIcon = ObjectAnimator.ofFloat(chargingViewIcon, "alpha", 1,
                0);
        textFadeAnimatorIcon.setDuration(chargingAnimationFadeDuration);
        textFadeAnimatorIcon.setInterpolator(Interpolators.LINEAR);
        textFadeAnimatorIcon.setStartDelay(chargingAnimationFadeStartOffset);

        // play all animations together
        AnimatorSet animatorSetIcon = new AnimatorSet();
        animatorSetIcon.playTogether(textOpacityAnimatorIcon, textFadeAnimatorIcon);

        chargingAnimation.start();
        animatorSet.start();
        animatorSetTransmitting.start();
        animatorSetIcon.start();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mRippleView != null) {
            int width = getMeasuredWidth();
            int height = getMeasuredHeight();
            mRippleView.setColor(
                    Utils.getColorAttr(mRippleView.getContext(),
                            android.R.attr.colorAccent).getDefaultColor());
            mRippleView.setOrigin(new PointF(width / 2, height / 2));
            mRippleView.setRadius(Math.max(width, height) * 0.5f);
            mRippleView.setDuration(RIPPLE_ANIMATION_DURATION);
            mRippleView.startRipple();
        }

        super.onLayout(changed, left, top, right, bottom);
    }
}
