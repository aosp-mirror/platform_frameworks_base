/*
 * Copyright (C) 2012 The Android Open Source Project
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

/**
 * Performs spline interpolation given a set of control points.
 * @hide
 */
public abstract class Spline {

    /**
     * Interpolates the value of Y = f(X) for given X.
     * Clamps X to the domain of the spline.
     *
     * @param x The X value.
     * @return The interpolated Y = f(X) value.
     */
    public abstract float interpolate(float x);

    /**
     * Creates an appropriate spline based on the properties of the control points.
     *
     * If the control points are monotonic then the resulting spline will preserve that and
     * otherwise optimize for error bounds.
     */
    public static Spline createSpline(float[] x, float[] y) {
        if (!isStrictlyIncreasing(x)) {
            throw new IllegalArgumentException("The control points must all have strictly "
                    + "increasing X values.");
        }

        if (isMonotonic(y)) {
            return createMonotoneCubicSpline(x, y);
        } else {
            return createLinearSpline(x, y);
        }
    }

    /**
     * Creates a monotone cubic spline from a given set of control points.
     *
     * The spline is guaranteed to pass through each control point exactly.
     * Moreover, assuming the control points are monotonic (Y is non-decreasing or
     * non-increasing) then the interpolated values will also be monotonic.
     *
     * This function uses the Fritsch-Carlson method for computing the spline parameters.
     * http://en.wikipedia.org/wiki/Monotone_cubic_interpolation
     *
     * @param x The X component of the control points, strictly increasing.
     * @param y The Y component of the control points, monotonic.
     * @return
     *
     * @throws IllegalArgumentException if the X or Y arrays are null, have
     * different lengths or have fewer than 2 values.
     * @throws IllegalArgumentException if the control points are not monotonic.
     */
    public static Spline createMonotoneCubicSpline(float[] x, float[] y) {
        return new MonotoneCubicSpline(x, y);
    }

    /**
     * Creates a linear spline from a given set of control points.
     *
     * Like a monotone cubic spline, the interpolated curve will be monotonic if the control points
     * are monotonic.
     *
     * @param x The X component of the control points, strictly increasing.
     * @param y The Y component of the control points.
     * @return
     *
     * @throws IllegalArgumentException if the X or Y arrays are null, have
     * different lengths or have fewer than 2 values.
     * @throws IllegalArgumentException if the X components of the control points are not strictly
     * increasing.
     */
    public static Spline createLinearSpline(float[] x, float[] y) {
        return new LinearSpline(x, y);
    }

    private static boolean isStrictlyIncreasing(float[] x) {
        if (x == null || x.length < 2) {
            throw new IllegalArgumentException("There must be at least two control points.");
        }
        float prev = x[0];
        for (int i = 1; i < x.length; i++) {
            float curr = x[i];
            if (curr <= prev) {
                return false;
            }
            prev = curr;
        }
        return true;
    }

    private static boolean isMonotonic(float[] x) {
        if (x == null || x.length < 2) {
            throw new IllegalArgumentException("There must be at least two control points.");
        }
        float prev = x[0];
        for (int i = 1; i < x.length; i++) {
            float curr = x[i];
            if (curr < prev) {
                return false;
            }
            prev = curr;
        }
        return true;
    }

    public static class MonotoneCubicSpline extends Spline {
        private float[] mX;
        private float[] mY;
        private float[] mM;

