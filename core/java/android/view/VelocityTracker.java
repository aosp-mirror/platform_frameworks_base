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

import android.util.Config;
import android.util.Log;
import android.util.Poolable;
import android.util.Pool;
import android.util.Pools;
import android.util.PoolableManager;

/**
 * Helper for tracking the velocity of touch events, for implementing
 * flinging and other such gestures.  Use {@link #obtain} to retrieve a
 * new instance of the class when you are going to begin tracking, put
 * the motion events you receive into it with {@link #addMovement(MotionEvent)},
 * and when you want to determine the velocity call
 * {@link #computeCurrentVelocity(int)} and then {@link #getXVelocity()}
 * and {@link #getXVelocity()}.
 */
public final class VelocityTracker implements Poolable<VelocityTracker> {
    static final String TAG = "VelocityTracker";
    static final boolean DEBUG = false;
    static final boolean localLOGV = DEBUG || Config.LOGV;

    static final int NUM_PAST = 10;
    static final int LONGEST_PAST_TIME = 200;

    static final VelocityTracker[] mPool = new VelocityTracker[1];
    private static final Pool<VelocityTracker> sPool = Pools.synchronizedPool(
            Pools.finitePool(new PoolableManager<VelocityTracker>() {
                public VelocityTracker newInstance() {
                    return new VelocityTracker();
                }

                public void onAcquired(VelocityTracker element) {
                    element.clear();
                }

                public void onReleased(VelocityTracker element) {
                }
            }, 2));

    final float mPastX[][] = new float[MotionEvent.BASE_AVAIL_POINTERS][NUM_PAST];
    final float mPastY[][] = new float[MotionEvent.BASE_AVAIL_POINTERS][NUM_PAST];
    final long mPastTime[][] = new long[MotionEvent.BASE_AVAIL_POINTERS][NUM_PAST];

    float mYVelocity[] = new float[MotionEvent.BASE_AVAIL_POINTERS];
    float mXVelocity[] = new float[MotionEvent.BASE_AVAIL_POINTERS];

    private VelocityTracker mNext;

    /**
     * Retrieve a new VelocityTracker object to watch the velocity of a
     * motion.  Be sure to call {@link #recycle} when done.  You should
     * generally only maintain an active object while tracking a movement,
     * so that the VelocityTracker can be re-used elsewhere.
     *
     * @return Returns a new VelocityTracker.
     */
    static public VelocityTracker obtain() {
        return sPool.acquire();
    }

    /**
     * Return a VelocityTracker object back to be re-used by others.  You must
     * not touch the object after calling this function.
     */
    public void recycle() {
        sPool.release(this);
    }

    /**
     * @hide
     */
    public void setNextPoolable(VelocityTracker element) {
        mNext = element;
    }

    /**
     * @hide
     */
    public VelocityTracker getNextPoolable() {
        return mNext;
    }

    private VelocityTracker() {
    }
    
    /**
     * Reset the velocity tracker back to its initial state.
     */
    public void clear() {
        for (int i = 0; i < MotionEvent.BASE_AVAIL_POINTERS; i++) {
            mPastTime[i][0] = 0;
        }
    }
    
    /**
     * Add a user's movement to the tracker.  You should call this for the
     * initial {@link MotionEvent#ACTION_DOWN}, the following
     * {@link MotionEvent#ACTION_MOVE} events that you receive, and the
     * final {@link MotionEvent#ACTION_UP}.  You can, however, call this
     * for whichever events you desire.
     * 
     * @param ev The MotionEvent you received and would like to track.
     */
    public void addMovement(MotionEvent ev) {
        long time = ev.getEventTime();
        final int N = ev.getHistorySize();
        final int pointerCount = ev.getPointerCount();
        for (int p = 0; p < pointerCount; p++) {
            for (int i=0; i<N; i++) {
                addPoint(p, ev.getHistoricalX(p, i), ev.getHistoricalY(p, i),
                        ev.getHistoricalEventTime(i));
            }
            addPoint(p, ev.getX(p), ev.getY(p), time);
        }
    }

