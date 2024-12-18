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

/**
 * The standard interface to Easing functions
 */
public abstract class Easing {
    int mType;
    /**
     * get the value at point x
     */
    public abstract float get(float x);

    /**
     * get the slope of the easing function at at x
     */
    public abstract float getDiff(float x);

    public int getType() {
        return mType;
    }

    public static final int CUBIC_STANDARD = 1;
    public static final int CUBIC_ACCELERATE = 2;
    public static final int CUBIC_DECELERATE = 3;
    public static final int CUBIC_LINEAR = 4;
    public static final int CUBIC_ANTICIPATE = 5;
    public static final int CUBIC_OVERSHOOT = 6;
    public static final int CUBIC_CUSTOM = 11;
    public static final int SPLINE_CUSTOM = 12;
    public static final int EASE_OUT_BOUNCE = 13;
    public static final int EASE_OUT_ELASTIC = 14;

}
