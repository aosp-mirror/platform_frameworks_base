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
    private static final String TAG = "VelocityTracker";
    private static final boolean DEBUG = false;
    private static final boolean localLOGV = DEBUG || Config.LOGV;

    private static final int NUM_PAST = 10;
    private static final int MAX_AGE_MILLISECONDS = 200;
    
    private static final int POINTER_POOL_CAPACITY = 20;

    private static final Pool<VelocityTracker> sPool = Pools.synchronizedPool(
            Pools.finitePool(new PoolableManager<VelocityTracker>() {
                public VelocityTracker newInstance() {
                    return new VelocityTracker();
                }

                public void onAcquired(VelocityTracker element) {
                }

                public void onReleased(VelocityTracker element) {
                    element.clear();
                }
            }, 2));
    
    private static Pointer sRecycledPointerListHead;
    private static int sRecycledPointerCount;
    
    private static final class Pointer {
        public Pointer next;
        
        public int id;
        public float xVelocity;
        public float yVelocity;
        
        public final float[] pastX = new float[NUM_PAST];
        public final float[] pastY = new float[NUM_PAST];
        public final long[] pastTime = new long[NUM_PAST]; // uses Long.MIN_VALUE as a sentinel
        
        public int generation;
    }
    
    private Pointer mPointerListHead; // sorted by id in increasing order
    private int mLastTouchIndex;
    private int mGeneration;

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
        releasePointerList(mPointerListHead);
        
        mPointerListHead = null;
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
        final int generation = mGeneration++;
        
        mLastTouchIndex = finalTouchIndex;

        // Update pointer data.
        Pointer previousPointer = null;
        for (int i = 0; i < pointerCount; i++){
            final int pointerId = ev.getPointerId(i);
            
            // Find the pointer data for this pointer id.
            // This loop is optimized for the common case where pointer ids in the event
            // are in sorted order.  However, we check for this case explicitly and
            // perform a full linear scan from the start if needed.
            Pointer nextPointer;
            if (previousPointer == null || pointerId < previousPointer.id) {
                previousPointer = null;
                nextPointer = mPointerListHead;
            } else {
                nextPointer = previousPointer.next;
            }
            
            final Pointer pointer;
            for (;;) {
                if (nextPointer != null) {
                    final int nextPointerId = nextPointer.id;
                    if (nextPointerId == pointerId) {
                        pointer = nextPointer;
                        break;
                    }
                    if (nextPointerId < pointerId) {
                        nextPointer = nextPointer.next;
                        continue;
                    }
                }
                
                // Pointer went down.  Add it to the list.
                // Write a sentinel at the end of the pastTime trace so we will be able to
                // tell when the trace started.
                pointer = obtainPointer();
                pointer.id = pointerId;
                pointer.pastTime[lastTouchIndex] = Long.MIN_VALUE;
                pointer.next = nextPointer;
                if (previousPointer == null) {
                    mPointerListHead = pointer;
                } else {
                    previousPointer.next = pointer;
                }
                break;
            }
            
            pointer.generation = generation;
            previousPointer = pointer;
            
            final float[] pastX = pointer.pastX;
            final float[] pastY = pointer.pastY;
            final long[] pastTime = pointer.pastTime;
            
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
        
        // Find removed pointers.
        previousPointer = null;
        for (Pointer pointer = mPointerListHead; pointer != null; ) {
            final Pointer nextPointer = pointer.next;
            if (pointer.generation != generation) {
                // Pointer went up.  Remove it from the list.
                if (previousPointer == null) {
                    mPointerListHead = nextPointer;
                } else {
                    previousPointer.next = nextPointer;
                }
                releasePointer(pointer);
            } else {
                previousPointer = pointer;
            }
            pointer = nextPointer;
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
        final int lastTouchIndex = mLastTouchIndex;
        
        for (Pointer pointer = mPointerListHead; pointer != null; pointer = pointer.next) {
            final long[] pastTime = pointer.pastTime;
            
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
            final float[] pastX = pointer.pastX;
            final float[] pastY = pointer.pastY;
            
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
            
            pointer.xVelocity = accumX;
            pointer.yVelocity = accumY;
            
            if (localLOGV) {
                Log.v(TAG, "Pointer " + pointer.id
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
        Pointer pointer = getPointer(0);
        return pointer != null ? pointer.xVelocity : 0;
    }
    
    /**
     * Retrieve the last computed Y velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @return The previously computed Y velocity.
     */
    public float getYVelocity() {
        Pointer pointer = getPointer(0);
        return pointer != null ? pointer.yVelocity : 0;
    }
    
    /**
     * Retrieve the last computed X velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @param id Which pointer's velocity to return.
     * @return The previously computed X velocity.
     */
    public float getXVelocity(int id) {
        Pointer pointer = getPointer(id);
        return pointer != null ? pointer.xVelocity : 0;
    }
    
    /**
     * Retrieve the last computed Y velocity.  You must first call
     * {@link #computeCurrentVelocity(int)} before calling this function.
     * 
     * @param id Which pointer's velocity to return.
     * @return The previously computed Y velocity.
     */
    public float getYVelocity(int id) {
        Pointer pointer = getPointer(id);
        return pointer != null ? pointer.yVelocity : 0;
    }
    
    private final Pointer getPointer(int id) {
        for (Pointer pointer = mPointerListHead; pointer != null; pointer = pointer.next) {
            if (pointer.id == id) {
                return pointer;
            }
        }
        return null;
    }
    
    private static final Pointer obtainPointer() {
        synchronized (sPool) {
            if (sRecycledPointerCount != 0) {
                Pointer element = sRecycledPointerListHead;
                sRecycledPointerCount -= 1;
                sRecycledPointerListHead = element.next;
                element.next = null;
                return element;
            }
        }
        return new Pointer();
    }
    
    private static final void releasePointer(Pointer pointer) {
        synchronized (sPool) {
            if (sRecycledPointerCount < POINTER_POOL_CAPACITY) {
                pointer.next = sRecycledPointerListHead;
                sRecycledPointerCount += 1;
                sRecycledPointerListHead = pointer;
            }
        }
    }
    
    private static final void releasePointerList(Pointer pointer) {
        if (pointer != null) {
            synchronized (sPool) {
                int count = sRecycledPointerCount;
                if (count >= POINTER_POOL_CAPACITY) {
                    return;
                }
                
                Pointer tail = pointer;
                for (;;) {
                    count += 1;
                    if (count >= POINTER_POOL_CAPACITY) {
                        break;
                    }
                    
                    Pointer next = tail.next;
                    if (next == null) {
                        break;
                    }
                    tail = next;
                }

                tail.next = sRecycledPointerListHead;
                sRecycledPointerCount = count;
                sRecycledPointerListHead = pointer;
            }
        }
    }
}
