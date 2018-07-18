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

package com.android.systemui;

import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.BounceInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

import com.android.systemui.statusbar.notification.stack.HeadsUpAppearInterpolator;

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
    public static final Interpolator ALPHA_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);
    public static final Interpolator ALPHA_OUT = new PathInterpolator(0f, 0f, 0.8f, 1f);
    public static final Interpolator LINEAR = new LinearInterpolator();
    public static final Interpolator ACCELERATE = new AccelerateInterpolator();
    public static final Interpolator ACCELERATE_DECELERATE = new AccelerateDecelerateInterpolator();
    public static final Interpolator DECELERATE_QUINT = new DecelerateInterpolator(2.5f);
    public static final Interpolator CUSTOM_40_40 = new PathInterpolator(0.4f, 0f, 0.6f, 1f);
    public static final Interpolator HEADS_UP_APPEAR = new HeadsUpAppearInterpolator();
    public static final Interpolator ICON_OVERSHOT = new PathInterpolator(0.4f, 0f, 0.2f, 1.4f);
    public static final Interpolator PANEL_CLOSE_ACCELERATED
            = new PathInterpolator(0.3f, 0, 0.5f, 1);
    public static final Interpolator BOUNCE = new BounceInterpolator();

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
}
