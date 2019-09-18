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

package com.android.server.compat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.compat.ChangeReporter;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.util.DumpUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * System server internal API for gating and reporting compatibility changes.
 */
public class PlatformCompat extends IPlatformCompat.Stub {

    private static final String TAG = "Compatibility";

    private final Context mContext;
    private final ChangeReporter mChangeReporter;

    public PlatformCompat(Context context) {
        mContext = context;
        mChangeReporter = new ChangeReporter();
    }

    @Override
    public void reportChange(long changeId, ApplicationInfo appInfo) {
        reportChange(changeId, appInfo, StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__LOGGED);
    }

    @Override
    public boolean isChangeEnabled(long changeId, ApplicationInfo appInfo) {
        if (CompatConfig.get().isChangeEnabled(changeId, appInfo)) {
            reportChange(changeId, appInfo,
                    StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__ENABLED);
            return true;
        }
        reportChange(changeId, appInfo,
                StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__DISABLED);
        return false;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, "platform_compat", pw)) return;
        CompatConfig.get().dumpConfig(pw);
    }

    private void reportChange(long changeId, ApplicationInfo appInfo, int state) {
        int uid = appInfo.uid;
        //TODO(b/138374585): Implement rate limiting for the logs.
        Slog.d(TAG, ChangeReporter.createLogString(uid, changeId, state));
        mChangeReporter.reportChange(uid, changeId,
                state, /* source */
                StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__SOURCE__SYSTEM_SERVER);
    }
}
