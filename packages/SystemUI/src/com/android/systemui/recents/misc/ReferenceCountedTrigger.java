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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;

import java.util.ArrayList;

/**
 * A ref counted trigger that does some logic when the count is first incremented, or last
 * decremented.  Not thread safe as it's not currently needed.
 */
public class ReferenceCountedTrigger {

    int mCount;
    ArrayList<Runnable> mFirstIncRunnables = new ArrayList<>();
    ArrayList<Runnable> mLastDecRunnables = new ArrayList<>();
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

    public ReferenceCountedTrigger() {
        this(null, null, null);
    }

    public ReferenceCountedTrigger(Runnable firstIncRunnable, Runnable lastDecRunnable,
            Runnable errorRunanable) {
        if (firstIncRunnable != null) mFirstIncRunnables.add(firstIncRunnable);
        if (lastDecRunnable != null) mLastDecRunnables.add(lastDecRunnable);
        mErrorRunnable = errorRunanable;
    }

    /** Increments the ref count */
    public void increment() {
        if (mCount == 0 && !mFirstIncRunnables.isEmpty()) {
            int numRunnables = mFirstIncRunnables.size();
            for (int i = 0; i < numRunnables; i++) {
                mFirstIncRunnables.get(i).run();
            }
        }
        mCount++;
    }

    /** Convenience method to increment this trigger as a runnable */
    public Runnable incrementAsRunnable() {
        return mIncrementRunnable;
    }

    /** Adds a runnable to the last-decrement runnables list. */
    public void addLastDecrementRunnable(Runnable r) {
        mLastDecRunnables.add(r);
    }

    /** Decrements the ref count */
    public void decrement() {
        mCount--;
        if (mCount == 0) {
            flushLastDecrementRunnables();
        } else if (mCount < 0) {
            if (mErrorRunnable != null) {
                mErrorRunnable.run();
            } else {
                throw new RuntimeException("Invalid ref count");
            }
        }
    }

    /**
     * Runs and clears all the last-decrement runnables now.
     */
    public void flushLastDecrementRunnables() {
        if (!mLastDecRunnables.isEmpty()) {
            int numRunnables = mLastDecRunnables.size();
            for (int i = 0; i < numRunnables; i++) {
                mLastDecRunnables.get(i).run();
            }
        }
        mLastDecRunnables.clear();
    }

    /**
     * Convenience method to decrement this trigger as a animator listener.  This listener is
     * guarded to prevent being called back multiple times, and will trigger a decrement once and
     * only once.
     */
    public Animator.AnimatorListener decrementOnAnimationEnd() {
        return new AnimatorListenerAdapter() {
            private boolean hasEnded;

            @Override
            public void onAnimationEnd(Animator animation) {
                if (hasEnded) return;
                decrement();
                hasEnded = true;
            }
        };
    }

    /** Returns the current ref count */
    public int getCount() {
        return mCount;
    }
}
