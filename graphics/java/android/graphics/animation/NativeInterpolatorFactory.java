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

package android.graphics.animation;

import android.animation.TimeInterpolator;

/**
 * Static utility class for constructing native interpolators to keep the
 * JNI simpler
 *
 * @hide
 */
public final class NativeInterpolatorFactory {
    private NativeInterpolatorFactory() {}

    /**
     * Create a native interpolator from the provided param generating a LUT variant if a native
     * implementation does not exist.
     */
    public static long createNativeInterpolator(TimeInterpolator interpolator, long
            duration) {
        if (interpolator == null) {
            return createLinearInterpolator();
        } else if (RenderNodeAnimator.isNativeInterpolator(interpolator)) {
            return ((NativeInterpolator) interpolator).createNativeInterpolator();
        } else {
            return FallbackLUTInterpolator.createNativeInterpolator(interpolator, duration);
        }
    }

    /** Creates a specialized native interpolator for Accelerate/Decelerate */
    public static native long createAccelerateDecelerateInterpolator();
    /** Creates a specialized native interpolator for Accelerate */
    public static native long createAccelerateInterpolator(float factor);
    /** Creates a specialized native interpolator for Anticipate */
    public static native long createAnticipateInterpolator(float tension);
    /** Creates a specialized native interpolator for Anticipate with Overshoot */
    public static native long createAnticipateOvershootInterpolator(float tension);
    /** Creates a specialized native interpolator for Bounce */
    public static native long createBounceInterpolator();
    /** Creates a specialized native interpolator for Cycle */
    public static native long createCycleInterpolator(float cycles);
    /** Creates a specialized native interpolator for Decelerate */
    public static native long createDecelerateInterpolator(float factor);
    /** Creates a specialized native interpolator for Linear interpolation */
    public static native long createLinearInterpolator();
    /** Creates a specialized native interpolator for Overshoot */
    public static native long createOvershootInterpolator(float tension);
    /** Creates a specialized native interpolator for along traveling along a Path */
    public static native long createPathInterpolator(float[] x, float[] y);
    /** Creates a specialized native interpolator for LUT */
    public static native long createLutInterpolator(float[] values);
}
