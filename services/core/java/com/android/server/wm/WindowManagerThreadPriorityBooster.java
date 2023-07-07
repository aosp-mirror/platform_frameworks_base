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

package com.android.server.wm;

import static android.os.Process.THREAD_PRIORITY_DISPLAY;
import static android.os.Process.THREAD_PRIORITY_TOP_APP_BOOST;
import static android.os.Process.myTid;
import static android.os.Process.setThreadPriority;

import static com.android.server.LockGuard.INDEX_WINDOW;

import com.android.internal.annotations.GuardedBy;
import com.android.server.AnimationThread;
import com.android.server.ThreadPriorityBooster;

/**
 * Window manager version of {@link ThreadPriorityBooster} that boosts even more during app
 * transitions.
 */
class WindowManagerThreadPriorityBooster extends ThreadPriorityBooster {

    private final Object mLock = new Object();

    private final int mAnimationThreadId;
    private final int mSurfaceAnimationThreadId;

    @GuardedBy("mLock")
    private boolean mAppTransitionRunning;
    @GuardedBy("mLock")
    private boolean mBoundsAnimationRunning;

    WindowManagerThreadPriorityBooster() {
        super(THREAD_PRIORITY_DISPLAY, INDEX_WINDOW);
        mAnimationThreadId = AnimationThread.get().getThreadId();
        mSurfaceAnimationThreadId = SurfaceAnimationThread.get().getThreadId();
    }

    @Override
    public void boost() {

        // Do not boost the animation threads. As the animation threads are changing priorities,
        // boosting it might mess up the priority because we reset it the the previous priority.
        final int myTid = myTid();
        if (myTid == mAnimationThreadId || myTid == mSurfaceAnimationThreadId) {
            return;
        }
        super.boost();
    }

    @Override
    public void reset() {

        // See comment in boost().
        final int myTid = myTid();
        if (myTid == mAnimationThreadId || myTid == mSurfaceAnimationThreadId) {
            return;
        }
        super.reset();
    }

    void setAppTransitionRunning(boolean running) {
        synchronized (mLock) {
            if (mAppTransitionRunning != running) {
                mAppTransitionRunning = running;
                updatePriorityLocked();
            }
        }
    }

    void setBoundsAnimationRunning(boolean running) {
        synchronized (mLock) {
            if (mBoundsAnimationRunning != running) {
                mBoundsAnimationRunning = running;
                updatePriorityLocked();
            }
        }
    }

    @GuardedBy("mLock")
    private void updatePriorityLocked() {
        int priority = (mAppTransitionRunning || mBoundsAnimationRunning)
                ? THREAD_PRIORITY_TOP_APP_BOOST : THREAD_PRIORITY_DISPLAY;
        setBoostToPriority(priority);
        setThreadPriority(mAnimationThreadId, priority);
        setThreadPriority(mSurfaceAnimationThreadId, priority);
    }
}
