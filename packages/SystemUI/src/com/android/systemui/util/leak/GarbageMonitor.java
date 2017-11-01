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

package com.android.systemui.util.leak;


import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.support.annotation.VisibleForTesting;

import com.android.systemui.Dependency;
import com.android.systemui.SystemUI;

public class GarbageMonitor {

    private static final String TAG = "GarbageMonitor";

    private static final long GARBAGE_INSPECTION_INTERVAL = 5 * 60 * 1000; // 5min
    private static final int GARBAGE_ALLOWANCE = 5;

    private final Handler mHandler;
    private final TrackedGarbage mTrackedGarbage;
    private final LeakReporter mLeakReporter;

    public GarbageMonitor(Looper bgLooper, LeakDetector leakDetector,
            LeakReporter leakReporter) {
        mHandler = bgLooper != null ? new Handler(bgLooper): null;
        mTrackedGarbage = leakDetector.getTrackedGarbage();
        mLeakReporter = leakReporter;
    }

    public void start() {
        if (mTrackedGarbage == null) {
            return;
        }

        scheduleInspectGarbage(this::inspectGarbage);
    }

    @VisibleForTesting
    void scheduleInspectGarbage(Runnable runnable) {
        mHandler.postDelayed(runnable, GARBAGE_INSPECTION_INTERVAL);
    }

    private void inspectGarbage() {
        if (mTrackedGarbage.countOldGarbage() > GARBAGE_ALLOWANCE) {
            Runtime.getRuntime().gc();

            // Allow some time to for ReferenceQueue to catch up.
            scheduleReinspectGarbage(this::reinspectGarbageAfterGc);
        }
        scheduleInspectGarbage(this::inspectGarbage);
    }

    @VisibleForTesting
    void scheduleReinspectGarbage(Runnable runnable) {
        mHandler.postDelayed(runnable, (long) 100);
    }

    private void reinspectGarbageAfterGc() {
        int count = mTrackedGarbage.countOldGarbage();
        if (count > GARBAGE_ALLOWANCE) {
            mLeakReporter.dumpLeak(count);
        }
    }

    public static class Service extends SystemUI {

        // TODO(b/35345376): Turn this back on for debuggable builds after known leak fixed.
        private static final boolean ENABLED = Build.IS_DEBUGGABLE
                && SystemProperties.getBoolean("debug.enable_leak_reporting", false);
        private static final String FORCE_ENABLE = "sysui_force_garbage_monitor";

        private GarbageMonitor mGarbageMonitor;

        @Override
        public void start() {
            boolean forceEnable = Settings.Secure.getInt(mContext.getContentResolver(),
                    FORCE_ENABLE, 0) != 0;
            if (!ENABLED && !forceEnable) {
                return;
            }
            mGarbageMonitor = Dependency.get(GarbageMonitor.class);
            mGarbageMonitor.start();
        }
    }
}
