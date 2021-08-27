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
 * limitations under the License.
 */

package com.android.systemui.animation;

import android.util.MathUtils;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.BounceInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/**
 * Utility class to receive interpolators from
 */
public class Interpolators {
    public static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    /**
     * Like {@link #FAST_OUT_SLOW_IN}, but used in case the animation is played in reverse (i.e. t
     * goes from 1 to 0 instead of 0 to 1).
     */
    public static final Interpolator FAST_OUT_SLOW_IN_REVERSE =
            new PathInterpolator(0.8f, 0f, 0.6f, 1f);
    public static final Interpolator FAST_OUT_LINEAR_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);
    public static final Interpolator LINEAR_OUT_SLOW_IN = new PathInterpolator(0f, 0f, 0.2f, 1f);
    public static final Interpolator SLOW_OUT_LINEAR_IN = new PathInterpolator(0.8f, 0f, 1f, 1f);
    public static final Interpolator ALPHA_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);
    public static final Interpolator ALPHA_OUT = new PathInterpolator(0f, 0f, 0.8f, 1f);
    public static final Interpolator LINEAR = new LinearInterpolator();
    public static final Interpolator ACCELERATE = new AccelerateInterpolator();
    public static final Interpolator ACCELERATE_DECELERATE = new AccelerateDecelerateInterpolator();
    public static final Interpolator DECELERATE_QUINT = new DecelerateInterpolator(2.5f);
    public static final Interpolator CUSTOM_40_40 = new PathInterpolator(0.4f, 0f, 0.6f, 1f);
    public static final Interpolator ICON_OVERSHOT = new PathInterpolator(0.4f, 0f, 0.2f, 1.4f);
    public static final Interpolator ICON_OVERSHOT_LESS = new PathInterpolator(0.4f, 0f, 0.2f,
            1.1f);
    public static final Interpolator PANEL_CLOSE_ACCELERATED = new PathInterpolator(0.3f, 0, 0.5f,
            1);
    public static final Interpolator BOUNCE = new BounceInterpolator();
    /**
     * For state transitions on the control panel that lives in GlobalActions.
     */
    public static final Interpolator CONTROL_STATE = new PathInterpolator(0.4f, 0f, 0.2f,
            1.0f);

    /**
     * Interpolator to be used when animating a move based on a click. Pair with enough duration.
     */
    public static final Interpolator TOUCH_RESPONSE =
            new PathInterpolator(0.3f, 0f, 0.1f, 1f);

    /**
     * Like {@link #TOUCH_RESPONSE}, but used in case the animation is played in reverse (i.e. t
     * goes from 1 to 0 instead of 0 to 1).
     */
    public static final Interpolator TOUCH_RESPONSE_REVERSE =
            new PathInterpolator(0.9f, 0f, 0.7f, 1f);

    /**
     * Calculate the amount of overshoot using an exponential falloff function with desired
     * properties, where the overshoot smoothly transitions at the 1.0f boundary into the
     * overshoot, retaining its acceleration.
     *
     * @param progress a progress value going from 0 to 1
     * @param overshootAmount the amount > 0 of overshoot desired. A value of 0.1 means the max
     *                        value of the overall progress will be at 1.1.
     * @param overshootStart the point in (0,1] where the result should reach 1
     * @return the interpolated overshoot
     */
    public static float getOvershootInterpolation(float progress, float overshootAmount,
            float overshootStart) {
        if (overshootAmount == 0.0f || overshootStart == 0.0f) {
            throw new IllegalArgumentException("Invalid values for overshoot");
        }
        float b = MathUtils.log((overshootAmount + 1) / (overshootAmount)) / overshootStart;
        return MathUtils.max(0.0f,
                (float) (1.0f - Math.exp(-b * progress)) * (overshootAmount + 1.0f));
    }

    /**
     * Similar to {@link #getOvershootInterpolation(float, float, float)} but the overshoot
     * starts immediately here, instead of first having a section of non-overshooting
     *
     * @param progress a progress value going from 0 to 1
     */
    public static float getOvershootInterpolation(float progress) {
        return MathUtils.max(0.0f, (float) (1.0f - Math.exp(-4 * progress)));
    }

    /**
     * Interpolate alpha for notifications background scrim during shade expansion.
     * @param fraction Shade expansion fraction
     * @param forNotification If we want the alpha of the notification shade or the scrim.
     */
    public static float getNotificationScrimAlpha(float fraction, boolean forNotification) {
        if (forNotification) {
            fraction = MathUtils.constrainedMap(0f, 1f, 0.3f, 1f, fraction);
        } else {
            fraction = MathUtils.constrainedMap(0f, 1f, 0f, 0.5f, fraction);
        }
        fraction = fraction * 1.2f - 0.2f;
        if (fraction <= 0) {
            return 0;
        } else {
            final float oneMinusFrac = 1f - fraction;
            return (float) (1f - 0.5f * (1f - Math.cos(3.14159f * oneMinusFrac * oneMinusFrac)));
        }
    }
}
