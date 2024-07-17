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

import java.util.Arrays;

/**
 * This performs a spline interpolation in multiple dimensions
 *
 *
 */
public class MonotonicCurveFit  {
    private static final String TAG = "MonotonicCurveFit";
    private double[] mT;
    private double[][] mY;
    private double[][] mTangent;
    private boolean mExtrapolate = true;
    double[] mSlopeTemp;

    /**
     * create a collection of curves
     * @param time the point along the curve
     * @param y the parameter at those points
     */
    public MonotonicCurveFit(double[] time, double[][] y) {
        final int n = time.length;
        final int dim = y[0].length;
        mSlopeTemp = new double[dim];
        double[][] slope = new double[n - 1][dim]; // could optimize this out
        double[][] tangent = new double[n][dim];
        for (int j = 0; j < dim; j++) {
            for (int i = 0; i < n - 1; i++) {
                double dt = time[i + 1] - time[i];
                slope[i][j] = (y[i + 1][j] - y[i][j]) / dt;
                if (i == 0) {
                    tangent[i][j] = slope[i][j];
                } else {
                    tangent[i][j] = (slope[i - 1][j] + slope[i][j]) * 0.5f;
                }
            }
            tangent[n - 1][j] = slope[n - 2][j];
        }

        for (int i = 0; i < n - 1; i++) {
            for (int j = 0; j < dim; j++) {
                if (slope[i][j] == 0.) {
                    tangent[i][j] = 0.;
                    tangent[i + 1][j] = 0.;
                } else {
                    double a = tangent[i][j] / slope[i][j];
                    double b = tangent[i + 1][j] / slope[i][j];
                    double h = Math.hypot(a, b);
                    if (h > 9.0) {
                        double t = 3. / h;
                        tangent[i][j] = t * a * slope[i][j];
                        tangent[i + 1][j] = t * b * slope[i][j];
                    }
                }
            }
        }
        mT = time;
        mY = y;
        mTangent = tangent;
    }

    /**
     * Get the position of all curves at time t
     * @param t
     * @param v
     */
    public void getPos(double t, double[] v) {
        final int n = mT.length;
        final int dim = mY[0].length;
        if (mExtrapolate) {
            if (t <= mT[0]) {
                getSlope(mT[0], mSlopeTemp);
                for (int j = 0; j < dim; j++) {
                    v[j] = mY[0][j] + (t - mT[0]) * mSlopeTemp[j];
                }
                return;
            }
            if (t >= mT[n - 1]) {
                getSlope(mT[n - 1], mSlopeTemp);
                for (int j = 0; j < dim; j++) {
                    v[j] = mY[n - 1][j] + (t - mT[n - 1]) * mSlopeTemp[j];
                }
                return;
            }
        } else {
            if (t <= mT[0]) {
                for (int j = 0; j < dim; j++) {
                    v[j] = mY[0][j];
                }
                return;
            }
            if (t >= mT[n - 1]) {
                for (int j = 0; j < dim; j++) {
                    v[j] = mY[n - 1][j];
                }
                return;
            }
        }

        for (int i = 0; i < n - 1; i++) {
            if (t == mT[i]) {
                for (int j = 0; j < dim; j++) {
                    v[j] = mY[i][j];
                }
            }
            if (t < mT[i + 1]) {
                double h = mT[i + 1] - mT[i];
                double x = (t - mT[i]) / h;
                for (int j = 0; j < dim; j++) {
                    double y1 = mY[i][j];
                    double y2 = mY[i + 1][j];
                    double t1 = mTangent[i][j];
                    double t2 = mTangent[i + 1][j];
                    v[j] = interpolate(h, x, y1, y2, t1, t2);
                }
                return;
            }
        }
    }

    /**
     * Get the position of all curves at time t
     * @param t
     * @param v
     */
    public void getPos(double t, float[] v) {
        final int n = mT.length;
        final int dim = mY[0].length;
        if (mExtrapolate) {
            if (t <= mT[0]) {
                getSlope(mT[0], mSlopeTemp);
                for (int j = 0; j < dim; j++) {
                    v[j] = (float) (mY[0][j] + (t - mT[0]) * mSlopeTemp[j]);
                }
                return;
            }
            if (t >= mT[n - 1]) {
                getSlope(mT[n - 1], mSlopeTemp);
                for (int j = 0; j < dim; j++) {
                    v[j] = (float) (mY[n - 1][j] + (t - mT[n - 1]) * mSlopeTemp[j]);
                }
                return;
            }
        } else {
            if (t <= mT[0]) {
                for (int j = 0; j < dim; j++) {
                    v[j] = (float) mY[0][j];
                }
                return;
            }
            if (t >= mT[n - 1]) {
                for (int j = 0; j < dim; j++) {
                    v[j] = (float) mY[n - 1][j];
                }
                return;
            }
        }

        for (int i = 0; i < n - 1; i++) {
            if (t == mT[i]) {
                for (int j = 0; j < dim; j++) {
                    v[j] = (float) mY[i][j];
                }
            }
            if (t < mT[i + 1]) {
                double h = mT[i + 1] - mT[i];
                double x = (t - mT[i]) / h;
                for (int j = 0; j < dim; j++) {
                    double y1 = mY[i][j];
                    double y2 = mY[i + 1][j];
                    double t1 = mTangent[i][j];
                    double t2 = mTangent[i + 1][j];
                    v[j] = (float) interpolate(h, x, y1, y2, t1, t2);
                }
                return;
            }
        }
    }

