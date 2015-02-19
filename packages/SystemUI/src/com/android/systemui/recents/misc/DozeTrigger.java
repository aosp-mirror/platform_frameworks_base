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

/**
 * A dozer is a class that fires a trigger after it falls asleep.  You can occasionally poke it to
 * wake it up, but it will fall asleep if left untouched.
 */
public class DozeTrigger {

    Handler mHandler;

    boolean mIsDozing;
    boolean mHasTriggered;
    int mDozeDurationSeconds;
    Runnable mSleepRunnable;

    // Sleep-runnable
    Runnable mDozeRunnable = new Runnable() {
        @Override
        public void run() {
            mSleepRunnable.run();
            mIsDozing = false;
            mHasTriggered = true;
        }
    };

    public DozeTrigger(int dozeDurationSeconds, Runnable sleepRunnable) {
        mHandler = new Handler();
        mDozeDurationSeconds = dozeDurationSeconds;
        mSleepRunnable = sleepRunnable;
    }

    /** Starts dozing. This also resets the trigger flag. */
    public void startDozing() {
        forcePoke();
        mHasTriggered = false;
    }

    /** Stops dozing. */
    public void stopDozing() {
        mHandler.removeCallbacks(mDozeRunnable);
        mIsDozing = false;
    }

    /** Poke this dozer to wake it up for a little bit, if it is dozing. */
    public void poke() {
        if (mIsDozing) {
            forcePoke();
        }
    }

    /** Poke this dozer to wake it up for a little bit. */
    void forcePoke() {
        mHandler.removeCallbacks(mDozeRunnable);
        mHandler.postDelayed(mDozeRunnable, mDozeDurationSeconds * 1000);
        mIsDozing = true;
    }

    /** Returns whether we are dozing or not. */
    public boolean isDozing() {
        return mIsDozing;
    }

    /** Returns whether the trigger has fired at least once. */
    public boolean hasTriggered() {
        return mHasTriggered;
    }

    /** Resets the doze trigger state. */
    public void resetTrigger() {
        mHasTriggered = false;
    }
}
