/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.annotation.UnsupportedAppUsage;
import android.graphics.Rect;

/**
 * A class that contains utility methods related to numbers.
 *
 * @hide Pending API council approval
 */
public final class MathUtils {
    private static final float DEG_TO_RAD = 3.1415926f / 180.0f;
    private static final float RAD_TO_DEG = 180.0f / 3.1415926f;

    private MathUtils() {
    }

    @UnsupportedAppUsage
    public static float abs(float v) {
        return v > 0 ? v : -v;
    }

    @UnsupportedAppUsage
    public static int constrain(int amount, int low, int high) {
        return amount < low ? low : (amount > high ? high : amount);
    }

    public static long constrain(long amount, long low, long high) {
        return amount < low ? low : (amount > high ? high : amount);
    }

    @UnsupportedAppUsage
    public static float constrain(float amount, float low, float high) {
        return amount < low ? low : (amount > high ? high : amount);
    }

    public static float log(float a) {
        return (float) Math.log(a);
    }

    public static float exp(float a) {
        return (float) Math.exp(a);
    }

    public static float pow(float a, float b) {
        return (float) Math.pow(a, b);
    }

    public static float sqrt(float a) {
        return (float) Math.sqrt(a);
    }

    public static float max(float a, float b) {
        return a > b ? a : b;
    }

    @UnsupportedAppUsage
    public static float max(int a, int b) {
        return a > b ? a : b;
    }

    public static float max(float a, float b, float c) {
        return a > b ? (a > c ? a : c) : (b > c ? b : c);
    }

    public static float max(int a, int b, int c) {
        return a > b ? (a > c ? a : c) : (b > c ? b : c);
    }

    public static float min(float a, float b) {
        return a < b ? a : b;
    }

    public static float min(int a, int b) {
        return a < b ? a : b;
    }

    public static float min(float a, float b, float c) {
        return a < b ? (a < c ? a : c) : (b < c ? b : c);
    }

    public static float min(int a, int b, int c) {
        return a < b ? (a < c ? a : c) : (b < c ? b : c);
    }

    public static float dist(float x1, float y1, float x2, float y2) {
        final float x = (x2 - x1);
        final float y = (y2 - y1);
        return (float) Math.hypot(x, y);
    }

