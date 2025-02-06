/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.utilities.easing;

/** The standard interface to Easing functions */
public abstract class Easing {
    int mType;

    /**
     * get the value at point x
     *
     * @param x the position at which to get the slope
     * @return the value at the point
     */
    public abstract float get(float x);

    /**
     * get the slope of the easing function at at x
     *
     * @param x the position at which to get the slope
     * @return the slope
     */
    public abstract float getDiff(float x);

    /**
     * get the type of easing function
     *
     * @return the type of easing function
     */
    public int getType() {
        return mType;
    }

    /** cubic Easing function that accelerates and decelerates */
    public static final int CUBIC_STANDARD = 1;

    /** cubic Easing function that accelerates */
    public static final int CUBIC_ACCELERATE = 2;

    /** cubic Easing function that decelerates */
    public static final int CUBIC_DECELERATE = 3;

    /** cubic Easing function that just linearly interpolates */
    public static final int CUBIC_LINEAR = 4;

    /** cubic Easing function that goes bacwards and then accelerates */
    public static final int CUBIC_ANTICIPATE = 5;

    /** cubic Easing function that overshoots and then goes back */
    public static final int CUBIC_OVERSHOOT = 6;

    /** cubic Easing function that you customize */
    public static final int CUBIC_CUSTOM = 11;

    /** a monotonic spline Easing function that you customize */
    public static final int SPLINE_CUSTOM = 12;

    /** a bouncing Easing function */
    public static final int EASE_OUT_BOUNCE = 13;

    /** a elastic Easing function */
    public static final int EASE_OUT_ELASTIC = 14;

    /**
     * Returns a string representation for the given value. Used during serialization.
     *
     * @param value
     * @return
     */
    public static String getString(int value) {
        switch (value) {
            case CUBIC_STANDARD:
                return "CUBIC_STANDARD";
            case CUBIC_ACCELERATE:
                return "CUBIC_ACCELERATE";
            case CUBIC_DECELERATE:
                return "CUBIC_DECELERATE";
            case CUBIC_LINEAR:
                return "CUBIC_LINEAR";
            case CUBIC_ANTICIPATE:
                return "CUBIC_ANTICIPATE";
            case CUBIC_OVERSHOOT:
                return "CUBIC_OVERSHOOT";
            case CUBIC_CUSTOM:
                return "CUBIC_CUSTOM";
            case SPLINE_CUSTOM:
                return "SPLINE_CUSTOM";
            case EASE_OUT_BOUNCE:
                return "EASE_OUT_BOUNCE";
            case EASE_OUT_ELASTIC:
                return "EASE_OUT_ELASTIC";
            default:
                return "INVALID_CURVE_TYPE[" + value + "]";
        }
    }
}
