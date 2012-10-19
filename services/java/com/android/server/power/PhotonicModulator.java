/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.power;

import android.util.Slog;

import com.android.server.LightsService;

import java.util.concurrent.Executor;

/**
 * Sets the value of a light asynchronously.
 *
 * This is done to avoid blocking the looper on devices for which
 * setting the backlight brightness is especially slow.
 */
final class PhotonicModulator {
    private static final String TAG = "PhotonicModulator";
    private static final boolean DEBUG = false;

    private static final int UNKNOWN_LIGHT_VALUE = -1;

    private final Object mLock = new Object();

    private final LightsService.Light mLight;
    private final Executor mExecutor;
    private final SuspendBlocker mSuspendBlocker;

    private boolean mPendingChange;
    private int mPendingLightValue;
    private int mActualLightValue;

    public PhotonicModulator(Executor executor, LightsService.Light light,
            SuspendBlocker suspendBlocker) {
        mExecutor = executor;
        mLight = light;
        mSuspendBlocker = suspendBlocker;
        mPendingLightValue = UNKNOWN_LIGHT_VALUE;
        mActualLightValue = UNKNOWN_LIGHT_VALUE;
    }

    /**
     * Sets the backlight brightness, synchronously or asynchronously.
     *
     * @param lightValue The new light value, from 0 to 255.
     * @param sync If true, waits for the brightness change to complete before returning.
     */
    public void setBrightness(int lightValue, boolean sync) {
        synchronized (mLock) {
            if (lightValue != mPendingLightValue) {
                mPendingLightValue = lightValue;
                if (DEBUG) {
                    Slog.d(TAG, "Enqueuing request to change brightness to " + lightValue);
                }
                if (!mPendingChange) {
                    mPendingChange = true;
                    mSuspendBlocker.acquire();
                    mExecutor.execute(mTask);
                }
            }
            if (sync) {
                while (mPendingChange) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ex) {
                        // ignore it
                    }
                }
            }
        }
    }

    private final Runnable mTask = new Runnable() {
        @Override
        public void run() {
            for (;;) {
                final int newLightValue;
                synchronized (mLock) {
                    newLightValue = mPendingLightValue;
                    if (newLightValue == mActualLightValue) {
                        mSuspendBlocker.release();
                        mPendingChange = false;
                        mLock.notifyAll();
                        return;
                    }
                    mActualLightValue = newLightValue;
                }
                if (DEBUG) {
                    Slog.d(TAG, "Setting brightness to " + newLightValue);
                }
                mLight.setBrightness(newLightValue);
            }
        }
    };
}
