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
 * limitations under the License
 */

package com.android.systemui.classifier;

import android.view.MotionEvent;

import java.lang.Math;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * A classifier which calculates the variance of differences between successive angles in a stroke.
 * For each stroke it keeps its last three points. If some successive points are the same, it
 * ignores the repetitions. If a new point is added, the classifier calculates the angle between
 * the last three points. After that, it calculates the difference between this angle and the
 * previously calculated angle. The return value of the classifier is the variance of the
 * differences from a stroke. To the differences there is artificially added value 0.0 and the
 * difference between the first angle and PI (angles are in radians). It helps with strokes which
 * have few points and punishes more strokes which are not smooth.
 */
public class AnglesVarianceClassifier extends StrokeClassifier {
    private HashMap<Stroke, Data> mStrokeMap = new HashMap<>();

    public AnglesVarianceClassifier(ClassifierData classifierData) {
        mClassifierData = classifierData;
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            mStrokeMap.clear();
        }

        for (int i = 0; i < event.getPointerCount(); i++) {
            Stroke stroke = mClassifierData.getStroke(event.getPointerId(i));

            if (mStrokeMap.get(stroke) == null) {
                mStrokeMap.put(stroke, new Data());
            }
            mStrokeMap.get(stroke).addPoint(stroke.getPoints().get(stroke.getPoints().size() - 1));
        }
    }

    @Override
    public float getFalseTouchEvaluation(int type, Stroke stroke) {
        return mStrokeMap.get(stroke).getAnglesVariance();
    }

    private class Data {
        private List<Point> mLastThreePoints = new ArrayList<>();
        private float mPreviousAngle;
        private float mSumSquares;
        private float mSum;
        private float mCount;

        public Data() {
            mPreviousAngle = (float) Math.PI;
            mSumSquares = 0.0f;
            mSum = 0.0f;
            mCount = 1.0f;
        }

        public void addPoint(Point point) {
            // Checking if the added point is different than the previously added point
            // Repetitions are being ignored so that proper angles are calculated.
            if (mLastThreePoints.isEmpty()
                    || !mLastThreePoints.get(mLastThreePoints.size() - 1).equals(point)) {
                mLastThreePoints.add(point);
                if (mLastThreePoints.size() == 4) {
                    mLastThreePoints.remove(0);

                    float angle = getAngle(mLastThreePoints.get(0), mLastThreePoints.get(1),
                            mLastThreePoints.get(2));

                    float difference = angle - mPreviousAngle;
                    mSum += difference;
                    mSumSquares += difference * difference;
                    mCount += 1.0;
                    mPreviousAngle = angle;
                }
            }
        }

        private float getAngle(Point a, Point b, Point c) {
            float dist1 = a.dist(b);
            float dist2 = b.dist(c);
            float crossProduct = b.crossProduct(a, c);
            float dotProduct = b.dotProduct(a, c);
            float cos = Math.min(1.0f, Math.max(-1.0f, dotProduct / dist1 / dist2));
            float angle = (float) Math.acos(cos);
            if (crossProduct < 0.0) {
                angle = 2.0f * (float) Math.PI - angle;
            }
            return angle;
        }

        public float getAnglesVariance() {
            return mSumSquares / mCount - (mSum / mCount) * (mSum / mCount);
        }
    }
}