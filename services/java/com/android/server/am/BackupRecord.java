/*
 * Copyright (C) 2009 The Android Open Source Project
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

import com.android.internal.os.BatteryStatsImpl;

import android.content.pm.ApplicationInfo;

/** @hide */
final class BackupRecord {
    // backup/restore modes
    public static final int BACKUP_NORMAL = 0;
    public static final int BACKUP_FULL = 1;
    public static final int RESTORE = 2;
    public static final int RESTORE_FULL = 3;
    
    final BatteryStatsImpl.Uid.Pkg.Serv stats;
    String stringName;                     // cached toString() output
    final ApplicationInfo appInfo;         // information about BackupAgent's app
    final int backupMode;                  // full backup / incremental / restore
    ProcessRecord app;                     // where this agent is running or null

    // ----- Implementation -----

    BackupRecord(BatteryStatsImpl.Uid.Pkg.Serv _agentStats, ApplicationInfo _appInfo,
            int _backupMode) {
        stats = _agentStats;
        appInfo = _appInfo;
        backupMode = _backupMode;
    }

    public String toString() {
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("BackupRecord{")
            .append(Integer.toHexString(System.identityHashCode(this)))
            .append(' ').append(appInfo.packageName)
            .append(' ').append(appInfo.name)
            .append(' ').append(appInfo.backupAgentName).append('}');
        return stringName = sb.toString();
    }
}