        public MonotoneCubicSpline(float[] x, float[] y) {
            if (x == null || y == null || x.length != y.length || x.length < 2) {
                throw new IllegalArgumentException("There must be at least two control "
                        + "points and the arrays must be of equal length.");
            }

            final int n = x.length;
            float[] d = new float[n - 1]; // could optimize this out
            float[] m = new float[n];

            // Compute slopes of secant lines between successive points.
            for (int i = 0; i < n - 1; i++) {
                float h = x[i + 1] - x[i];
                if (h <= 0f) {
                    throw new IllegalArgumentException("The control points must all "
                            + "have strictly increasing X values.");
                }
                d[i] = (y[i + 1] - y[i]) / h;
            }

            // Initialize the tangents as the average of the secants.
            m[0] = d[0];
            for (int i = 1; i < n - 1; i++) {
                m[i] = (d[i - 1] + d[i]) * 0.5f;
            }
            m[n - 1] = d[n - 2];

            // Update the tangents to preserve monotonicity.
            for (int i = 0; i < n - 1; i++) {
                if (d[i] == 0f) { // successive Y values are equal
                    m[i] = 0f;
                    m[i + 1] = 0f;
                } else {
                    float a = m[i] / d[i];
                    float b = m[i + 1] / d[i];
                    if (a < 0f || b < 0f) {
                        throw new IllegalArgumentException("The control points must have "
                                + "monotonic Y values.");
                    }
                    float h = FloatMath.hypot(a, b);
                    if (h > 9f) {
                        float t = 3f / h;
                        m[i] = t * a * d[i];
                        m[i + 1] = t * b * d[i];
                    }
                }
            }

            mX = x;
            mY = y;
            mM = m;
        }

        @Override
        public float interpolate(float x) {
            // Handle the boundary cases.
            final int n = mX.length;
            if (Float.isNaN(x)) {
                return x;
            }
            if (x <= mX[0]) {
                return mY[0];
            }
            if (x >= mX[n - 1]) {
                return mY[n - 1];
            }

            // Find the index 'i' of the last point with smaller X.
            // We know this will be within the spline due to the boundary tests.
            int i = 0;
            while (x >= mX[i + 1]) {
                i += 1;
                if (x == mX[i]) {
                    return mY[i];
                }
            }

            // Perform cubic Hermite spline interpolation.
            float h = mX[i + 1] - mX[i];
            float t = (x - mX[i]) / h;
            return (mY[i] * (1 + 2 * t) + h * mM[i] * t) * (1 - t) * (1 - t)
                    + (mY[i + 1] * (3 - 2 * t) + h * mM[i + 1] * (t - 1)) * t * t;
        }

        // For debugging.
        @Override
        public String toString() {
            StringBuilder str = new StringBuilder();
            final int n = mX.length;
            str.append("MonotoneCubicSpline{[");
            for (int i = 0; i < n; i++) {
                if (i != 0) {
                    str.append(", ");
                }
                str.append("(").append(mX[i]);
                str.append(", ").append(mY[i]);
                str.append(": ").append(mM[i]).append(")");
            }
            str.append("]}");
            return str.toString();
        }
    }

    public static class LinearSpline extends Spline {
        private final float[] mX;
        private final float[] mY;
        private final float[] mM;

        public LinearSpline(float[] x, float[] y) {
            if (x == null || y == null || x.length != y.length || x.length < 2) {
                throw new IllegalArgumentException("There must be at least two control "
                        + "points and the arrays must be of equal length.");
            }
            final int N = x.length;
            mM = new float[N-1];
            for (int i = 0; i < N-1; i++) {
                mM[i] = (y[i+1] - y[i]) / (x[i+1] - x[i]);
            }
            mX = x;
            mY = y;
        }

        @Override
        public float interpolate(float x) {
            // Handle the boundary cases.
            final int n = mX.length;
            if (Float.isNaN(x)) {
                return x;
            }
            if (x <= mX[0]) {
                return mY[0];
            }
            if (x >= mX[n - 1]) {
                return mY[n - 1];
            }

            // Find the index 'i' of the last point with smaller X.
            // We know this will be within the spline due to the boundary tests.
            int i = 0;
            while (x >= mX[i + 1]) {
                i += 1;
                if (x == mX[i]) {
                    return mY[i];
                }
            }
            return mY[i] + mM[i] * (x - mX[i]);
        }

        @Override
        public String toString() {
            StringBuilder str = new StringBuilder();
            final int n = mX.length;
            str.append("LinearSpline{[");
            for (int i = 0; i < n; i++) {
                if (i != 0) {
                    str.append(", ");
                }
                str.append("(").append(mX[i]);
                str.append(", ").append(mY[i]);
                if (i < n-1) {
                    str.append(": ").append(mM[i]);
                }
                str.append(")");
            }
            str.append("]}");
            return str.toString();
        }
    }
}
