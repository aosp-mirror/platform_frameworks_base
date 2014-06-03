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

package com.android.systemui.recents;

import android.content.Context;

/**
 * A ref counted trigger that does some logic when the count is first incremented, or last
 * decremented.  Not thread safe as it's not currently needed.
 */
public class ReferenceCountedTrigger {

    Context mContext;
    int mCount;
    Runnable mFirstIncRunnable;
    Runnable mLastDecRunnable;
    Runnable mErrorRunnable;

    // Convenience runnables
    Runnable mIncrementRunnable = new Runnable() {
        @Override
        public void run() {
            increment();
        }
    };
    Runnable mDecrementRunnable = new Runnable() {
        @Override
        public void run() {
            decrement();
        }
    };

    public ReferenceCountedTrigger(Context context, Runnable firstIncRunnable,
                                   Runnable lastDecRunnable, Runnable errorRunanable) {
        mContext = context;
        mFirstIncRunnable = firstIncRunnable;
        mLastDecRunnable = lastDecRunnable;
        mErrorRunnable = errorRunanable;
    }

    /** Increments the ref count */
    public void increment() {
        if (mCount == 0 && mFirstIncRunnable != null) {
            mFirstIncRunnable.run();
        }
        mCount++;
    }

    /** Convenience method to increment this trigger as a runnable */
    public Runnable incrementAsRunnable() {
        return mIncrementRunnable;
    }

    /** Decrements the ref count */
    public void decrement() {
        mCount--;
        if (mCount == 0 && mLastDecRunnable != null) {
            mLastDecRunnable.run();
        } else if (mCount < 0) {
            if (mErrorRunnable != null) {
                mErrorRunnable.run();
            } else {
                new Throwable("Invalid ref count").printStackTrace();
                Console.logError(mContext, "Invalid ref count");
            }
        }
    }

    /** Convenience method to decrement this trigger as a runnable */
    public Runnable decrementAsRunnable() {
        return mDecrementRunnable;
    }

    /** Returns the current ref count */
    public int getCount() {
        return mCount;
    }
}
