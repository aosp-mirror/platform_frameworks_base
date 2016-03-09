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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * A classifier which for each point from a stroke, it creates a point on plane with coordinates
 * (timeOffsetNano, distanceCoveredUpToThisPoint) (scaled by DURATION_SCALE and LENGTH_SCALE)
 * and then it calculates the angle variance of these points like the class
 * {@link AnglesClassifier} (without splitting it into two parts). The classifier ignores
 * the last point of a stroke because the UP event comes in with some delay and this ruins the
 * smoothness of this curve. Additionally, the classifier classifies calculates the percentage of
 * angles which value is in [PI - ANGLE_DEVIATION, 2* PI) interval. The reason why the classifier
 * does that is because the speed of a good stroke is most often increases, so most of these angels
 * should be in this interval.
 */
public class SpeedAnglesClassifier extends StrokeClassifier {
    private HashMap<Stroke, Data> mStrokeMap = new HashMap<>();

    public SpeedAnglesClassifier(ClassifierData classifierData) {
        mClassifierData = classifierData;
    }

    @Override
    public String getTag() {
        return "SPD_ANG";
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

            if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
                    && !(action == MotionEvent.ACTION_POINTER_UP && i == event.getActionIndex())) {
                mStrokeMap.get(stroke).addPoint(
                        stroke.getPoints().get(stroke.getPoints().size() - 1));
            }
        }
    }

    @Override
    public float getFalseTouchEvaluation(int type, Stroke stroke) {
        Data data = mStrokeMap.get(stroke);
        return SpeedVarianceEvaluator.evaluate(data.getAnglesVariance())
                + SpeedAnglesPercentageEvaluator.evaluate(data.getAnglesPercentage());
    }

    private static class Data {
        private final float DURATION_SCALE = 1e8f;
        private final float LENGTH_SCALE = 1.0f;
        private final float ANGLE_DEVIATION = (float) Math.PI / 10.0f;

        private List<Point> mLastThreePoints = new ArrayList<>();
        private Point mPreviousPoint;
        private float mPreviousAngle;
        private float mSumSquares;
        private float mSum;
        private float mCount;
        private float mDist;
        private float mAnglesCount;
        private float mAcceleratingAngles;

        public Data() {
            mPreviousPoint = null;
            mPreviousAngle = (float) Math.PI;
            mSumSquares = 0.0f;
            mSum = 0.0f;
            mCount = 1.0f;
            mDist = 0.0f;
            mAnglesCount = mAcceleratingAngles = 0.0f;
        }

        public void addPoint(Point point) {
            if (mPreviousPoint != null) {
                mDist += mPreviousPoint.dist(point);
            }

            mPreviousPoint = point;
            Point speedPoint = new Point((float) point.timeOffsetNano / DURATION_SCALE,
                    mDist / LENGTH_SCALE);

            // Checking if the added point is different than the previously added point
            // Repetitions are being ignored so that proper angles are calculated.
            if (mLastThreePoints.isEmpty()
                    || !mLastThreePoints.get(mLastThreePoints.size() - 1).equals(speedPoint)) {
                mLastThreePoints.add(speedPoint);
                if (mLastThreePoints.size() == 4) {
                    mLastThreePoints.remove(0);

                    float angle = mLastThreePoints.get(1).getAngle(mLastThreePoints.get(0),
                            mLastThreePoints.get(2));

                    mAnglesCount++;
                    if (angle >= (float) Math.PI - ANGLE_DEVIATION) {
                        mAcceleratingAngles++;
                    }

                    float difference = angle - mPreviousAngle;
                    mSum += difference;
                    mSumSquares += difference * difference;
                    mCount += 1.0;
                    mPreviousAngle = angle;
                }
            }
        }

        public float getAnglesVariance() {
            return mSumSquares / mCount - (mSum / mCount) * (mSum / mCount);
        }

        public float getAnglesPercentage() {
            if (mAnglesCount == 0.0f) {
                return 1.0f;
            }
            return (mAcceleratingAngles) / mAnglesCount;
        }
    }
}