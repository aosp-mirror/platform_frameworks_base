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

import android.annotation.NonNull;
import android.annotation.Nullable;

/** This performs a spline interpolation in multiple dimensions */
public class MonotonicSpline {
    private static final String TAG = "MonotonicCurveFit";
    private float[] mT;
    private float[] mY;
    private float[] mTangent;
    private boolean mExtrapolate = true;
    float[] mSlopeTemp;

    /**
     * create a collection of curves
     *
     * @param time the point along the curve
     * @param y the parameter at those points
     */
    public MonotonicSpline(@Nullable float[] time, @NonNull float[] y) {
        if (time == null) { // if time  is null assume even 0 to 1;
            time = new float[y.length];
            for (int i = 0; i < time.length; i++) {
                time[i] = i / (float) (time.length - 1);
            }
        }
        mT = time;
        mY = y;
        final int n = time.length;
        final int dim = 1;
        mSlopeTemp = new float[dim];
        float[] slope = new float[n - 1]; // could optimize this out
        float[] tangent = new float[n];
        for (int i = 0; i < n - 1; i++) {
            float dt = time[i + 1] - time[i];
            slope[i] = (y[i + 1] - y[i]) / dt;
            if (i == 0) {
                tangent[i] = slope[i];
            } else {
                tangent[i] = (slope[i - 1] + slope[i]) * 0.5f;
            }
        }
        tangent[n - 1] = slope[n - 2];

        for (int i = 0; i < n - 1; i++) {
            if (slope[i] == 0.) {
                tangent[i] = 0f;
                tangent[i + 1] = 0f;
            } else {
                float a = tangent[i] / slope[i];
                float b = tangent[i + 1] / slope[i];
                float h = (float) Math.hypot(a, b);
                if (h > 9.0) {
                    float t = 3f / h;
                    tangent[i] = t * a * slope[i];
                    tangent[i + 1] = t * b * slope[i];
                }
            }
        }
        mTangent = tangent;
    }

    public float[] getArray() {
        return mY;
    }

    /**
     * Get the position of all curves at time t
     *
     * @param t
     * @return position at t
     */
    public float getPos(float t) {
        final int n = mT.length;
        float v;
        if (mExtrapolate) {
            if (t <= mT[0]) {
                float slopeTemp = getSlope(mT[0]);
                v = mY[0] + (t - mT[0]) * slopeTemp;

                return v;
            }
            if (t >= mT[n - 1]) {
                float slopeTemp = getSlope(mT[n - 1]);
                v = mY[n - 1] + (t - mT[n - 1]) * slopeTemp;

                return v;
            }
        } else {
            if (t <= mT[0]) {
                v = mY[0];

                return v;
            }
            if (t >= mT[n - 1]) {
                v = mY[n - 1];

                return v;
            }
        }

        for (int i = 0; i < n - 1; i++) {
            if (t == mT[i]) {

                v = mY[i];
            }
            if (t < mT[i + 1]) {
                float h = mT[i + 1] - mT[i];
                float x = (t - mT[i]) / h;

                float y1 = mY[i];
                float y2 = mY[i + 1];
                float t1 = mTangent[i];
                float t2 = mTangent[i + 1];
                v = interpolate(h, x, y1, y2, t1, t2);

                return v;
            }
        }
        return 0f;
    }

    /**
     * Get the slope of the curve at position t
     *
     * @param t
     * @return slope at t
     */
    public float getSlope(float t) {
        final int n = mT.length;
        float v = 0;

        if (t <= mT[0]) {
            t = mT[0];
        } else if (t >= mT[n - 1]) {
            t = mT[n - 1];
        }

        for (int i = 0; i < n - 1; i++) {
            if (t <= mT[i + 1]) {
                float h = mT[i + 1] - mT[i];
                float x = (t - mT[i]) / h;
                float y1 = mY[i];
                float y2 = mY[i + 1];
                float t1 = mTangent[i];
                float t2 = mTangent[i + 1];
                v = diff(h, x, y1, y2, t1, t2) / h;
            }
            break;
        }
        return v;
    }

    public float[] getTimePoints() {
        return mT;
    }

    /** Cubic Hermite spline */
    private static float interpolate(float h, float x, float y1, float y2, float t1, float t2) {
        float x2 = x * x;
        float x3 = x2 * x;
        return -2 * x3 * y2
                + 3 * x2 * y2
                + 2 * x3 * y1
                - 3 * x2 * y1
                + y1
                + h * t2 * x3
                + h * t1 * x3
                - h * t2 * x2
                - 2 * h * t1 * x2
                + h * t1 * x;
    }

    /** Cubic Hermite spline slope differentiated */
    private static float diff(float h, float x, float y1, float y2, float t1, float t2) {
        float x2 = x * x;
        return -6 * x2 * y2
                + 6 * x * y2
                + 6 * x2 * y1
                - 6 * x * y1
                + 3 * h * t2 * x2
                + 3 * h * t1 * x2
                - 2 * h * t2 * x
                - 4 * h * t1 * x
                + h * t1;
    }
}
