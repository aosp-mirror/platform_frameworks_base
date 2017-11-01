/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server;

import static android.os.Process.getThreadPriority;
import static android.os.Process.myTid;
import static android.os.Process.setThreadPriority;

/**
 * Utility class to boost threads in sections where important locks are held.
 */
public class ThreadPriorityBooster {

    private volatile int mBoostToPriority;
    private final int mLockGuardIndex;

    private final ThreadLocal<PriorityState> mThreadState = new ThreadLocal<PriorityState>() {
        @Override protected PriorityState initialValue() {
            return new PriorityState();
        }
    };

    public ThreadPriorityBooster(int boostToPriority, int lockGuardIndex) {
        mBoostToPriority = boostToPriority;
        mLockGuardIndex = lockGuardIndex;
    }

    public void boost() {
        final int tid = myTid();
        final int prevPriority = getThreadPriority(tid);
        final PriorityState state = mThreadState.get();
        if (state.regionCounter == 0) {
            state.prevPriority = prevPriority;
            if (prevPriority > mBoostToPriority) {
                setThreadPriority(tid, mBoostToPriority);
            }
        }
        state.regionCounter++;
        if (LockGuard.ENABLED) {
            LockGuard.guard(mLockGuardIndex);
        }
    }

    public void reset() {
        final PriorityState state = mThreadState.get();
        state.regionCounter--;
        final int currentPriority = getThreadPriority(myTid());
        if (state.regionCounter == 0 && state.prevPriority != currentPriority) {
            setThreadPriority(myTid(), state.prevPriority);
        }
    }

    /**
     * Updates the priority we boost the threads to, and updates the current thread's priority if
     * necessary.
     */
    protected void setBoostToPriority(int priority) {

        // We don't care about the other threads here, as long as they see the update of this
        // variable immediately.
        mBoostToPriority = priority;
        final PriorityState state = mThreadState.get();
        final int tid = myTid();
        final int prevPriority = getThreadPriority(tid);
        if (state.regionCounter != 0 && prevPriority != priority) {
            setThreadPriority(tid, priority);
        }
    }

    private static class PriorityState {

        /**
         * Acts as counter for number of synchronized region that needs to acquire 'this' as a lock
         * the current thread is currently in. When it drops down to zero, we will no longer boost
         * the thread's priority.
         */
        int regionCounter;

        /**
         * The thread's previous priority before boosting.
         */
        int prevPriority;
    }
}