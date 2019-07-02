/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.graphics.Path;
import android.view.animation.BaseInterpolator;
import android.view.animation.Interpolator;

/**
 * An interpolator that can traverse a Path. The x coordinate along the <code>Path</code>
 * is the input value and the output is the y coordinate of the line at that point.
 * This means that the Path must conform to a function <code>y = f(x)</code>.
 *
 * <p>The <code>Path</code> must not have gaps in the x direction and must not
 * loop back on itself such that there can be two points sharing the same x coordinate.
 * It is alright to have a disjoint line in the vertical direction:</p>
 * <p><blockquote><pre>
 *     Path path = new Path();
 *     path.lineTo(0.25f, 0.25f);
 *     path.moveTo(0.25f, 0.5f);
 *     path.lineTo(1f, 1f);
 * </pre></blockquote></p>
 */
public class FreePathInterpolator extends BaseInterpolator {

    // This governs how accurate the approximation of the Path is.
    private static final float PRECISION = 0.002f;

    private float[] mX;
    private float[] mY;
    private float mArcLength;

    /**
     * Create an interpolator for an arbitrary <code>Path</code>.
     *
     * @param path The <code>Path</code> to use to make the line representing the interpolator.
     */
    public FreePathInterpolator(Path path) {
        initPath(path);
    }

    private void initPath(Path path) {
        float[] pointComponents = path.approximate(PRECISION);

        int numPoints = pointComponents.length / 3;

        mX = new float[numPoints];
        mY = new float[numPoints];
        mArcLength = 0;
        float prevX = 0;
        float prevY = 0;
        float prevFraction = 0;
        int componentIndex = 0;
        for (int i = 0; i < numPoints; i++) {
            float fraction = pointComponents[componentIndex++];
            float x = pointComponents[componentIndex++];
            float y = pointComponents[componentIndex++];
            if (fraction == prevFraction && x != prevX) {
                throw new IllegalArgumentException(
                        "The Path cannot have discontinuity in the X axis.");
            }
            if (x < prevX) {
                throw new IllegalArgumentException("The Path cannot loop back on itself.");
            }
            mX[i] = x;
            mY[i] = y;
            mArcLength += Math.hypot(x - prevX, y - prevY);
            prevX = x;
            prevY = y;
            prevFraction = fraction;
        }
    }

    /**
     * Using the line in the Path in this interpolator that can be described as
     * <code>y = f(x)</code>, finds the y coordinate of the line given <code>t</code>
     * as the x coordinate.
     *
     * @param t Treated as the x coordinate along the line.
     * @return The y coordinate of the Path along the line where x = <code>t</code>.
     * @see Interpolator#getInterpolation(float)
     */
    @Override
    public float getInterpolation(float t) {
        int startIndex = 0;
        int endIndex = mX.length - 1;

        // Return early if out of bounds
        if (t <= 0) {
            return mY[startIndex];
        } else if (t >= 1) {
            return mY[endIndex];
        }

        // Do a binary search for the correct x to interpolate between.
        while (endIndex - startIndex > 1) {
            int midIndex = (startIndex + endIndex) / 2;
            if (t < mX[midIndex]) {
                endIndex = midIndex;
            } else {
                startIndex = midIndex;
            }
        }

        float xRange = mX[endIndex] - mX[startIndex];
        if (xRange == 0) {
            return mY[startIndex];
        }

        float tInRange = t - mX[startIndex];
        float fraction = tInRange / xRange;

        float startY = mY[startIndex];
        float endY = mY[endIndex];
        return startY + (fraction * (endY - startY));
    }

    /**
     * Finds the x that provides the given <code>y = f(x)</code>.
     *
     * @param y a value from (0,1) that is in this path.
     */
    public float getX(float y) {
        int startIndex = 0;
        int endIndex = mY.length - 1;

        // Return early if out of bounds
        if (y <= 0) {
            return mX[endIndex];
        } else if (y >= 1) {
            return mX[startIndex];
        }

        // Do a binary search for index that bounds the y
        while (endIndex - startIndex > 1) {
            int midIndex = (startIndex + endIndex) / 2;
            if (y < mY[midIndex]) {
                startIndex = midIndex;
            } else {
                endIndex = midIndex;
            }
        }

        float yRange = mY[endIndex] - mY[startIndex];
        if (yRange == 0) {
            return mX[startIndex];
        }

        float tInRange = y - mY[startIndex];
        float fraction = tInRange / yRange;

        float startX = mX[startIndex];
        float endX = mX[endIndex];
        return startX + (fraction * (endX - startX));
    }

    /**
     * Returns the arclength of the path we are interpolating.
     */
    public float getArcLength() {
        return mArcLength;
    }
}