    public static float dist(float x1, float y1, float z1, float x2, float y2, float z2) {
        final float x = (x2 - x1);
        final float y = (y2 - y1);
        final float z = (z2 - z1);
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public static float mag(float a, float b) {
        return (float) Math.hypot(a, b);
    }

    public static float mag(float a, float b, float c) {
        return (float) Math.sqrt(a * a + b * b + c * c);
    }

    public static float sq(float v) {
        return v * v;
    }

    public static float dot(float v1x, float v1y, float v2x, float v2y) {
        return v1x * v2x + v1y * v2y;
    }

    public static float cross(float v1x, float v1y, float v2x, float v2y) {
        return v1x * v2y - v1y * v2x;
    }

    public static float radians(float degrees) {
        return degrees * DEG_TO_RAD;
    }

    public static float degrees(float radians) {
        return radians * RAD_TO_DEG;
    }

    public static float acos(float value) {
        return (float) Math.acos(value);
    }

    public static float asin(float value) {
        return (float) Math.asin(value);
    }

    public static float atan(float value) {
        return (float) Math.atan(value);
    }

    public static float atan2(float a, float b) {
        return (float) Math.atan2(a, b);
    }

    public static float tan(float angle) {
        return (float) Math.tan(angle);
    }

    @UnsupportedAppUsage
    public static float lerp(float start, float stop, float amount) {
        return start + (stop - start) * amount;
    }

    /**
     * Returns the interpolation scalar (s) that satisfies the equation: {@code value = }{@link
     * #lerp}{@code (a, b, s)}
     *
     * <p>If {@code a == b}, then this function will return 0.
     */
    public static float lerpInv(float a, float b, float value) {
        return a != b ? ((value - a) / (b - a)) : 0.0f;
    }

    /** Returns the single argument constrained between [0.0, 1.0]. */
    public static float saturate(float value) {
        return constrain(value, 0.0f, 1.0f);
    }

    /** Returns the saturated (constrained between [0, 1]) result of {@link #lerpInv}. */
    public static float lerpInvSat(float a, float b, float value) {
        return saturate(lerpInv(a, b, value));
    }

    /**
     * Returns an interpolated angle in degrees between a set of start and end
     * angles.
     * <p>
     * Unlike {@link #lerp(float, float, float)}, the direction and distance of
     * travel is determined by the shortest angle between the start and end
     * angles. For example, if the starting angle is 0 and the ending angle is
     * 350, then the interpolated angle will be in the range [0,-10] rather
     * than [0,350].
     *
     * @param start the starting angle in degrees
     * @param end the ending angle in degrees
     * @param amount the position between start and end in the range [0,1]
     *               where 0 is the starting angle and 1 is the ending angle
     * @return the interpolated angle in degrees
     */
    public static float lerpDeg(float start, float end, float amount) {
        final float minAngle = (((end - start) + 180) % 360) - 180;
        return minAngle * amount + start;
    }

    public static float norm(float start, float stop, float value) {
        return (value - start) / (stop - start);
    }

    public static float map(float minStart, float minStop, float maxStart, float maxStop, float value) {
        return maxStart + (maxStop - maxStart) * ((value - minStart) / (minStop - minStart));
    }

    /**
     * Calculates a value in [rangeMin, rangeMax] that maps value in [valueMin, valueMax] to
     * returnVal in [rangeMin, rangeMax].
     * <p>
     * Always returns a constrained value in the range [rangeMin, rangeMax], even if value is
     * outside [valueMin, valueMax].
     * <p>
     * Eg:
     *    constrainedMap(0f, 100f, 0f, 1f, 0.5f) = 50f
     *    constrainedMap(20f, 200f, 10f, 20f, 20f) = 200f
     *    constrainedMap(20f, 200f, 10f, 20f, 50f) = 200f
     *    constrainedMap(10f, 50f, 10f, 20f, 5f) = 10f
     *
     * @param rangeMin minimum of the range that should be returned.
     * @param rangeMax maximum of the range that should be returned.
     * @param valueMin minimum of range to map {@code value} to.
     * @param valueMax maximum of range to map {@code value} to.
     * @param value to map to the range [{@code valueMin}, {@code valueMax}]. Note, can be outside
     *              this range, resulting in a clamped value.
     * @return the mapped value, constrained to [{@code rangeMin}, {@code rangeMax}.
     */
    public static float constrainedMap(
            float rangeMin, float rangeMax, float valueMin, float valueMax, float value) {
        return lerp(rangeMin, rangeMax, lerpInvSat(valueMin, valueMax, value));
    }

    /**
     * Perform Hermite interpolation between two values.
     * Eg:
     *   smoothStep(0, 0.5f, 0.5f) = 1f
     *   smoothStep(0, 0.5f, 0.25f) = 0.5f
     *
     * @param start Left edge.
     * @param end Right edge.
     * @param x A value between {@code start} and {@code end}.
     * @return A number between 0 and 1 representing where {@code x} is in the interpolation.
     */
    public static float smoothStep(float start, float end, float x) {
        return constrain((x - start) / (end - start), 0f, 1f);
    }

    /**
     * Returns the sum of the two parameters, or throws an exception if the resulting sum would
     * cause an overflow or underflow.
     * @throws IllegalArgumentException when overflow or underflow would occur.
     */
    public static int addOrThrow(int a, int b) throws IllegalArgumentException {
        if (b == 0) {
            return a;
        }

        if (b > 0 && a <= (Integer.MAX_VALUE - b)) {
            return a + b;
        }

        if (b < 0 && a >= (Integer.MIN_VALUE - b)) {
            return a + b;
        }
        throw new IllegalArgumentException("Addition overflow: " + a + " + " + b);
    }

    /**
     * Resize a {@link Rect} so one size would be {@param largestSide}.
     *
     * @param outToResize Rectangle that will be resized.
     * @param largestSide Size of the largest side.
     */
    public static void fitRect(Rect outToResize, int largestSide) {
        if (outToResize.isEmpty()) {
            return;
        }
        float maxSize = Math.max(outToResize.width(), outToResize.height());
        outToResize.scale(largestSide / maxSize);
    }
}
