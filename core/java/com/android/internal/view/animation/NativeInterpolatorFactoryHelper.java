/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.view.animation;

/**
 * Static utility class for constructing native interpolators to keep the
 * JNI simpler
 */
public final class NativeInterpolatorFactoryHelper {
    private NativeInterpolatorFactoryHelper() {}

    public static native long createAccelerateDecelerateInterpolator();
    public static native long createAccelerateInterpolator(float factor);
    public static native long createAnticipateInterpolator(float tension);
    public static native long createAnticipateOvershootInterpolator(float tension);
    public static native long createBounceInterpolator();
    public static native long createCycleInterpolator(float cycles);
    public static native long createDecelerateInterpolator(float factor);
    public static native long createLinearInterpolator();
    public static native long createOvershootInterpolator(float tension);
    public static native long createPathInterpolator(float[] x, float[] y);
    public static native long createLutInterpolator(float[] values);
}
