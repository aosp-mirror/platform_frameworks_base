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

import android.content.Context;
import android.os.Handler;

import com.android.systemui.Flags;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;

import dagger.Lazy;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

/**
 * A wake lock that has a built in delay when releasing to give the framebuffer time to update.
 */
public class DelayedWakeLock implements WakeLock {

    private static final String TO_STRING_PREFIX = "[DelayedWakeLock] ";
    private static final long RELEASE_DELAY_MS = 100;

    private final Handler mHandler;
    private final WakeLock mInner;

    @AssistedInject
    public DelayedWakeLock(@Background Lazy<Handler> bgHandler,
                           @Main Lazy<Handler> mainHandler,
                           Context context, WakeLockLogger logger,
            @Assisted String tag) {
        mInner = WakeLock.createPartial(context, logger, tag);
        mHandler = Flags.delayedWakelockReleaseOnBackgroundThread() ? bgHandler.get()
                : mainHandler.get();
    }

    @Override
    public void acquire(String why) {
        mInner.acquire(why);
    }

    @Override
    public void release(String why) {
        mHandler.postDelayed(() -> mInner.release(why), RELEASE_DELAY_MS);
    }

    @Override
    public Runnable wrap(Runnable r) {
        return WakeLock.wrapImpl(this, r);
    }

    @Override
    public String toString() {
        return TO_STRING_PREFIX + mInner;
    }

    /**
     * Factory to create the instance of DelayedWakeLock class.
     */
    @AssistedFactory
    public interface Factory {
        /** creates the instance of DelayedWakeLock class. */
        DelayedWakeLock create(String tag);
    }
}