/*
 * Copyright (C) 2016 The CyanogenMod Project
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
package com.android.internal.util.custom;

public final class MathUtils {

    /**
     * Given a range of values which change continuously in a non-linear way,
     * we can map back and forth to a linear scale using some quadratic equations.
     *
     * The linear scale ranges from 0 -> 1. This method will calculate the
     * coefficients needed to solve the conversion functions in the next two methods.
     *
     * lower = actual value when linear value = 0
     * mid = actual value when linear value = .5
     * upper actual value when linear value = 1
     *
     * @param lower
     * @param mid
     * @param upper
     * @return array of coefficients
     */
    public static double[] powerCurve(double lower, double mid, double upper) {
        final double[] curve = new double[3];
        curve[0] = ((lower * upper) - (mid * mid)) / (lower - (2 * mid) + upper);
        curve[1] = Math.pow((mid - lower), 2) / (lower - (2 * mid) + upper);
        curve[2] = 2 * Math.log((upper - mid) / (mid - lower));
        return curve;
    }

    /**
     * Map a value on a power curve to a linear value
     *
     * @param curve obtained from powerCurve()
     * @param value to convert to linear scale
     * @return linear value from 0 -> 1
     */
    public static double powerCurveToLinear(final double[] curve, double value) {
        return Math.log((value - curve[0]) / curve[1]) / curve[2];
    }

    /**
     * Map a value on a linear scale to a value on a power curve
     *
     * @param curve obtained from powerCurve()
     * @param value from 0 -> 1 to map onto power curve
     * @return actual value on the given curve
     */
    public static double linearToPowerCurve(final double[] curve, double value) {
        return curve[0] + curve[1] * Math.exp(curve[2] * value);
    }


}
