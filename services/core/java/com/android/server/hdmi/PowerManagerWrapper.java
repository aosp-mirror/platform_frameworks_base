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

import android.content.Context;
import android.os.PowerManager;

/**
 * Abstraction around {@link PowerManager} to allow faking PowerManager in tests.
 */
public class PowerManagerWrapper {
    private static final String TAG = "PowerManagerWrapper";

    private final PowerManager mPowerManager;

    public PowerManagerWrapper(Context context) {
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    boolean isInteractive() {
        return mPowerManager.isInteractive();
    }

    void wakeUp(long time, int reason, String details) {
        mPowerManager.wakeUp(time, reason, details);
    }

    void goToSleep(long time, int reason, int flags) {
        mPowerManager.goToSleep(time, reason, flags);
    }

    WakeLockWrapper newWakeLock(int levelAndFlags, String tag) {
        return new DefaultWakeLockWrapper(mPowerManager.newWakeLock(levelAndFlags, tag));
    }

    /**
     * "Default" wrapper for {@link PowerManager.WakeLock}, as opposed to a "Fake" wrapper for
     * testing - see {@link FakePowerManagerWrapper.FakeWakeLockWrapper}.
     *
     * Stores an instance of {@link PowerManager.WakeLock} and directly passes method calls to that
     * instance.
     */
    public static class DefaultWakeLockWrapper implements WakeLockWrapper {

        private static final String TAG = "DefaultWakeLockWrapper";

        private final PowerManager.WakeLock mWakeLock;

        private DefaultWakeLockWrapper(PowerManager.WakeLock wakeLock) {
            mWakeLock = wakeLock;
        }

        @Override
        public void acquire(long timeout) {
            mWakeLock.acquire(timeout);
        }

        @Override
        public void acquire() {
            mWakeLock.acquire();
        }

        /**
         * @throws RuntimeException WakeLock can throw this exception if it is not released
         * successfully.
         */
        @Override
        public void release() throws RuntimeException {
            mWakeLock.release();
        }

        @Override
        public boolean isHeld() {
            return mWakeLock.isHeld();
        }

        @Override
        public void setReferenceCounted(boolean value) {
            mWakeLock.setReferenceCounted(value);
        }
    }
}
