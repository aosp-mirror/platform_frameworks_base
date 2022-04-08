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

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.view.MotionEvent;

/**
 * A classifier which looks at the proximity sensor during the gesture. It calculates the percentage
 * the proximity sensor showing the near state during the whole gesture
 */
public class ProximityClassifier extends GestureClassifier {
    private long mGestureStartTimeNano;
    private long mNearStartTimeNano;
    private long mNearDuration;
    private boolean mNear;
    private float mAverageNear;

    public ProximityClassifier(ClassifierData classifierData) {
    }

    @Override
    public String getTag() {
        return "PROX";
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            update(event.values[0] < event.sensor.getMaximumRange(), event.timestamp);
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            mGestureStartTimeNano = event.getEventTimeNano();
            mNearStartTimeNano = event.getEventTimeNano();
            mNearDuration = 0;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            update(mNear, event.getEventTimeNano());
            long duration = event.getEventTimeNano() - mGestureStartTimeNano;

            if (duration == 0) {
                mAverageNear = mNear ? 1.0f : 0.0f;
            } else {
                mAverageNear = (float) mNearDuration / (float) duration;
            }
        }
    }


    /**
     * @param near is the sensor showing the near state right now
     * @param timestampNano time of this event in nanoseconds
     */
    private void update(boolean near, long timestampNano) {
        // This if is necessary because MotionEvents and SensorEvents do not come in
        // chronological order
        if (timestampNano > mNearStartTimeNano) {
            // if the state before was near then add the difference of the current time and
            // mNearStartTimeNano to mNearDuration.
            if (mNear) {
                mNearDuration += timestampNano - mNearStartTimeNano;
            }

            // if the new state is near, set mNearStartTimeNano equal to this moment.
            if (near) {
                mNearStartTimeNano = timestampNano;
            }
        }
        mNear = near;
    }

    @Override
    public float getFalseTouchEvaluation(int type) {
        return ProximityEvaluator.evaluate(mAverageNear, type);
    }
}
