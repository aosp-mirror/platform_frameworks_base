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
 * This contains the class to provide the logic for an animation to come to a stop using a spring
 * model. String debug(String desc, float time); float getVelocity(float time); float
 * getInterpolation(float time); float getVelocity(); boolean isStopped();
 */
public class SpringStopEngine {
    double mDamping = 0.5f;

    @SuppressWarnings("unused")
    private static final double UNSET = Double.MAX_VALUE;

    @SuppressWarnings("unused")
    private boolean mInitialized = false;

    private double mStiffness;
    private double mTargetPos;

    @SuppressWarnings("unused")
    private double mLastVelocity;

    private float mLastTime;
    private float mPos;
    private float mV;
    private float mMass;
    private float mStopThreshold;
    private int mBoundaryMode = 0;

    //    public String debug(String desc, float time) {
    //        return null;
    //    }

    void log(String str) {
        StackTraceElement s = new Throwable().getStackTrace()[1];
        String line =
                ".(" + s.getFileName() + ":" + s.getLineNumber() + ") " + s.getMethodName() + "() ";
        System.out.println(line + str);
    }

    /** */
    public SpringStopEngine() {}

    /**
     * get the value the sping is pulling towards
     *
     * @return the value the sping is pulling towards
     */
    public float getTargetValue() {
        return (float) mTargetPos;
    }

    /**
     * get the value the sping is starting from
     *
     * @param v the value the sping is starting from
     */
    public void setInitialValue(float v) {
        mPos = v;
    }

    /**
     * set the value the sping is pulling towards
     *
     * @param v the value the sping is pulling towards
     */
    public void setTargetValue(float v) {
        mTargetPos = v;
    }

    /**
     * Create a sping engine with the parameters encoded as an array of floats
     *
     * @param parameters the parameters to use
     */
    public SpringStopEngine(float[] parameters) {
        if (parameters[0] != 0) {
            throw new RuntimeException(" parameter[0] should be 0");
        }

        springParameters(
                1,
                parameters[1],
                parameters[2],
                parameters[3],
                Float.floatToRawIntBits(parameters[4]));
    }

    /**
     * Config the spring starting conditions
     *
     * @param currentPos the current position of the spring
     * @param target the target position of the spring
     * @param currentVelocity the current velocity of the spring
     */
    public void springStart(float currentPos, float target, float currentVelocity) {
        mTargetPos = target;
        mInitialized = false;
        mPos = currentPos;
        mLastVelocity = currentVelocity;
        mLastTime = 0;
    }

    /**
     * Config the spring parameters
     *
     * @param mass The mass of the spring
     * @param stiffness The stiffness of the spring
     * @param damping The dampening factor
     * @param stopThreshold how low energy must you be to stop
     * @param boundaryMode The boundary behaviour
     */
    public void springParameters(
            float mass, float stiffness, float damping, float stopThreshold, int boundaryMode) {
        mDamping = damping;
        mInitialized = false;
        mStiffness = stiffness;
        mMass = mass;
        mStopThreshold = stopThreshold;
        mBoundaryMode = boundaryMode;
        mLastTime = 0;
    }

    /**
     * get the velocity of the spring at a time
     *
     * @param time the time to get the velocity at
     * @return the velocity of the spring at a time
     */
    public float getVelocity(float time) {
        return (float) mV;
    }

    /**
     * get the position of the spring at a time
     *
     * @param time the time to get the position at
     * @return the position of the spring at a time
     */
    public float get(float time) {
        compute(time - mLastTime);
        mLastTime = time;
        if (isStopped()) {
            mPos = (float) mTargetPos;
        }
        return (float) mPos;
    }

    /**
     * get the acceleration of the spring
     *
     * @return the acceleration of the spring
     */
    public float getAcceleration() {
        double k = mStiffness;
        double c = mDamping;
        double x = (mPos - mTargetPos);
        return (float) (-k * x - c * mV) / mMass;
    }

    /**
     * get the velocity of the spring
     *
     * @return the velocity of the spring
     */
    public float getVelocity() {
        return 0;
    }

    /**
     * is the spring stopped
     *
     * @return true if the spring is stopped
     */
    public boolean isStopped() {
        double x = (mPos - mTargetPos);
        double k = mStiffness;
        double v = mV;
        double m = mMass;
        double energy = v * v * m + k * x * x;
        double max_def = Math.sqrt(energy / k);
        return max_def <= mStopThreshold;
    }

    /**
     * increment the spring position over time dt
     *
     * @param dt the time to increment the spring position over
     */
    private void compute(double dt) {
        if (dt <= 0) {
            // Nothing to compute if there's no time difference
            return;
        }

        double k = mStiffness;
        double c = mDamping;
        // Estimate how many time we should over sample based on the frequency and current sampling
        int overSample = (int) (1 + 9 / (Math.sqrt(mStiffness / mMass) * dt * 4));
        dt /= overSample;

        for (int i = 0; i < overSample; i++) {
            double x = (mPos - mTargetPos);
            double a = (-k * x - c * mV) / mMass;
            // This refinement of a simple coding of the acceleration increases accuracy
            double avgV = mV + a * dt / 2; // pass 1 calculate the average velocity
            double avgX = mPos + dt * avgV / 2 - mTargetPos; // pass 1 calculate the average pos
            a = (-avgX * k - avgV * c) / mMass; //  calculate acceleration over that average pos

            double dv = a * dt; //  calculate change in velocity
            avgV = mV + dv / 2; //  average  velocity is current + half change
            mV += (float) dv;
            mPos += (float) (avgV * dt);
            if (mBoundaryMode > 0) {
                if (mPos < 0 && ((mBoundaryMode & 1) == 1)) {
                    mPos = -mPos;
                    mV = -mV;
                }
                if (mPos > 1 && ((mBoundaryMode & 2) == 2)) {
                    mPos = 2 - mPos;
                    mV = -mV;
                }
            }
        }
    }
}
