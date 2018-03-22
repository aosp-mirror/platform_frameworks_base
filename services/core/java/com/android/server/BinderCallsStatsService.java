/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server;

import android.os.Binder;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.internal.os.BinderCallsStats;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class BinderCallsStatsService extends Binder {

    private static final String TAG = "BinderCallsStatsService";

    private static final String PERSIST_SYS_BINDER_CPU_STATS_TRACKING
            = "persist.sys.binder_cpu_stats_tracking";

    public static void start() {
        BinderCallsStatsService service = new BinderCallsStatsService();
        ServiceManager.addService("binder_calls_stats", service);
        // TODO Enable by default on eng/userdebug builds
        boolean trackingEnabledDefault = false;
        boolean trackingEnabled = SystemProperties.getBoolean(PERSIST_SYS_BINDER_CPU_STATS_TRACKING,
                trackingEnabledDefault);

        if (trackingEnabled) {
            Slog.i(TAG, "Enabled CPU usage tracking for binder calls. Controlled by "
                    + PERSIST_SYS_BINDER_CPU_STATS_TRACKING);
            BinderCallsStats.getInstance().setTrackingEnabled(true);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        BinderCallsStats.getInstance().dump(pw);
    }
}
