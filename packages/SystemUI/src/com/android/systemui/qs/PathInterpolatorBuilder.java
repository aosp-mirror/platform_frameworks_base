/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.graphics.Path;
import android.view.animation.BaseInterpolator;
import android.view.animation.Interpolator;

public class PathInterpolatorBuilder {

    // This governs how accurate the approximation of the Path is.
    private static final float PRECISION = 0.002f;

    private float[] mX; // x coordinates in the line
    private float[] mY; // y coordinates in the line
    private float[] mDist; // Cumulative percentage length of the line

    public PathInterpolatorBuilder(Path path) {
        initPath(path);
    }

    public PathInterpolatorBuilder(float controlX, float controlY) {
        initQuad(controlX, controlY);
    }

    public PathInterpolatorBuilder(float controlX1, float controlY1, float controlX2,
            float controlY2) {
        initCubic(controlX1, controlY1, controlX2, controlY2);
    }

    private void initQuad(float controlX, float controlY) {
        Path path = new Path();
        path.moveTo(0, 0);
        path.quadTo(controlX, controlY, 1f, 1f);
        initPath(path);
    }

    private void initCubic(float x1, float y1, float x2, float y2) {
        Path path = new Path();
        path.moveTo(0, 0);
        path.cubicTo(x1, y1, x2, y2, 1f, 1f);
        initPath(path);
    }

    private void initPath(Path path) {
        float[] pointComponents = path.approximate(PRECISION);

        int numPoints = pointComponents.length / 3;
        if (pointComponents[1] != 0 || pointComponents[2] != 0
                || pointComponents[pointComponents.length - 2] != 1
                || pointComponents[pointComponents.length - 1] != 1) {
            throw new IllegalArgumentException("The Path must start at (0,0) and end at (1,1)");
        }

        mX = new float[numPoints];
        mY = new float[numPoints];
        mDist = new float[numPoints];
        float prevX = 0;
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
            if (i > 0) {
                float dx = mX[i] - mX[i - 1];
                float dy = mY[i] - mY[i - 1];
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                mDist[i] = mDist[i - 1] + dist;
            }
            prevX = x;
            prevFraction = fraction;
        }
        // Scale down dist to 0-1.
        float max = mDist[mDist.length - 1];
        for (int i = 0; i < numPoints; i++) {
            mDist[i] /= max;
        }
    }

    public Interpolator getXInterpolator() {
        return new PathInterpolator(mDist, mX);
    }

    public Interpolator getYInterpolator() {
        return new PathInterpolator(mDist, mY);
    }

    private static class PathInterpolator extends BaseInterpolator {
        private final float[] mX; // x coordinates in the line
        private final float[] mY; // y coordinates in the line

        private PathInterpolator(float[] xs, float[] ys) {
            mX = xs;
            mY = ys;
        }

        @Override
        public float getInterpolation(float t) {
            if (t <= 0) {
                return 0;
            } else if (t >= 1) {
                return 1;
            }
            // Do a binary search for the correct x to interpolate between.
            int startIndex = 0;
            int endIndex = mX.length - 1;

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
    }

}
