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

package com.android.systemui.recents.misc;

import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

/**
 * Represents a 2d curve that is parameterized along the arc length of the curve (p), and allows the
 * conversions of x->p and p->x.
 */
public class ParametricCurve {

    private static final boolean DEBUG = false;
    private static final String TAG = "ParametricCurve";

    private static final int PrecisionSteps = 250;

    /**
     * A 2d function, representing the curve.
     */
    public interface CurveFunction {
        float f(float x);
        float invF(float y);
    }

    /**
     * A function that returns a value given a parametric value.
     */
    public interface ParametricCurveFunction {
        float f(float p);
    }

    float[] xp;
    float[] px;
    float mLength;

    CurveFunction mFn;
    ParametricCurveFunction mScaleFn;

    public ParametricCurve(CurveFunction fn, ParametricCurveFunction scaleFn) {
        long t1;
        if (DEBUG) {
            t1 = SystemClock.currentThreadTimeMicro();
            Log.d(TAG, "initializeCurve");
        }
        mFn = fn;
        mScaleFn = scaleFn;
        xp = new float[PrecisionSteps + 1];
        px = new float[PrecisionSteps + 1];

        // Approximate f(x)
        float[] fx = new float[PrecisionSteps + 1];
        float step = 1f / PrecisionSteps;
        float x = 0;
        for (int xStep = 0; xStep <= PrecisionSteps; xStep++) {
            fx[xStep] = fn.f(x);
            x += step;
        }
        // Calculate the arc length for x:1->0
        float pLength = 0;
        float[] dx = new float[PrecisionSteps + 1];
        dx[0] = 0;
        for (int xStep = 1; xStep < PrecisionSteps; xStep++) {
            dx[xStep] = (float) Math.hypot(fx[xStep] - fx[xStep - 1], step);
            pLength += dx[xStep];
        }
        mLength = pLength;
        // Approximate p(x), a function of cumulative progress with x, normalized to 0..1
        float p = 0;
        px[0] = 0f;
        px[PrecisionSteps] = 1f;
        if (DEBUG) {
            Log.d(TAG, "p[0]=0");
            Log.d(TAG, "p[" + PrecisionSteps + "]=1");
        }
        for (int xStep = 1; xStep < PrecisionSteps; xStep++) {
            p += Math.abs(dx[xStep] / pLength);
            px[xStep] = p;
            if (DEBUG) {
                Log.d(TAG, "p[" + xStep + "]=" + p);
            }
        }
        // Given p(x), calculate the inverse function x(p). This assumes that x(p) is also a valid
        // function.
        int xStep = 0;
        p = 0;
        xp[0] = 0f;
        xp[PrecisionSteps] = 1f;
        if (DEBUG) {
            Log.d(TAG, "x[0]=0");
            Log.d(TAG, "x[" + PrecisionSteps + "]=1");
        }
        for (int pStep = 0; pStep < PrecisionSteps; pStep++) {
            // Walk forward in px and find the x where px <= p && p < px+1
            while (xStep < PrecisionSteps) {
                if (px[xStep] > p) break;
                xStep++;
            }
            // Now, px[xStep-1] <= p < px[xStep]
            if (xStep == 0) {
                xp[pStep] = 0;
            } else {
                // Find x such that proportionally, x is correct
                float fraction = (p - px[xStep - 1]) / (px[xStep] - px[xStep - 1]);
                x = (xStep - 1 + fraction) * step;
                xp[pStep] = x;
            }
            if (DEBUG) {
                Log.d(TAG, "x[" + pStep + "]=" + xp[pStep]);
            }
            p += step;
        }
        if (DEBUG) {
            Log.d(TAG, "\t1t: " + (SystemClock.currentThreadTimeMicro() - t1) + "microsecs");
        }
    }

    /**
     * Converts from the progress along the arc-length of the curve to a coordinate within the
     * bounds.  Note, p=0 represents the top of the bounds, and p=1 represents the bottom.
     */
    public int pToX(float p, Rect bounds) {
        int top = bounds.top;
        int height = bounds.height();

        if (p <= 0f) return top;
        if (p >= 1f) return top + (int) (p * height);

        float pIndex = p * PrecisionSteps;
        int pFloorIndex = (int) Math.floor(pIndex);
        int pCeilIndex = (int) Math.ceil(pIndex);
        float x = xp[pFloorIndex];
        if (pFloorIndex < PrecisionSteps && (pCeilIndex != pFloorIndex)) {
            // Interpolate between the two precalculated positions
            x += (xp[pCeilIndex] - xp[pFloorIndex]) * (pIndex - pFloorIndex);
        }
        return top + (int) (x * height);
    }

