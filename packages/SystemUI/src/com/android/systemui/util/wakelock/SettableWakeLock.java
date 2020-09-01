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

package com.android.systemui.util.wakelock;

import java.util.Objects;

public class SettableWakeLock {

    private final WakeLock mInner;
    private final String mWhy;

    private boolean mAcquired;

    public SettableWakeLock(WakeLock inner, String why) {
        Objects.requireNonNull(inner, "inner wakelock required");

        mInner = inner;
        mWhy = why;
    }

    public synchronized boolean isAcquired() {
        return mAcquired;
    }

    public synchronized void setAcquired(boolean acquired) {
        if (mAcquired != acquired) {
            if (acquired) {
                mInner.acquire(mWhy);
            } else {
                mInner.release(mWhy);
            }
            mAcquired = acquired;
        }
    }
}
