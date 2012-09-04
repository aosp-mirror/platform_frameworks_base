/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.util;

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

/**
 * Delegate implementing the native methods of android.util.FloatMath
 *
 * Through the layoutlib_create tool, the original native methods of FloatMath have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * Because it's a stateless class to start with, there's no need to keep a {@link DelegateManager}
 * around to map int to instance of the delegate.
 *
 */
/*package*/ final class FloatMath_Delegate {

    /** Prevents instantiation. */
    private FloatMath_Delegate() {}

    /**
     * Returns the float conversion of the most positive (i.e. closest to
     * positive infinity) integer value which is less than the argument.
     *
     * @param value to be converted
     * @return the floor of value
     */
    @LayoutlibDelegate
    /*package*/ static float floor(float value) {
        return (float)Math.floor(value);
    }

    /**
     * Returns the float conversion of the most negative (i.e. closest to
     * negative infinity) integer value which is greater than the argument.
     *
     * @param value to be converted
     * @return the ceiling of value
     */
    @LayoutlibDelegate
    /*package*/ static float ceil(float value) {
        return (float)Math.ceil(value);
    }

    /**
     * Returns the closest float approximation of the sine of the argument.
     *
     * @param angle to compute the cosine of, in radians
     * @return the sine of angle
     */
    @LayoutlibDelegate
    /*package*/ static  float sin(float angle) {
        return (float)Math.sin(angle);
    }

    /**
     * Returns the closest float approximation of the cosine of the argument.
     *
     * @param angle to compute the cosine of, in radians
     * @return the cosine of angle
     */
    @LayoutlibDelegate
    /*package*/ static float cos(float angle) {
        return (float)Math.cos(angle);
    }

    /**
     * Returns the closest float approximation of the square root of the
     * argument.
     *
     * @param value to compute sqrt of
     * @return the square root of value
     */
    @LayoutlibDelegate
    /*package*/ static float sqrt(float value) {
        return (float)Math.sqrt(value);
    }

    /**
     * Returns the closest float approximation of the raising "e" to the power
     * of the argument.
     *
     * @param value to compute the exponential of
     * @return the exponential of value
     */
    @LayoutlibDelegate
    /*package*/ static float exp(float value) {
        return (float)Math.exp(value);
    }

    /**
     * Returns the closest float approximation of the result of raising {@code
     * x} to the power of {@code y}.
     *
     * @param x the base of the operation.
     * @param y the exponent of the operation.
     * @return {@code x} to the power of {@code y}.
     */
    @LayoutlibDelegate
    /*package*/ static float pow(float x, float y) {
        return (float)Math.pow(x, y);
    }

    /**
     * Returns {@code sqrt(}<i>{@code x}</i><sup>{@code 2}</sup>{@code +} <i>
     * {@code y}</i><sup>{@code 2}</sup>{@code )}.
     *
     * @param x a float number
     * @param y a float number
     * @return the hypotenuse
     */
    @LayoutlibDelegate
    /*package*/ static float hypot(float x, float y) {
        return (float)Math.sqrt(x*x + y*y);
    }
}
