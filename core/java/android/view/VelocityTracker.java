/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

import android.util.Pools.SynchronizedPool;

/**
 * Helper for tracking the velocity of touch events, for implementing
 * flinging and other such gestures.
 *
 * Use {@link #obtain} to retrieve a new instance of the class when you are going
 * to begin tracking.  Put the motion events you receive into it with
 * {@link #addMovement(MotionEvent)}.  When you want to determine the velocity call
 * {@link #computeCurrentVelocity(int)} and then call {@link #getXVelocity(int)}
 * and {@link #getYVelocity(int)} to retrieve the velocity for each pointer id.
 */
public final class VelocityTracker {
    private static final SynchronizedPool<VelocityTracker> sPool =
            new SynchronizedPool<VelocityTracker>(2);

    private static final int ACTIVE_POINTER_ID = -1;

    private int mPtr;
    private final String mStrategy;

    private static native int nativeInitialize(String strategy);
    private static native void nativeDispose(int ptr);
    private static native void nativeClear(int ptr);
    private static native void nativeAddMovement(int ptr, MotionEvent event);
    private static native void nativeComputeCurrentVelocity(int ptr, int units, float maxVelocity);
    private static native float nativeGetXVelocity(int ptr, int id);
    private static native float nativeGetYVelocity(int ptr, int id);
    private static native boolean nativeGetEstimator(int ptr, int id, Estimator outEstimator);

    /**
     * Retrieve a new VelocityTracker object to watch the velocity of a
     * motion.  Be sure to call {@link #recycle} when done.  You should
     * generally only maintain an active object while tracking a movement,
     * so that the VelocityTracker can be re-used elsewhere.
     *
     * @return Returns a new VelocityTracker.
     */
    static public VelocityTracker obtain() {
        VelocityTracker instance = sPool.acquire();
        return (instance != null) ? instance : new VelocityTracker(null);
    }

    /**
     * Obtains a velocity tracker with the specified strategy.
     * For testing and comparison purposes only.
     *
     * @param strategy The strategy, or null to use the default.
     * @return The velocity tracker.
     *
     * @hide
     */
    public static VelocityTracker obtain(String strategy) {
        if (strategy == null) {
            return obtain();
        }
        return new VelocityTracker(strategy);
    }

    /**
     * Return a VelocityTracker object back to be re-used by others.  You must
     * not touch the object after calling this function.
     */
    public void recycle() {
        if (mStrategy == null) {
            clear();
            sPool.release(this);
        }
    }

