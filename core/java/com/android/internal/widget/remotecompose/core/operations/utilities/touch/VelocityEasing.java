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
package com.android.internal.widget.remotecompose.core.operations.utilities.touch;

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

/**
 * This computes an form of easing such that the values constrained to be consistent in velocity The
 * easing function is also constrained by the configure To have: a maximum time to stop, a maximum
 * velocity, a maximum acceleration
 */
public class VelocityEasing {
    private float mStartPos = 0;
    private float mStartV = 0;
    private float mEndPos = 0;
    private float mDuration = 0;

    private Stage[] mStage = {new Stage(1), new Stage(2), new Stage(3)};
    private int mNumberOfStages = 0;
    private Easing mEasing;
    private double mEasingAdapterDistance = 0;
    private double mEasingAdapterA = 0;
    private double mEasingAdapterB = 0;
    private boolean mOneDimension = true;
    private float mTotalEasingDuration = 0;

    /**
     * get the duration the easing will take
     *
     * @return the duration for the easing
     */
    public float getDuration() {
        if (mEasing != null) {
            return mTotalEasingDuration;
        }
        return mDuration;
    }

    /**
     * Get the velocity at time t
     *
     * @param t time in seconds
     * @return the velocity units/second
     */
    public float getV(float t) {
        if (mEasing == null) {
            for (int i = 0; i < mNumberOfStages; i++) {
                if (mStage[i].mEndTime > t) {
                    return mStage[i].getVel(t);
                }
            }
            return 0f;
        }
        int lastStages = mNumberOfStages - 1;
        for (int i = 0; i < lastStages; i++) {
            if (mStage[i].mEndTime > t) {
                return mStage[i].getVel(t);
            }
        }
        return (float) getEasingDiff((t - mStage[lastStages].mStartTime));
    }

    /**
     * Get the position t seconds after the configure
     *
     * @param t time in seconds
     * @return the position at time t
     */
    public float getPos(float t) {
        if (mEasing == null) {
            for (int i = 0; i < mNumberOfStages; i++) {
                if (mStage[i].mEndTime > t) {
                    return mStage[i].getPos(t);
                }
            }
            return mEndPos;
        }
        int lastStages = mNumberOfStages - 1;
        for (int i = 0; i < lastStages; i++) {
            if (mStage[i].mEndTime > t) {
                return mStage[i].getPos(t);
            }
        }
        var ret = (float) getEasing((t - mStage[lastStages].mStartTime));
        ret += mStage[lastStages].mStartPos;
        return ret;
    }

    @Override
    public String toString() {
        var s = " ";
        for (int i = 0; i < mNumberOfStages; i++) {
            Stage stage = mStage[i];
            s += " $i $stage";
        }
        return s;
    }

    /**
     * Configure the Velocity easing curve The system is in arbitrary units
     *
     * @param currentPos the current position
     * @param destination the destination
     * @param currentVelocity the current velocity units/seconds
     * @param maxTime the max time to achieve position
     * @param maxAcceleration the max acceleration units/s^2
     * @param maxVelocity the maximum velocity
     * @param easing End in using this easing curve
     */
    public void config(
            float currentPos,
            float destination,
            float currentVelocity,
            float maxTime,
            float maxAcceleration,
            float maxVelocity,
            Easing easing) {
        float pos = currentPos;
        float velocity = currentVelocity;
        if (pos == destination) {
            pos += 1f;
        }
        mStartPos = pos;
        mEndPos = destination;
        if (easing != null) {
            this.mEasing = easing.clone();
        }
        float dir = Math.signum(destination - pos);
        float maxV = maxVelocity * dir;
        float maxA = maxAcceleration * dir;
        if (velocity == 0.0) {
            velocity = 0.0001f * dir;
        }
        mStartV = velocity;
        if (!rampDown(pos, destination, velocity, maxTime)) {
            if (!(mOneDimension
                    && cruseThenRampDown(pos, destination, velocity, maxTime, maxA, maxV))) {
                if (!rampUpRampDown(pos, destination, velocity, maxA, maxV, maxTime)) {
                    rampUpCruseRampDown(pos, destination, velocity, maxA, maxV, maxTime);
                }
            }
        }
        if (mOneDimension) {
            configureEasingAdapter();
        }
    }

    private boolean rampDown(
            float currentPos, float destination, float currentVelocity, float maxTime) {
        float timeToDestination = 2 * ((destination - currentPos) / currentVelocity);
        if (timeToDestination > 0 && timeToDestination <= maxTime) { // hit the brakes
            mNumberOfStages = 1;
            mStage[0].setUp(currentVelocity, currentPos, 0f, 0f, destination, timeToDestination);
            mDuration = timeToDestination;
            return true;
        }
        return false;
    }

    private boolean cruseThenRampDown(
            float currentPos,
            float destination,
            float currentVelocity,
            float maxTime,
            float maxA,
            float maxV) {
        float timeToBreak = currentVelocity / maxA;
        float brakeDist = currentVelocity * timeToBreak / 2;
        float cruseDist = destination - currentPos - brakeDist;
        float cruseTime = cruseDist / currentVelocity;
        float totalTime = cruseTime + timeToBreak;
        if (totalTime > 0 && totalTime < maxTime) {
            mNumberOfStages = 2;
            mStage[0].setUp(currentVelocity, currentPos, 0f, currentVelocity, cruseDist, cruseTime);
            mStage[1].setUp(
                    currentVelocity,
                    currentPos + cruseDist,
                    cruseTime,
                    0f,
                    destination,
                    cruseTime + timeToBreak);
            mDuration = cruseTime + timeToBreak;
            return true;
        }
        return false;
    }

