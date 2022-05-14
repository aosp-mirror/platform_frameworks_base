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

package com.android.wm.shell.animation;

import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/**
 * Common interpolators used in wm shell library.
 */
public class Interpolators {

    public static final Interpolator LINEAR = new LinearInterpolator();

    /**
     * Interpolator for alpha in animation.
     */
    public static final Interpolator ALPHA_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);

    /**
     * Interpolator for alpha out animation.
     */
    public static final Interpolator ALPHA_OUT = new PathInterpolator(0f, 0f, 0.8f, 1f);

    /**
     * Interpolator for fast out linear in animation.
     */
    public static final Interpolator FAST_OUT_LINEAR_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);

    /**
     * Interpolator for fast out slow in animation.
     */
    public static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    /**
     * Interpolator for linear out slow in animation.
     */
    public static final Interpolator LINEAR_OUT_SLOW_IN = new PathInterpolator(0f, 0f, 0.2f, 1f);

    /**
     * The accelerated emphasized interpolator. Used for hero / emphasized movement of content that
     * is disappearing e.g. when moving off screen.
     */
    public static final Interpolator EMPHASIZED_ACCELERATE = new PathInterpolator(
            0.3f, 0f, 0.8f, 0.15f);

    /**
     * The decelerating emphasized interpolator. Used for hero / emphasized movement of content that
     * is appearing e.g. when coming from off screen
     */
    public static final Interpolator EMPHASIZED_DECELERATE = new PathInterpolator(
            0.05f, 0.7f, 0.1f, 1f);
    /**
     * Interpolator to be used when animating a move based on a click. Pair with enough duration.
     */
    public static final Interpolator TOUCH_RESPONSE = new PathInterpolator(0.3f, 0f, 0.1f, 1f);

    /**
     * Interpolator to be used when animating a panel closing.
     */
    public static final Interpolator PANEL_CLOSE_ACCELERATED =
            new PathInterpolator(0.3f, 0, 0.5f, 1);

    public static final PathInterpolator SLOWDOWN_INTERPOLATOR =
            new PathInterpolator(0.5f, 1f, 0.5f, 1f);

    public static final PathInterpolator DIM_INTERPOLATOR =
            new PathInterpolator(.23f, .87f, .52f, -0.11f);
}
