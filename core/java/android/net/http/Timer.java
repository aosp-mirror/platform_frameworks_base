/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.net.http;

import android.os.SystemClock;

/**
 * {@hide}
 * Debugging tool
 */
class Timer {

    private long mStart;
    private long mLast;

    public Timer() {
        mStart = mLast = SystemClock.uptimeMillis();
    }

    public void mark(String message) {
        long now = SystemClock.uptimeMillis();
        if (HttpLog.LOGV) {
            HttpLog.v(message + " " + (now - mLast) + " total " + (now - mStart));
        }
        mLast = now;
    }
}
