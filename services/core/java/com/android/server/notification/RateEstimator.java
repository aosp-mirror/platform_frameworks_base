/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.notification;


/**
 * Exponentially weighted moving average estimator for event rate.
 *
 * {@hide}
 */
public class RateEstimator {
    private static final double RATE_ALPHA = 0.8;
    private static final double MINIMUM_DT = 0.0005;
    private Long mLastEventTime;
    private double mInterarrivalTime;

    public RateEstimator() {
        // assume something generous if we have no information
        mInterarrivalTime = 1000.0;
    }

    /** Update the estimate to account for an event that just happened. */
    public float update(long now) {
        float rate;
        if (mLastEventTime == null) {
            // No last event time, rate is zero.
            rate = 0f;
        } else {
            // Calculate the new inter-arrival time based on last event time.
            mInterarrivalTime = getInterarrivalEstimate(now);
            rate = (float) (1.0 / mInterarrivalTime);
        }
        mLastEventTime = now;
        return rate;
    }

    /** @return the estimated rate if there were a new event right now. */
    public float getRate(long now) {
        if (mLastEventTime == null) {
            return 0f;
        }
        return (float) (1.0 / getInterarrivalEstimate(now));
    }

    /** @return the average inter-arrival time if there were a new event right now. */
    private double getInterarrivalEstimate(long now) {
        double dt = ((double) (now - mLastEventTime)) / 1000.0;
        dt = Math.max(dt, MINIMUM_DT);
        // a*iat_old + (1-a)*(t_now-t_last)
        return (RATE_ALPHA * mInterarrivalTime + (1.0 - RATE_ALPHA) * dt);
    }
}