    private void addPoint(int pos, float x, float y, long time) {
        int drop = -1;
        int i;
        if (localLOGV) Log.v(TAG, "Adding past y=" + y + " time=" + time);
        final long[] pastTime = mPastTime[pos];
        for (i=0; i<NUM_PAST; i++) {
            if (pastTime[i] == 0) {
                break;
            } else if (pastTime[i] < time-LONGEST_PAST_TIME) {
                if (localLOGV) Log.v(TAG, "Dropping past too old at "
                        + i + " time=" + pastTime[i]);
                drop = i;
            }
        }
        if (localLOGV) Log.v(TAG, "Add index: " + i);
        if (i == NUM_PAST && drop < 0) {
            drop = 0;
        }
        if (drop == i) drop--;
        final float[] pastX = mPastX[pos];
        final float[] pastY = mPastY[pos];
        if (drop >= 0) {
            if (localLOGV) Log.v(TAG, "Dropping up to #" + drop);
            final int start = drop+1;
            final int count = NUM_PAST-drop-1;
            System.arraycopy(pastX, start, pastX, 0, count);
            System.arraycopy(pastY, start, pastY, 0, count);
            System.arraycopy(pastTime, start, pastTime, 0, count);
            i -= (drop+1);
        }
        pastX[i] = x;
        pastY[i] = y;
        pastTime[i] = time;
        i++;
        if (i < NUM_PAST) {
            pastTime[i] = 0;
        }
    }

    /**
     * Equivalent to invoking {@link #computeCurrentVelocity(int, float)} with a maximum
     * velocity of Float.MAX_VALUE.
     * 
     * @see #computeCurrentVelocity(int, float) 
     */
    public void computeCurrentVelocity(int units) {
        computeCurrentVelocity(units, Float.MAX_VALUE);
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
        for (int pos = 0; pos < MotionEvent.BASE_AVAIL_POINTERS; pos++) {
            final float[] pastX = mPastX[pos];
            final float[] pastY = mPastY[pos];
            final long[] pastTime = mPastTime[pos];

            // Kind-of stupid.
            final float oldestX = pastX[0];
            final float oldestY = pastY[0];
            final long oldestTime = pastTime[0];
            float accumX = 0;
            float accumY = 0;
            int N=0;
            while (N < NUM_PAST) {
                if (pastTime[N] == 0) {
                    break;
                }
                N++;
            }
            // Skip the last received event, since it is probably pretty noisy.
            if (N > 3) N--;

            for (int i=1; i < N; i++) {
                final int dur = (int)(pastTime[i] - oldestTime);
                if (dur == 0) continue;
                float dist = pastX[i] - oldestX;
                float vel = (dist/dur) * units;   // pixels/frame.
                if (accumX == 0) accumX = vel;
                else accumX = (accumX + vel) * .5f;

                dist = pastY[i] - oldestY;
                vel = (dist/dur) * units;   // pixels/frame.
                if (accumY == 0) accumY = vel;
                else accumY = (accumY + vel) * .5f;
            }
            mXVelocity[pos] = accumX < 0.0f ? Math.max(accumX, -maxVelocity)
                    : Math.min(accumX, maxVelocity);
            mYVelocity[pos] = accumY < 0.0f ? Math.max(accumY, -maxVelocity)
                    : Math.min(accumY, maxVelocity);

            if (localLOGV) Log.v(TAG, "Y velocity=" + mYVelocity +" X velocity="
                    + mXVelocity + " N=" + N);
        }
    }
    
    /**
     * Retrieve the last computed X velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @return The previously computed X velocity.
     */
    public float getXVelocity() {
        return mXVelocity[0];
    }
    
    /**
     * Retrieve the last computed Y velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @return The previously computed Y velocity.
     */
    public float getYVelocity() {
        return mYVelocity[0];
    }
    
    /**
     * Retrieve the last computed X velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @param pos Which pointer's velocity to return.
     * @return The previously computed X velocity.
     * 
     * @hide Pending API approval
     */
    public float getXVelocity(int pos) {
        return mXVelocity[pos];
    }
    
    /**
     * Retrieve the last computed Y velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @param pos Which pointer's velocity to return.
     * @return The previously computed Y velocity.
     * 
     * @hide Pending API approval
     */
    public float getYVelocity(int pos) {
        return mYVelocity[pos];
    }
}
