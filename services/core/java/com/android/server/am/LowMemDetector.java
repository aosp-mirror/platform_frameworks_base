/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.am;

import com.android.internal.annotations.GuardedBy;

/**
 * Detects low memory using PSI.
 *
 * If the kernel doesn't support PSI, then this class is not available.
 */
public final class LowMemDetector {
    private static final String TAG = "LowMemDetector";
    private final ActivityManagerService mAm;
    private final LowMemThread mLowMemThread;
    private boolean mAvailable;

    private final Object mPressureStateLock = new Object();

    @GuardedBy("mPressureStateLock")
    private int mPressureState = MEM_PRESSURE_NONE;

    /* getPressureState return values */
    public static final int MEM_PRESSURE_NONE = 0;
    public static final int MEM_PRESSURE_LOW = 1;
    public static final int MEM_PRESSURE_MEDIUM = 2;
    public static final int MEM_PRESSURE_HIGH = 3;

    LowMemDetector(ActivityManagerService am) {
        mAm = am;
        mLowMemThread = new LowMemThread();
        if (init() != 0) {
            mAvailable = false;
        } else {
            mAvailable = true;
            mLowMemThread.start();
        }
    }

    public boolean isAvailable() {
        return mAvailable;
    }

    /**
     * Returns the current mem factor.
     * Note that getMemFactor returns LowMemDetector.MEM_PRESSURE_XXX
     * which match ProcessStats.ADJ_MEM_FACTOR_XXX values. If they deviate
     * there should be conversion performed here to translate pressure state
     * into memFactor.
     */
    public int getMemFactor() {
        synchronized (mPressureStateLock) {
            return mPressureState;
        }
    }

    private native int init();
    private native int waitForPressure();

    private final class LowMemThread extends Thread {
        public void run() {

            while (true) {
                // sleep waiting for a PSI event
                int newPressureState = waitForPressure();
                if (newPressureState == -1) {
                    // epoll broke, tear this down
                    mAvailable = false;
                    break;
                }
                // got a PSI event? let's update lowmem info
                synchronized (mPressureStateLock) {
                    mPressureState = newPressureState;
                }
            }
        }
    }
}
