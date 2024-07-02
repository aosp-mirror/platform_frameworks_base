/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.navigationbar.gestural;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.annotation.ColorInt;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.animation.Interpolator;

import com.android.app.animation.Interpolators;
import com.android.settingslib.Utils;
import com.android.systemui.navigationbar.views.buttons.ButtonInterface;
import com.android.systemui.res.R;

public class NavigationHandle extends View implements ButtonInterface {

    protected final Paint mPaint = new Paint();
    private @ColorInt final int mLightColor;
    private @ColorInt final int mDarkColor;
    protected final float mRadius;
    protected final float mBottom;
    private final float mAdditionalWidthForAnimation;
    private final float mAdditionalHeightForAnimation;
    private final float mShrinkWidthForAnimation;
    private boolean mRequiresInvalidate;
    private boolean mShrink;

    private ObjectAnimator mPulseAnimator = null;
    private float mPulseAnimationProgress;

    private static final FloatProperty<NavigationHandle> PULSE_ANIMATION_PROGRESS =
            new FloatProperty<>("pulseAnimationProgress") {
                @Override
                public Float get(NavigationHandle controller) {
                    return controller.getPulseAnimationProgress();
                }

                @Override
                public void setValue(NavigationHandle controller, float progress) {
                    controller.setPulseAnimationProgress(progress);
                }
            };

    public NavigationHandle(Context context) {
        this(context, null);
    }

    public NavigationHandle(Context context, AttributeSet attr) {
        super(context, attr);
        final Resources res = context.getResources();
        mRadius = res.getDimension(R.dimen.navigation_handle_radius);
        mBottom = res.getDimension(R.dimen.navigation_handle_bottom);
        mAdditionalWidthForAnimation =
                res.getDimension(R.dimen.navigation_home_handle_additional_width_for_animation);
        mAdditionalHeightForAnimation =
                res.getDimension(R.dimen.navigation_home_handle_additional_height_for_animation);
        mShrinkWidthForAnimation =
                res.getDimension(R.dimen.navigation_home_handle_shrink_width_for_animation);

        final int dualToneDarkTheme = Utils.getThemeAttr(context, R.attr.darkIconTheme);
        final int dualToneLightTheme = Utils.getThemeAttr(context, R.attr.lightIconTheme);
        Context lightContext = new ContextThemeWrapper(context, dualToneLightTheme);
        Context darkContext = new ContextThemeWrapper(context, dualToneDarkTheme);
        mLightColor = Utils.getColorAttrDefaultColor(lightContext, R.attr.homeHandleColor);
        mDarkColor = Utils.getColorAttrDefaultColor(darkContext, R.attr.homeHandleColor);
        mPaint.setAntiAlias(true);
        setFocusable(false);
    }

    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (alpha > 0f && mRequiresInvalidate) {
            mRequiresInvalidate = false;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw that bar
        int navHeight = getHeight();
        float additionalHeight;
        float additionalWidth;
        if (mShrink) {
            additionalHeight = 0;
            additionalWidth = -mShrinkWidthForAnimation * mPulseAnimationProgress;
        } else {
            additionalHeight = mAdditionalHeightForAnimation * mPulseAnimationProgress;
            additionalWidth = mAdditionalWidthForAnimation * mPulseAnimationProgress;
        }

        float height = mRadius * 2 + additionalHeight;
        float width = getWidth() + additionalWidth;
        float x = -additionalWidth;
        float y = navHeight - mBottom - height + (additionalHeight / 2);
        float adjustedRadius = height / 2;
        canvas.drawRoundRect(x, y, width, y + height, adjustedRadius, adjustedRadius, mPaint);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {}

    @Override
    public void abortCurrentGesture() {}

    @Override
    public void setVertical(boolean vertical) {}

    @Override
    public void setDarkIntensity(float intensity) {
        int color = (int) ArgbEvaluator.getInstance().evaluate(intensity, mLightColor, mDarkColor);
        if (mPaint.getColor() != color) {
            mPaint.setColor(color);
            if (getVisibility() == VISIBLE && getAlpha() > 0) {
                invalidate();
            } else {
                // If we are currently invisible, then invalidate when we are next made visible
                mRequiresInvalidate = true;
            }
        }
    }

    @Override
    public void setDelayTouchFeedback(boolean shouldDelay) {}

    @Override
    public void animateLongPress(boolean isTouchDown, boolean shrink, long durationMs) {
        if (mPulseAnimator != null) {
            mPulseAnimator.cancel();
        }

        mShrink = shrink;
        Interpolator interpolator;
        if (shrink) {
            interpolator = Interpolators.LEGACY_DECELERATE;
        } else {
            if (isTouchDown) {
                // For now we animate the navbar expanding and contracting so that the navbar is
                // the original size by the end of {@code duration}. This is because a screenshot
                // is taken at that point and we don't want to capture the larger navbar.
                // TODO(b/306400785): Determine a way to exclude navbar from the screenshot.

                // Fraction of the touch down animation to expand; remaining is used to contract
                // again.
                float expandFraction = 0.9f;
                interpolator = t -> t <= expandFraction
                        ? Interpolators.clampToProgress(Interpolators.LEGACY, t, 0, expandFraction)
                        : 1 - Interpolators.clampToProgress(
                                Interpolators.LINEAR, t, expandFraction, 1);
            } else {
                interpolator = Interpolators.LEGACY_DECELERATE;
            }
        }

        mPulseAnimator =
                ObjectAnimator.ofFloat(this, PULSE_ANIMATION_PROGRESS, isTouchDown ? 1 : 0);
        mPulseAnimator.setDuration(durationMs).setInterpolator(interpolator);
        mPulseAnimator.start();
    }

    private void setPulseAnimationProgress(float pulseAnimationProgress) {
        mPulseAnimationProgress = pulseAnimationProgress;
        invalidate();
    }

    private float getPulseAnimationProgress() {
        return mPulseAnimationProgress;
    }
}
