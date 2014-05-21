/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.util.Log;
import android.util.Pools;
import android.view.MotionEvent;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * A very simple low-pass velocity filter for motion events for noisy touch screens.
 */
public class NoisyVelocityTracker implements VelocityTrackerInterface {

    private static final Pools.SynchronizedPool<NoisyVelocityTracker> sNoisyPool =
            new Pools.SynchronizedPool<>(2);

    private static final float DECAY = 0.75f;
    private static final boolean DEBUG = false;

    private final int MAX_EVENTS = 8;
    private ArrayDeque<MotionEventCopy> mEventBuf = new ArrayDeque<MotionEventCopy>(MAX_EVENTS);
    private float mVX, mVY = 0;

    private static class MotionEventCopy {
        public MotionEventCopy(float x2, float y2, long eventTime) {
            this.x = x2;
            this.y = y2;
            this.t = eventTime;
        }
        float x, y;
        long t;
    }

    public static NoisyVelocityTracker obtain() {
        NoisyVelocityTracker instance = sNoisyPool.acquire();
        return (instance != null) ? instance : new NoisyVelocityTracker();
    }

    private NoisyVelocityTracker() {
    }

    public void addMovement(MotionEvent event) {
        if (mEventBuf.size() == MAX_EVENTS) {
            mEventBuf.remove();
        }
        mEventBuf.add(new MotionEventCopy(event.getX(), event.getY(), event.getEventTime()));
    }

    public void computeCurrentVelocity(int units) {
        if (NoisyVelocityTracker.DEBUG) {
            Log.v("FlingTracker", "computing velocities for " + mEventBuf.size() + " events");
        }
        mVX = mVY = 0;
        MotionEventCopy last = null;
        int i = 0;
        float totalweight = 0f;
        float weight = 10f;
        for (final Iterator<MotionEventCopy> iter = mEventBuf.iterator();
                iter.hasNext();) {
            final MotionEventCopy event = iter.next();
            if (last != null) {
                final float dt = (float) (event.t - last.t) / units;
                final float dx = (event.x - last.x);
                final float dy = (event.y - last.y);
                if (NoisyVelocityTracker.DEBUG) {
                    Log.v("FlingTracker", String.format(
                            "   [%d] (t=%d %.1f,%.1f) dx=%.1f dy=%.1f dt=%f vx=%.1f vy=%.1f",
                            i, event.t, event.x, event.y,
                            dx, dy, dt,
                            (dx/dt),
                            (dy/dt)
                    ));
                }
                if (event.t == last.t) {
                    // Really not sure what to do with events that happened at the same time,
                    // so we'll skip subsequent events.
                    continue;
                }
                mVX += weight * dx / dt;
                mVY += weight * dy / dt;
                totalweight += weight;
                weight *= DECAY;
            }
            last = event;
            i++;
        }
        if (totalweight > 0) {
            mVX /= totalweight;
            mVY /= totalweight;
        } else {
            // so as not to contaminate the velocities with NaN
            mVX = mVY = 0;
        }

        if (NoisyVelocityTracker.DEBUG) {
            Log.v("FlingTracker", "computed: vx=" + mVX + " vy=" + mVY);
        }
    }

    public float getXVelocity() {
        if (Float.isNaN(mVX) || Float.isInfinite(mVX)) {
            mVX = 0;
        }
        return mVX;
    }

    public float getYVelocity() {
        if (Float.isNaN(mVY) || Float.isInfinite(mVX)) {
            mVY = 0;
        }
        return mVY;
    }

    public void recycle() {
        mEventBuf.clear();
        sNoisyPool.release(this);
    }
}
