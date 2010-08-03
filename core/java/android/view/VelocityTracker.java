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
    static final int MAX_AGE_MILLISECONDS = 200;

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
    
    private static final int INITIAL_POINTERS = 5;
    
    private static final class PointerData {
        public int id;
        public float xVelocity;
        public float yVelocity;
        
        public final float[] pastX = new float[NUM_PAST];
        public final float[] pastY = new float[NUM_PAST];
        public final long[] pastTime = new long[NUM_PAST]; // uses Long.MIN_VALUE as a sentinel
    }
    
    private PointerData[] mPointers = new PointerData[INITIAL_POINTERS];
    private int mNumPointers;
    private int mLastTouchIndex;

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
        clear();
    }
    
    /**
     * Reset the velocity tracker back to its initial state.
     */
    public void clear() {
        mNumPointers = 0;
        mLastTouchIndex = 0;
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
        final int historySize = ev.getHistorySize();
        final int pointerCount = ev.getPointerCount();
        final int lastTouchIndex = mLastTouchIndex;
        final int nextTouchIndex = (lastTouchIndex + 1) % NUM_PAST;
        final int finalTouchIndex = (nextTouchIndex + historySize) % NUM_PAST;
        
        if (pointerCount < mNumPointers) {
            final PointerData[] pointers = mPointers;
            int i = mNumPointers;
            while (--i >= 0) {
                final PointerData pointerData = pointers[i];
                if (ev.findPointerIndex(pointerData.id) == -1) {
                    // Pointer went up.
                    // Shuffle pointers down to fill the hole.  Place the old pointer data at
                    // the end so we can recycle it if more pointers are added later.
                    mNumPointers -= 1;
                    final int remaining = mNumPointers - i;
                    if (remaining != 0) {
                        System.arraycopy(pointers, i + 1, pointers, i, remaining);
                        pointers[mNumPointers] = pointerData;
                    }
                }
            }
        }
        
        for (int i = 0; i < pointerCount; i++){
            final int pointerId = ev.getPointerId(i);
            PointerData pointerData = getPointerData(pointerId);
            if (pointerData == null) {
                // Pointer went down.
                // Add a new entry.  Write a sentinel at the end of the pastTime trace so we
                // will be able to tell where the trace started.
                final PointerData[] oldPointers = mPointers;
                final int newPointerIndex = mNumPointers;
                if (newPointerIndex < oldPointers.length) {
                    pointerData = oldPointers[newPointerIndex];
                    if (pointerData == null) {
                        pointerData = new PointerData();
                        oldPointers[newPointerIndex] = pointerData;
                    }
                } else {
                    final PointerData[] newPointers = new PointerData[newPointerIndex * 2];
                    System.arraycopy(oldPointers, 0, newPointers, 0, newPointerIndex);
                    mPointers = newPointers;
                    pointerData = new PointerData();
                    newPointers[newPointerIndex] = pointerData;
                }
                pointerData.id = pointerId;
                pointerData.pastTime[lastTouchIndex] = Long.MIN_VALUE;
                mNumPointers += 1;
            }
            
            final float[] pastX = pointerData.pastX;
            final float[] pastY = pointerData.pastY;
            final long[] pastTime = pointerData.pastTime;
            
            for (int j = 0; j < historySize; j++) {
                final int touchIndex = (nextTouchIndex + j) % NUM_PAST;
                pastX[touchIndex] = ev.getHistoricalX(i, j);
                pastY[touchIndex] = ev.getHistoricalY(i, j);
                pastTime[touchIndex] = ev.getHistoricalEventTime(j);
            }
            pastX[finalTouchIndex] = ev.getX(i);
            pastY[finalTouchIndex] = ev.getY(i);
            pastTime[finalTouchIndex] = ev.getEventTime();
        }
        
        mLastTouchIndex = finalTouchIndex;
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
        final int numPointers = mNumPointers;
        final PointerData[] pointers = mPointers;
        final int lastTouchIndex = mLastTouchIndex;
        
        for (int p = 0; p < numPointers; p++) {
            final PointerData pointerData = pointers[p];
            final long[] pastTime = pointerData.pastTime;
            
            // Search backwards in time for oldest acceptable time.
            // Stop at the beginning of the trace as indicated by the sentinel time Long.MIN_VALUE.
            int oldestTouchIndex = lastTouchIndex;
            int numTouches = 1;
            final long minTime = pastTime[lastTouchIndex] - MAX_AGE_MILLISECONDS;
            while (numTouches < NUM_PAST) {
                final int nextOldestTouchIndex = (oldestTouchIndex + NUM_PAST - 1) % NUM_PAST;
                final long nextOldestTime = pastTime[nextOldestTouchIndex];
                if (nextOldestTime < minTime) { // also handles end of trace sentinel
                    break;
                }
                oldestTouchIndex = nextOldestTouchIndex;
                numTouches += 1;
            }
            
            // If we have a lot of samples, skip the last received sample since it is
            // probably pretty noisy compared to the sum of all of the traces already acquired.
            if (numTouches > 3) {
                numTouches -= 1;
            }
            
            // Kind-of stupid.
            final float[] pastX = pointerData.pastX;
            final float[] pastY = pointerData.pastY;
            
            final float oldestX = pastX[oldestTouchIndex];
            final float oldestY = pastY[oldestTouchIndex];
            final long oldestTime = pastTime[oldestTouchIndex];
            
            float accumX = 0;
            float accumY = 0;
            
            for (int i = 1; i < numTouches; i++) {
                final int touchIndex = (oldestTouchIndex + i) % NUM_PAST;
                final int duration = (int)(pastTime[touchIndex] - oldestTime);
                
                if (duration == 0) continue;
                
                float delta = pastX[touchIndex] - oldestX;
                float velocity = (delta / duration) * units; // pixels/frame.
                accumX = (accumX == 0) ? velocity : (accumX + velocity) * .5f;
            
                delta = pastY[touchIndex] - oldestY;
                velocity = (delta / duration) * units; // pixels/frame.
                accumY = (accumY == 0) ? velocity : (accumY + velocity) * .5f;
            }
            
            if (accumX < -maxVelocity) {
                accumX = - maxVelocity;
            } else if (accumX > maxVelocity) {
                accumX = maxVelocity;
            }
            
            if (accumY < -maxVelocity) {
                accumY = - maxVelocity;
            } else if (accumY > maxVelocity) {
                accumY = maxVelocity;
            }
            
            pointerData.xVelocity = accumX;
            pointerData.yVelocity = accumY;
            
            if (localLOGV) {
                Log.v(TAG, "[" + p + "] Pointer " + pointerData.id
                    + ": Y velocity=" + accumX +" X velocity=" + accumY + " N=" + numTouches);
            }
        }
    }
    
    /**
     * Retrieve the last computed X velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @return The previously computed X velocity.
     */
    public float getXVelocity() {
        PointerData pointerData = getPointerData(0);
        return pointerData != null ? pointerData.xVelocity : 0;
    }
    
    /**
     * Retrieve the last computed Y velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @return The previously computed Y velocity.
     */
    public float getYVelocity() {
        PointerData pointerData = getPointerData(0);
        return pointerData != null ? pointerData.yVelocity : 0;
    }
    
    /**
     * Retrieve the last computed X velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @param id Which pointer's velocity to return.
     * @return The previously computed X velocity.
     */
    public float getXVelocity(int id) {
        PointerData pointerData = getPointerData(id);
        return pointerData != null ? pointerData.xVelocity : 0;
    }
    
    /**
     * Retrieve the last computed Y velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @param id Which pointer's velocity to return.
     * @return The previously computed Y velocity.
     */
    public float getYVelocity(int id) {
        PointerData pointerData = getPointerData(id);
        return pointerData != null ? pointerData.yVelocity : 0;
    }
    
    private final PointerData getPointerData(int id) {
        final PointerData[] pointers = mPointers;
        final int numPointers = mNumPointers;
        for (int p = 0; p < numPointers; p++) {
            PointerData pointerData = pointers[p];
            if (pointerData.id == id) {
                return pointerData;
            }
        }
        return null;
    }
}