    private VelocityTracker(String strategy) {
        mPtr = nativeInitialize(strategy);
        mStrategy = strategy;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mPtr != 0) {
                nativeDispose(mPtr);
                mPtr = 0;
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * Reset the velocity tracker back to its initial state.
     */
    public void clear() {
        nativeClear(mPtr);
    }
    
    /**
     * Add a user's movement to the tracker.  You should call this for the
     * initial {@link MotionEvent#ACTION_DOWN}, the following
     * {@link MotionEvent#ACTION_MOVE} events that you receive, and the
     * final {@link MotionEvent#ACTION_UP}.  You can, however, call this
     * for whichever events you desire.
     * 
     * @param event The MotionEvent you received and would like to track.
     */
    public void addMovement(MotionEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        nativeAddMovement(mPtr, event);
    }

    /**
     * Equivalent to invoking {@link #computeCurrentVelocity(int, float)} with a maximum
     * velocity of Float.MAX_VALUE.
     * 
     * @see #computeCurrentVelocity(int, float) 
     */
    public void computeCurrentVelocity(int units) {
        nativeComputeCurrentVelocity(mPtr, units, Float.MAX_VALUE);
    }

    /**
     * Compute the current velocity based on the points that have been
     * collected.  Only call this when you actually want to retrieve velocity
     * information, as it is relatively expensive.  You can then retrieve
     * the velocity with {@link #getXVelocity()} and
     * {@link #getYVelocity()}.
     * 
     * @param units The units you would like the velocity in.  A value of 1
     * provides pixels per millisecond, 1000 provides pixels per second, etc.
     * @param maxVelocity The maximum velocity that can be computed by this method.
     * This value must be declared in the same unit as the units parameter. This value
     * must be positive.
     */
    public void computeCurrentVelocity(int units, float maxVelocity) {
        nativeComputeCurrentVelocity(mPtr, units, maxVelocity);
    }
    
    /**
     * Retrieve the last computed X velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @return The previously computed X velocity.
     */
    public float getXVelocity() {
        return nativeGetXVelocity(mPtr, ACTIVE_POINTER_ID);
    }
    
    /**
     * Retrieve the last computed Y velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @return The previously computed Y velocity.
     */
    public float getYVelocity() {
        return nativeGetYVelocity(mPtr, ACTIVE_POINTER_ID);
    }
    
    /**
     * Retrieve the last computed X velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @param id Which pointer's velocity to return.
     * @return The previously computed X velocity.
     */
    public float getXVelocity(int id) {
        return nativeGetXVelocity(mPtr, id);
    }
    
    /**
     * Retrieve the last computed Y velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @param id Which pointer's velocity to return.
     * @return The previously computed Y velocity.
     */
    public float getYVelocity(int id) {
        return nativeGetYVelocity(mPtr, id);
    }

    /**
     * Get an estimator for the movements of a pointer using past movements of the
     * pointer to predict future movements.
     *
     * It is not necessary to call {@link #computeCurrentVelocity(int)} before calling
     * this method.
     *
     * @param id Which pointer's velocity to return.
     * @param outEstimator The estimator to populate.
     * @return True if an estimator was obtained, false if there is no information
     * available about the pointer.
     *
     * @hide For internal use only.  Not a final API.
     */
    public boolean getEstimator(int id, Estimator outEstimator) {
        if (outEstimator == null) {
            throw new IllegalArgumentException("outEstimator must not be null");
        }
        return nativeGetEstimator(mPtr, id, outEstimator);
    }

    /**
     * An estimator for the movements of a pointer based on a polynomial model.
     *
     * The last recorded position of the pointer is at time zero seconds.
     * Past estimated positions are at negative times and future estimated positions
     * are at positive times.
     *
     * First coefficient is position (in pixels), second is velocity (in pixels per second),
     * third is acceleration (in pixels per second squared).
     *
     * @hide For internal use only.  Not a final API.
     */
    public static final class Estimator {
        // Must match VelocityTracker::Estimator::MAX_DEGREE
        private static final int MAX_DEGREE = 4;

        /**
         * Polynomial coefficients describing motion in X.
         */
        public final float[] xCoeff = new float[MAX_DEGREE + 1];

        /**
         * Polynomial coefficients describing motion in Y.
         */
        public final float[] yCoeff = new float[MAX_DEGREE + 1];

        /**
         * Polynomial degree, or zero if only position information is available.
         */
        public int degree;

        /**
         * Confidence (coefficient of determination), between 0 (no fit) and 1 (perfect fit).
         */
        public float confidence;

        /**
         * Gets an estimate of the X position of the pointer at the specified time point.
         * @param time The time point in seconds, 0 is the last recorded time.
         * @return The estimated X coordinate.
         */
        public float estimateX(float time) {
            return estimate(time, xCoeff);
        }

        /**
         * Gets an estimate of the Y position of the pointer at the specified time point.
         * @param time The time point in seconds, 0 is the last recorded time.
         * @return The estimated Y coordinate.
         */
        public float estimateY(float time) {
            return estimate(time, yCoeff);
        }

        /**
         * Gets the X coefficient with the specified index.
         * @param index The index of the coefficient to return.
         * @return The X coefficient, or 0 if the index is greater than the degree.
         */
        public float getXCoeff(int index) {
            return index <= degree ? xCoeff[index] : 0;
        }

        /**
         * Gets the Y coefficient with the specified index.
         * @param index The index of the coefficient to return.
         * @return The Y coefficient, or 0 if the index is greater than the degree.
         */
        public float getYCoeff(int index) {
            return index <= degree ? yCoeff[index] : 0;
        }

        private float estimate(float time, float[] c) {
            float a = 0;
            float scale = 1;
            for (int i = 0; i <= degree; i++) {
                a += c[i] * scale;
                scale *= time;
            }
            return a;
        }
    }
}
