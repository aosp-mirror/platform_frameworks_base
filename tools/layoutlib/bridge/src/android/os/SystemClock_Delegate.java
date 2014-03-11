/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.os;

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

/**
 * Delegate implementing the native methods of android.os.SystemClock
 *
 * Through the layoutlib_create tool, the original native methods of SystemClock have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * Because it's a stateless class to start with, there's no need to keep a {@link DelegateManager}
 * around to map int to instance of the delegate.
 *
 */
public class SystemClock_Delegate {
    private static long sBootTime = System.currentTimeMillis();
    private static long sBootTimeNano = System.nanoTime();

    /**
     * Returns milliseconds since boot, not counting time spent in deep sleep.
     * <b>Note:</b> This value may get reset occasionally (before it would
     * otherwise wrap around).
     *
     * @return milliseconds of non-sleep uptime since boot.
     */
    @LayoutlibDelegate
    /*package*/ static long uptimeMillis() {
        return System.currentTimeMillis() - sBootTime;
    }

    /**
     * Returns milliseconds since boot, including time spent in sleep.
     *
     * @return elapsed milliseconds since boot.
     */
    @LayoutlibDelegate
    /*package*/ static long elapsedRealtime() {
        return System.currentTimeMillis() - sBootTime;
    }

    /**
     * Returns nanoseconds since boot, including time spent in sleep.
     *
     * @return elapsed nanoseconds since boot.
     */
    @LayoutlibDelegate
    /*package*/ static long elapsedRealtimeNanos() {
        return System.nanoTime() - sBootTimeNano;
    }

    /**
     * Returns milliseconds running in the current thread.
     *
     * @return elapsed milliseconds in the thread
     */
    @LayoutlibDelegate
    /*package*/ static long currentThreadTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * Returns microseconds running in the current thread.
     *
     * @return elapsed microseconds in the thread
     *
     * @hide
     */
    @LayoutlibDelegate
    /*package*/ static long currentThreadTimeMicro() {
        return System.currentTimeMillis() * 1000;
    }

    /**
     * Returns current wall time in  microseconds.
     *
     * @return elapsed microseconds in wall time
     *
     * @hide
     */
    @LayoutlibDelegate
    /*package*/ static long currentTimeMicro() {
        return elapsedRealtime() * 1000;
    }
}