    private boolean rampUpRampDown(
            float currentPos,
            float destination,
            float currentVelocity,
            float maxA,
            float maxVelocity,
            float maxTime) {
        float peak_v =
                Math.signum(maxA)
                        * (float)
                                Math.sqrt(
                                        (maxA * (destination - currentPos)
                                                + currentVelocity * currentVelocity / 2));
        if (maxVelocity / peak_v > 1) {
            float t1 = (peak_v - currentVelocity) / maxA;
            float d1 = (peak_v + currentVelocity) * t1 / 2 + currentPos;
            float t2 = peak_v / maxA;
            mNumberOfStages = 2;
            mStage[0].setUp(currentVelocity, currentPos, 0f, peak_v, d1, t1);
            mStage[1].setUp(peak_v, d1, t1, 0f, destination, t2 + t1);
            mDuration = t2 + t1;
            if (mDuration > maxTime) {
                return false;
            }
            if (mDuration < maxTime / 2) {
                t1 = mDuration / 2;
                t2 = t1;
                peak_v = (2 * (destination - currentPos) / t1 - currentVelocity) / 2;
                d1 = (peak_v + currentVelocity) * t1 / 2 + currentPos;
                mNumberOfStages = 2;
                mStage[0].setUp(currentVelocity, currentPos, 0f, peak_v, d1, t1);
                mStage[1].setUp(peak_v, d1, t1, 0f, destination, t2 + t1);
                mDuration = t2 + t1;
                if (mDuration > maxTime) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private void rampUpCruseRampDown(
            float currentPos,
            float destination,
            float currentVelocity,
            float maxA,
            float maxV,
            float maxTime) {
        float t1 = maxTime / 3;
        float t2 = t1 * 2;
        float distance = destination - currentPos;
        float dt2 = t2 - t1;
        float dt3 = maxTime - t2;
        float v1 = (2 * distance - currentVelocity * t1) / (t1 + 2 * dt2 + dt3);
        mDuration = maxTime;
        float d1 = (currentVelocity + v1) * t1 / 2;
        float d2 = (v1 + v1) * (t2 - t1) / 2;
        mNumberOfStages = 3;
        float acc = (v1 - currentVelocity) / t1;
        float dec = v1 / dt3;
        mStage[0].setUp(currentVelocity, currentPos, 0f, v1, currentPos + d1, t1);
        mStage[1].setUp(v1, currentPos + d1, t1, v1, currentPos + d1 + d2, t2);
        mStage[2].setUp(v1, currentPos + d1 + d2, t2, 0f, destination, maxTime);
        mDuration = maxTime;
    }

    double getEasing(double t) {
        double gx = t * t * mEasingAdapterA + t * mEasingAdapterB;
        if (gx > 1) {
            return mEasingAdapterDistance;
        } else {
            return mEasing.get(gx) * mEasingAdapterDistance;
        }
    }

    private double getEasingDiff(double t) {
        double gx = t * t * mEasingAdapterA + t * mEasingAdapterB;
        if (gx > 1) {
            return 0.0;
        } else {
            return mEasing.getDiff(gx)
                    * mEasingAdapterDistance
                    * (t * mEasingAdapterA + mEasingAdapterB);
        }
    }

    protected void configureEasingAdapter() {
        if (mEasing == null) {
            return;
        }
        int last = mNumberOfStages - 1;
        float initialVelocity = mStage[last].mStartV;
        float distance = mStage[last].mEndPos - mStage[last].mStartPos;
        float duration = mStage[last].mEndTime - mStage[last].mStartTime;
        double baseVel = mEasing.getDiff(0.0);
        mEasingAdapterB = initialVelocity / (baseVel * distance);
        mEasingAdapterA = 1 - mEasingAdapterB;
        mEasingAdapterDistance = distance;
        double easingDuration =
                (Math.sqrt(4 * mEasingAdapterA + mEasingAdapterB * mEasingAdapterB)
                                - mEasingAdapterB)
                        / (2 * mEasingAdapterA);
        mTotalEasingDuration = (float) (easingDuration + mStage[last].mStartTime);
    }

    interface Easing {
        double get(double t);

        double getDiff(double t);

        Easing clone();
    }

    class Stage {
        private float mStartV = 0;
        private float mStartPos = 0;
        private float mStartTime = 0;
        private float mEndV = 0;
        private float mEndPos = 0;
        private float mEndTime = 0;
        private float mDeltaV = 0;
        private float mDeltaT = 0;
        final int mStage;

        Stage(int n) {
            mStage = n;
        }

        void setUp(
                float startV,
                float startPos,
                float startTime,
                float endV,
                float endPos,
                float endTime) {
            this.mStartV = startV;
            this.mStartPos = startPos;
            this.mStartTime = startTime;
            this.mEndV = endV;
            this.mEndTime = endTime;
            this.mEndPos = endPos;
            mDeltaV = this.mEndV - this.mStartV;
            mDeltaT = this.mEndTime - this.mStartTime;
        }

        float getPos(float t) {
            float dt = t - mStartTime;
            float pt = dt / mDeltaT;
            float v = mStartV + mDeltaV * pt;
            return dt * (mStartV + v) / 2 + mStartPos;
        }

        float getVel(float t) {
            float dt = t - mStartTime;
            float pt = dt / (mEndTime - mStartTime);
            return mStartV + mDeltaV * pt;
        }
    }
}
