/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.webkit;

import android.os.SystemClock;
import android.util.Log;

class PerfChecker {

    private long mTime;
    private static final long mResponseThreshold = 2000;    // 2s

    public PerfChecker() {
        if (false) {
            mTime = SystemClock.uptimeMillis();
        }
    }

    /**
     * @param what log string
     * Logs given string if mResponseThreshold time passed between either
     * instantiation or previous responseAlert call
     */
    public void responseAlert(String what) {
        if (false) {
            long upTime = SystemClock.uptimeMillis();
            long time =  upTime - mTime;
            if (time > mResponseThreshold) {
                Log.w("webkit", what + " used " + time + " ms");
            }
            // Reset mTime, to permit reuse
            mTime = upTime;
        }
    }
}
