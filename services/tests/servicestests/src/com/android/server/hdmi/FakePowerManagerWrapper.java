/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.hdmi;

import android.annotation.NonNull;
import android.content.Context;

/**
 * Fake class which stubs PowerManagerWrapper (useful for testing).
 */
final class FakePowerManagerWrapper extends PowerManagerWrapper {
    private boolean mInteractive;
    private WakeLockWrapper mWakeLock;
    private boolean mWasWakeLockInstanceCreated = false;


    FakePowerManagerWrapper(@NonNull Context context) {
        this(context, null);
    }

    FakePowerManagerWrapper(@NonNull Context context, WakeLockWrapper wakeLock) {
        super(context);
        mInteractive = true;
        mWakeLock = wakeLock;
    }

    @Override
    boolean isInteractive() {
        return mInteractive;
    }

    void setInteractive(boolean interactive) {
        mInteractive = interactive;
    }

    @Override
    void wakeUp(long time, int reason, String details) {
        mInteractive = true;
        return;
    }

    @Override
    void goToSleep(long time, int reason, int flags) {
        mInteractive = false;
        return;
    }

    @Override
    WakeLockWrapper newWakeLock(int levelAndFlags, String tag) {
        if (mWakeLock == null) {
            mWakeLock = new FakeWakeLockWrapper();
        }
        mWasWakeLockInstanceCreated = true;
        return mWakeLock;
    }

    boolean wasWakeLockInstanceCreated() {
        return mWasWakeLockInstanceCreated;
    }

    /**
     * "Fake" wrapper for {@link PowerManager.WakeLock}, as opposed to a "Default" wrapper used by
     * the framework - see {@link PowerManagerWrapper.DefaultWakeLockWrapper}.
     */
    public static class FakeWakeLockWrapper implements WakeLockWrapper {
        private static final String TAG = "FakeWakeLockWrapper";
        private boolean mWakeLockHeld = false;

        @Override
        public void acquire(long timeout) {
            mWakeLockHeld = true;
        }

        @Override
        public void acquire() {
            mWakeLockHeld = true;
        }

        @Override
        public void release() {
            mWakeLockHeld = false;
        }

        @Override
        public boolean isHeld() {
            return mWakeLockHeld;
        }

        @Override
        public void setReferenceCounted(boolean value) {}
    }
}
