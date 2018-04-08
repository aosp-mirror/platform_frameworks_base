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
 * limitations under the License.
 */

package com.android.systemui.util.wakelock;

import android.os.Handler;

/**
 * A wake lock that has a built in delay when releasing to give the framebuffer time to update.
 */
public class DelayedWakeLock implements WakeLock {

    private static final long RELEASE_DELAY_MS = 140;

    private final Handler mHandler;
    private final WakeLock mInner;
    private final Runnable mRelease;

    public DelayedWakeLock(Handler h, WakeLock inner) {
        mHandler = h;
        mInner = inner;
        mRelease = mInner::release;
    }

    @Override
    public void acquire() {
        mInner.acquire();
    }

    @Override
    public void release() {
        mHandler.postDelayed(mRelease, RELEASE_DELAY_MS);
    }

    @Override
    public Runnable wrap(Runnable r) {
        return WakeLock.wrapImpl(this, r);
    }
}
