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

import android.hardware.SensorEvent;
import android.view.MotionEvent;

import java.lang.Math;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * A classifier which calculates the variance of differences between successive angles in a stroke.
 * For each stroke it keeps its last three points. If some successive points are the same, it ignores
 * the repetitions. If a new point is added, the classifier calculates the angle between the last
 * three points. After that it calculates the difference between this angle and the previously
 * calculated angle. The return value of the classifier is the variance of the differences
 * from a stroke. If there are multiple strokes created at once, the classifier sums up the
 * variances of all the strokes. Also the value is multiplied by HISTORY_FACTOR after each
 * INTERVAL milliseconds.
 */
public class AnglesVarianceClassifier extends Classifier {
    private final float INTERVAL = 10.0f;
    private final float CLEAR_HISTORY = 500f;
    private final float HISTORY_FACTOR = 0.9f;

    private HashMap<Stroke, Data> mStrokeMap = new HashMap<>();
    private float mValue;
    private long mLastUpdate;

    public AnglesVarianceClassifier(ClassifierData classifierData) {
        mClassifierData = classifierData;
        mValue = 0.0f;
        mLastUpdate = System.currentTimeMillis();
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

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || (action == MotionEvent.ACTION_POINTER_UP && i == event.getActionIndex())) {
                decayValue();
                mValue += mStrokeMap.get(stroke).getAnglesVariance();
            }
        }
    }

    /**
     * Decreases mValue through time
     */
    private void decayValue() {
        long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis - mLastUpdate > CLEAR_HISTORY) {
            mValue = 0.0f;
        } else {
            mValue *= Math.pow(HISTORY_FACTOR, (float) (currentTimeMillis - mLastUpdate) / INTERVAL);
        }
        mLastUpdate = currentTimeMillis;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
    }

    @Override
    public float getFalseTouchEvaluation(int type) {
        decayValue();
        float currentValue = 0.0f;
        for (Data data: mStrokeMap.values()) {
            currentValue += data.getAnglesVariance();
        }
        return (float) (mValue + currentValue);
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
            return mSumSquares / mCount + (mSum / mCount) * (mSum / mCount);
        }
    }
}