    /**
     * Get the position of the jth curve at time t
     * @param t
     * @param j
     * @return
     */
    public double getPos(double t, int j) {
        final int n = mT.length;
        if (mExtrapolate) {
            if (t <= mT[0]) {
                return mY[0][j] + (t - mT[0]) * getSlope(mT[0], j);
            }
            if (t >= mT[n - 1]) {
                return mY[n - 1][j] + (t - mT[n - 1]) * getSlope(mT[n - 1], j);
            }
        } else {
            if (t <= mT[0]) {
                return mY[0][j];
            }
            if (t >= mT[n - 1]) {
                return mY[n - 1][j];
            }
        }

        for (int i = 0; i < n - 1; i++) {
            if (t == mT[i]) {
                return mY[i][j];
            }
            if (t < mT[i + 1]) {
                double h = mT[i + 1] - mT[i];
                double x = (t - mT[i]) / h;
                double y1 = mY[i][j];
                double y2 = mY[i + 1][j];
                double t1 = mTangent[i][j];
                double t2 = mTangent[i + 1][j];
                return interpolate(h, x, y1, y2, t1, t2);

            }
        }
        return 0; // should never reach here
    }

    /**
     * Get the slope of all the curves at position t
     * @param t
     * @param v
     */
    public void getSlope(double t, double[] v) {
        final int n = mT.length;
        int dim = mY[0].length;
        if (t <= mT[0]) {
            t = mT[0];
        } else if (t >= mT[n - 1]) {
            t = mT[n - 1];
        }

        for (int i = 0; i < n - 1; i++) {
            if (t <= mT[i + 1]) {
                double h = mT[i + 1] - mT[i];
                double x = (t - mT[i]) / h;
                for (int j = 0; j < dim; j++) {
                    double y1 = mY[i][j];
                    double y2 = mY[i + 1][j];
                    double t1 = mTangent[i][j];
                    double t2 = mTangent[i + 1][j];
                    v[j] = diff(h, x, y1, y2, t1, t2) / h;
                }
                break;
            }
        }
        return;
    }

    /**
     * Get the slope of the j curve at position t
     * @param t
     * @param j
     * @return
     */
    public double getSlope(double t, int j) {
        final int n = mT.length;

        if (t < mT[0]) {
            t = mT[0];
        } else if (t >= mT[n - 1]) {
            t = mT[n - 1];
        }
        for (int i = 0; i < n - 1; i++) {
            if (t <= mT[i + 1]) {
                double h = mT[i + 1] - mT[i];
                double x = (t - mT[i]) / h;
                double y1 = mY[i][j];
                double y2 = mY[i + 1][j];
                double t1 = mTangent[i][j];
                double t2 = mTangent[i + 1][j];
                return diff(h, x, y1, y2, t1, t2) / h;
            }
        }
        return 0; // should never reach here
    }

    public double[] getTimePoints() {
        return mT;
    }

    /**
     * Cubic Hermite spline
     */
    private static double interpolate(double h,
                                      double x,
                                      double y1,
                                      double y2,
                                      double t1,
                                      double t2) {
        double x2 = x * x;
        double x3 = x2 * x;
        return -2 * x3 * y2 + 3 * x2 * y2 + 2 * x3 * y1 - 3 * x2 * y1 + y1
                + h * t2 * x3 + h * t1 * x3 - h * t2 * x2 - 2 * h * t1 * x2
                + h * t1 * x;
    }

    /**
     * Cubic Hermite spline slope differentiated
     */
    private static double diff(double h, double x, double y1, double y2, double t1, double t2) {
        double x2 = x * x;
        return -6 * x2 * y2 + 6 * x * y2 + 6 * x2 * y1 - 6 * x * y1 + 3 * h * t2 * x2
                + 3 * h * t1 * x2 - 2 * h * t2 * x - 4 * h * t1 * x + h * t1;
    }

    /**
     * This builds a monotonic spline to be used as a wave function
     */
    public static MonotonicCurveFit buildWave(String configString) {
        // done this way for efficiency
        String str = configString;
        double[] values = new double[str.length() / 2];
        int start = configString.indexOf('(') + 1;
        int off1 = configString.indexOf(',', start);
        int count = 0;
        while (off1 != -1) {
            String tmp = configString.substring(start, off1).trim();
            values[count++] = Double.parseDouble(tmp);
            off1 = configString.indexOf(',', start = off1 + 1);
        }
        off1 = configString.indexOf(')', start);
        String tmp = configString.substring(start, off1).trim();
        values[count++] = Double.parseDouble(tmp);

        return buildWave(Arrays.copyOf(values, count));
    }

    private static MonotonicCurveFit buildWave(double[] values) {
        int length = values.length * 3 - 2;
        int len = values.length - 1;
        double gap = 1.0 / len;
        double[][] points = new double[length][1];
        double[] time = new double[length];
        for (int i = 0; i < values.length; i++) {
            double v = values[i];
            points[i + len][0] = v;
            time[i + len] = i * gap;
            if (i > 0) {
                points[i + len * 2][0] = v + 1;
                time[i + len * 2] = i * gap + 1;

                points[i - 1][0] = v - 1 - gap;
                time[i - 1] = i * gap + -1 - gap;
            }
        }

        return new MonotonicCurveFit(time, points);
    }
}
