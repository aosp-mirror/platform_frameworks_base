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
 * limitations under the License.
 */

package com.android.systemui.recents.misc;

import android.os.Handler;
import android.view.ViewDebug;

/**
 * A dozer is a class that fires a trigger after it falls asleep.
 * You can occasionally poke the trigger to wake it up, but it will fall asleep if left untouched.
 */
public class DozeTrigger {

    Handler mHandler;

    @ViewDebug.ExportedProperty(category="recents")
    boolean mIsDozing;
    @ViewDebug.ExportedProperty(category="recents")
    boolean mIsAsleep;
    @ViewDebug.ExportedProperty(category="recents")
    int mDozeDurationMilliseconds;
    Runnable mOnSleepRunnable;

    // Sleep-runnable
    Runnable mDozeRunnable = new Runnable() {
        @Override
        public void run() {
            mIsDozing = false;
            mIsAsleep = true;
            mOnSleepRunnable.run();
        }
    };

    public DozeTrigger(int dozeDurationMilliseconds, Runnable onSleepRunnable) {
        mHandler = new Handler();
        mDozeDurationMilliseconds = dozeDurationMilliseconds;
        mOnSleepRunnable = onSleepRunnable;
    }

    /**
     * Starts dozing and queues the onSleepRunnable to be called. This also resets the trigger flag.
     */
    public void startDozing() {
        forcePoke();
        mIsAsleep = false;
    }

    /**
     * Stops dozing and prevents the onSleepRunnable from being called.
     */
    public void stopDozing() {
        mHandler.removeCallbacks(mDozeRunnable);
        mIsDozing = false;
        mIsAsleep = false;
    }

    /**
     * Updates the duration that we have to wait until dozing triggers.
     */
    public void setDozeDuration(int duration) {
        mDozeDurationMilliseconds = duration;
    }

    /**
     * Poke this dozer to wake it up if it is dozing, delaying the onSleepRunnable from being
     * called for a for the doze duration.
     */
    public void poke() {
        if (mIsDozing) {
            forcePoke();
        }
    }

    /**
     * Poke this dozer to wake it up even if it is not currently dozing.
     */
    void forcePoke() {
        mHandler.removeCallbacks(mDozeRunnable);
        mHandler.postDelayed(mDozeRunnable, mDozeDurationMilliseconds);
        mIsDozing = true;
    }

    /** Returns whether we are dozing or not. */
    public boolean isDozing() {
        return mIsDozing;
    }

    /** Returns whether the trigger has fired at least once. */
    public boolean isAsleep() {
        return mIsAsleep;
    }
}