    /**
     * Converts from the progress along the arc-length of the curve to a scale.
     */
    public float pToScale(float p) {
        return mScaleFn.f(p);
    }

    /**
     * Converts from a bounds coordinate to the progress along the arc-length of the curve.
     * Note, p=0 represents the top of the bounds, and p=1 represents the bottom.
     */
    public float xToP(int x, Rect bounds) {
        int top = bounds.top;

        float xf = (float) (x - top) / bounds.height();
        if (xf <= 0f) return 0f;
        if (xf >= 1f) return xf;

        float xIndex = xf * PrecisionSteps;
        int xFloorIndex = (int) Math.floor(xIndex);
        int xCeilIndex = (int) Math.ceil(xIndex);
        float p = px[xFloorIndex];
        if (xFloorIndex < PrecisionSteps && (xCeilIndex != xFloorIndex)) {
            // Interpolate between the two precalculated positions
            p += (px[xCeilIndex] - px[xFloorIndex]) * (xIndex - xFloorIndex);
        }
        return p;
    }

    /**
     * Computes the progress offset from the bottom of the curve (p=1) such that the given height
     * is visible when scaled at the that progress.
     */
    public float computePOffsetForScaledHeight(int height, Rect bounds) {
        int top = bounds.top;
        int bottom = bounds.bottom;

        if (bounds.height() == 0) {
            return 0;
        }

        // Find the next p(x) such that (bottom-x) == scale(p(x))*height
        int minX = top;
        int maxX = bottom;
        long t1;
        if (DEBUG) {
            t1 = SystemClock.currentThreadTimeMicro();
            Log.d(TAG, "computePOffsetForScaledHeight: " + height);
        }
        while (minX <= maxX) {
            int midX = minX + ((maxX - minX) / 2);
            float pMidX = xToP(midX, bounds);
            float scaleMidX = mScaleFn.f(pMidX);
            int scaledHeight = (int) (scaleMidX * height);
            if ((bottom - midX) < scaledHeight) {
                maxX = midX - 1;
            } else if ((bottom - midX) > scaledHeight) {
                minX = midX + 1;
            } else {
                if (DEBUG) {
                    Log.d(TAG, "\t1t: " + (SystemClock.currentThreadTimeMicro() - t1) + "microsecs");
                }
                return 1f - pMidX;
            }
        }
        if (DEBUG) {
            Log.d(TAG, "\t2t: " + (SystemClock.currentThreadTimeMicro() - t1) + "microsecs");
        }
        return 1f - xToP(maxX, bounds);
    }

    /**
     * Computes the progress offset from the bottom of the curve (p=1) that allows the given height,
     * unscaled at the progress, will be visible.
     */
    public float computePOffsetForHeight(int height, Rect bounds) {
        int top = bounds.top;
        int bottom = bounds.bottom;

        if (bounds.height() == 0) {
            return 0;
        }

        // Find the next p(x) such that (bottom-x) == height
        int minX = top;
        int maxX = bottom;
        long t1;
        if (DEBUG) {
            t1 = SystemClock.currentThreadTimeMicro();
            Log.d(TAG, "computePOffsetForHeight: " + height);
        }
        while (minX <= maxX) {
            int midX = minX + ((maxX - minX) / 2);
            if ((bottom - midX) < height) {
                maxX = midX - 1;
            } else if ((bottom - midX) > height) {
                minX = midX + 1;
            } else {
                if (DEBUG) {
                    Log.d(TAG, "\t1t: " + (SystemClock.currentThreadTimeMicro() - t1) + "microsecs");
                }
                return 1f - xToP(midX, bounds);
            }
        }
        if (DEBUG) {
            Log.d(TAG, "\t2t: " + (SystemClock.currentThreadTimeMicro() - t1) + "microsecs");
        }
        return 1f - xToP(maxX, bounds);
    }

    /**
     * Returns the length of this curve.
     */
    public float getArcLength() {
        return mLength;
    }
}
