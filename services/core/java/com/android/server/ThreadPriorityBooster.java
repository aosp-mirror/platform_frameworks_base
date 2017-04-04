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

import android.os.Process;

/**
 * Utility class to boost threads in sections where important locks are held.
 */
public class ThreadPriorityBooster {

    private final int mBoostToPriority;
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
        final int tid = Process.myTid();
        final int prevPriority = Process.getThreadPriority(tid);
        PriorityState state = mThreadState.get();
        if (state.regionCounter == 0 && prevPriority > mBoostToPriority) {
            state.prevPriority = prevPriority;
            Process.setThreadPriority(tid, mBoostToPriority);
        }
        state.regionCounter++;
        if (LockGuard.ENABLED) {
            LockGuard.guard(mLockGuardIndex);
        }
    }

    public void reset() {
        PriorityState state = mThreadState.get();
        state.regionCounter--;
        if (state.regionCounter == 0 && state.prevPriority > mBoostToPriority) {
            Process.setThreadPriority(Process.myTid(), state.prevPriority);
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