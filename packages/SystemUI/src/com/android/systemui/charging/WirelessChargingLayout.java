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
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.surfaceeffects.ripple.RippleAnimationConfig;
import com.android.systemui.surfaceeffects.ripple.RippleShader.RippleShape;
import com.android.systemui.surfaceeffects.ripple.RippleView;

import java.text.NumberFormat;

/**
 * @hide
 */
final class WirelessChargingLayout extends FrameLayout {
    private static final long CIRCLE_RIPPLE_ANIMATION_DURATION = 1500;
    private static final long ROUNDED_BOX_RIPPLE_ANIMATION_DURATION = 1750;
    private static final int SCRIM_COLOR = 0x4C000000;
    private static final int SCRIM_FADE_DURATION = 300;
    private RippleView mRippleView;

    WirelessChargingLayout(Context context, int transmittingBatteryLevel, int batteryLevel,
            boolean isDozing, RippleShape rippleShape) {
        super(context);
        init(context, null, transmittingBatteryLevel, batteryLevel, isDozing, rippleShape);
    }

    private WirelessChargingLayout(Context context) {
        super(context);
        init(context, null, /* isDozing= */ false, RippleShape.CIRCLE);
    }

    private WirelessChargingLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, /* isDozing= */false, RippleShape.CIRCLE);
    }

    private void init(Context c, AttributeSet attrs, boolean isDozing, RippleShape rippleShape) {
        init(c, attrs, -1, -1, isDozing, rippleShape);
    }

    private void init(Context context, AttributeSet attrs, int transmittingBatteryLevel,
            int batteryLevel, boolean isDozing, RippleShape rippleShape) {
        final boolean showTransmittingBatteryLevel =
                (transmittingBatteryLevel != WirelessChargingAnimation.UNKNOWN_BATTERY_LEVEL);

        // set style based on background
        int style = R.style.ChargingAnim_WallpaperBackground;
        if (isDozing) {
            style = R.style.ChargingAnim_DarkBackground;
        }

        inflate(new ContextThemeWrapper(context, style), R.layout.wireless_charging_layout, this);

        // amount of battery:
        final TextView percentage = findViewById(R.id.wireless_charging_percentage);

        if (batteryLevel != WirelessChargingAnimation.UNKNOWN_BATTERY_LEVEL) {
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

        ValueAnimator scrimFadeInAnimator = ObjectAnimator.ofArgb(this,
                "backgroundColor", Color.TRANSPARENT, SCRIM_COLOR);
        scrimFadeInAnimator.setDuration(SCRIM_FADE_DURATION);
        scrimFadeInAnimator.setInterpolator(Interpolators.LINEAR);
        ValueAnimator scrimFadeOutAnimator = ObjectAnimator.ofArgb(this,
                "backgroundColor", SCRIM_COLOR, Color.TRANSPARENT);
        scrimFadeOutAnimator.setDuration(SCRIM_FADE_DURATION);
        scrimFadeOutAnimator.setInterpolator(Interpolators.LINEAR);
        scrimFadeOutAnimator.setStartDelay((rippleShape == RippleShape.CIRCLE
                ? CIRCLE_RIPPLE_ANIMATION_DURATION : ROUNDED_BOX_RIPPLE_ANIMATION_DURATION)
                - SCRIM_FADE_DURATION);
        AnimatorSet animatorSetScrim = new AnimatorSet();
        animatorSetScrim.playTogether(scrimFadeInAnimator, scrimFadeOutAnimator);
        animatorSetScrim.start();

        mRippleView = findViewById(R.id.wireless_charging_ripple);
        mRippleView.setupShader(rippleShape);
        int color = Utils.getColorAttr(mRippleView.getContext(),
                android.R.attr.colorAccent).getDefaultColor();
        if (mRippleView.getRippleShape() == RippleShape.ROUNDED_BOX) {
            mRippleView.setDuration(ROUNDED_BOX_RIPPLE_ANIMATION_DURATION);
            mRippleView.setSparkleStrength(0.22f);
            mRippleView.setColor(color, 28);
        } else {
            mRippleView.setDuration(CIRCLE_RIPPLE_ANIMATION_DURATION);
            mRippleView.setColor(color, RippleAnimationConfig.RIPPLE_DEFAULT_ALPHA);
        }

        OnAttachStateChangeListener listener = new OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                mRippleView.startRipple();
                mRippleView.removeOnAttachStateChangeListener(this);
            }

            @Override
            public void onViewDetachedFromWindow(View view) {}
        };
        mRippleView.addOnAttachStateChangeListener(listener);

        if (!showTransmittingBatteryLevel) {
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

        animatorSet.start();
        animatorSetTransmitting.start();
        animatorSetIcon.start();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mRippleView != null) {
            int width = getMeasuredWidth();
            int height = getMeasuredHeight();
            mRippleView.setCenter(width * 0.5f, height * 0.5f);
            if (mRippleView.getRippleShape() == RippleShape.ROUNDED_BOX) {
                // Those magic numbers are introduced for visual polish. This aspect ratio maps with
                // the tablet's docking station.
                mRippleView.setMaxSize(width * 1.36f, height * 1.46f);
            } else {
                float maxSize = Math.max(width, height);
                mRippleView.setMaxSize(maxSize, maxSize);
            }
        }

        super.onLayout(changed, left, top, right, bottom);
    }
}
