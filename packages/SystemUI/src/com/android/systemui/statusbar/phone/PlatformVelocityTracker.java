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

import android.util.Pools;
import android.view.MotionEvent;
import android.view.VelocityTracker;

/**
 * An implementation of {@link VelocityTrackerInterface} using the platform-standard
 * {@link VelocityTracker}.
 */
public class PlatformVelocityTracker implements VelocityTrackerInterface {

    private static final Pools.SynchronizedPool<PlatformVelocityTracker> sPool =
            new Pools.SynchronizedPool<>(2);

    private VelocityTracker mTracker;

    public static PlatformVelocityTracker obtain() {
        PlatformVelocityTracker tracker = sPool.acquire();
        if (tracker == null) {
            tracker = new PlatformVelocityTracker();
        }
        tracker.setTracker(VelocityTracker.obtain());
        return tracker;
    }

    public void setTracker(VelocityTracker tracker) {
        mTracker = tracker;
    }

    @Override
    public void addMovement(MotionEvent event) {
        mTracker.addMovement(event);
    }

    @Override
    public void computeCurrentVelocity(int units) {
        mTracker.computeCurrentVelocity(units);
    }

    @Override
    public float getXVelocity() {
        return mTracker.getXVelocity();
    }

    @Override
    public float getYVelocity() {
        return mTracker.getYVelocity();
    }

    @Override
    public void recycle() {
        mTracker.recycle();
        sPool.release(this);
    }
}
