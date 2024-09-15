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


/**
 * This class translates a series of floating point values into a continuous
 * curve for use in an easing function including quantize functions
 * it is used with the "spline(0,0.3,0.3,0.5,...0.9,1)" it should start at 0 and end at one 1
 */
public class StepCurve extends Easing {
    private static final boolean DEBUG = false;
    MonotonicCurveFit mCurveFit;

    public StepCurve(float[] params, int offset, int len) {
        mCurveFit = genSpline(params, offset, len);
    }

    private static MonotonicCurveFit genSpline(float[] values, int off, int arrayLen) {
        int length = arrayLen * 3 - 2;
        int len = arrayLen - 1;
        double gap = 1.0 / len;
        double[][] points = new double[length][1];
        double[] time = new double[length];
        for (int i = 0; i < arrayLen; i++) {
            double v = values[i + off];
            points[i + len][0] = v;
            time[i + len] = i * gap;
            if (i > 0) {
                points[i + len * 2][0] = v + 1;
                time[i + len * 2] = i * gap + 1;

                points[i - 1][0] = v - 1 - gap;
                time[i - 1] = i * gap + -1 - gap;
            }
        }

        MonotonicCurveFit ms = new MonotonicCurveFit(time, points);

        return ms;
    }

    @Override
    public float getDiff(float x) {
        return (float) mCurveFit.getSlope(x, 0);
    }


    @Override
    public float get(float x) {
        return (float) mCurveFit.getPos(x, 0);
    }
}
