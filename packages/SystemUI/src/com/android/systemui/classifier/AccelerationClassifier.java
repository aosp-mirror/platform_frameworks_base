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

import java.util.HashMap;

/**
 * A classifier which looks at the speed and distance between successive points of a Stroke.
 * It looks at two consecutive speeds between two points and calculates the ratio between them.
 * The final result is the maximum of these values. It does the same for distances. If some speed
 * or distance is equal to zero then the ratio between this and the next part is not calculated. To
 * the duration of each part there is added one nanosecond so that it is always possible to
 * calculate the speed of a part.
 */
public class AccelerationClassifier extends StrokeClassifier {
    private final HashMap<Stroke, Data> mStrokeMap = new HashMap<>();

    public AccelerationClassifier(ClassifierData classifierData) {
        mClassifierData = classifierData;
    }

    @Override
    public String getTag() {
        return "ACC";
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            mStrokeMap.clear();
        }

        for (int i = 0; i < event.getPointerCount(); i++) {
            Stroke stroke = mClassifierData.getStroke(event.getPointerId(i));
            Point point = stroke.getPoints().get(stroke.getPoints().size() - 1);
            if (mStrokeMap.get(stroke) == null) {
                mStrokeMap.put(stroke, new Data(point));
            } else {
                mStrokeMap.get(stroke).addPoint(point);
            }
        }
    }

    @Override
    public float getFalseTouchEvaluation(int type, Stroke stroke) {
        Data data = mStrokeMap.get(stroke);
        return 2 * SpeedRatioEvaluator.evaluate(data.maxSpeedRatio);
    }

    private static class Data {

        static final float MILLIS_TO_NANOS = 1e6f;

        Point previousPoint;
        float previousSpeed = 0;
        float maxSpeedRatio = 0;

        public Data(Point point) {
            previousPoint = point;
        }

        public void addPoint(Point point) {
            float distance = previousPoint.dist(point);
            float duration = (float) (point.timeOffsetNano - previousPoint.timeOffsetNano + 1);
            float speed = distance / duration;

            if (duration > 20 * MILLIS_TO_NANOS || duration < 5 * MILLIS_TO_NANOS) {
                // reject this segment and ensure we won't use data about it in the next round.
                previousSpeed = 0;
                previousPoint = point;
                return;
            }
            if (previousSpeed != 0.0f) {
                maxSpeedRatio = Math.max(maxSpeedRatio, speed / previousSpeed);
            }

            previousSpeed = speed;
            previousPoint = point;
        }
    }
}