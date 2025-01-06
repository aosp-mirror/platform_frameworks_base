/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.os.WorkSource.WorkChain;

import com.android.internal.util.FrameworkStatsLog;

public class FrameworkStatsLogger {
    public enum WakelockEventType {
        ACQUIRE,
        RELEASE
    }

    /** Log WakelockStateChanged push atom without a WorkChain. */
    public void wakelockStateChanged(
            int ownerUid, String tag, int powerManagerWakeLockLevel, WakelockEventType eventType) {
        int event =
                (eventType == WakelockEventType.ACQUIRE)
                        ? FrameworkStatsLog.WAKELOCK_STATE_CHANGED__STATE__ACQUIRE
                        : FrameworkStatsLog.WAKELOCK_STATE_CHANGED__STATE__RELEASE;
        FrameworkStatsLog.write_non_chained(
                FrameworkStatsLog.WAKELOCK_STATE_CHANGED,
                ownerUid,
                null,
                powerManagerWakeLockLevel,
                tag,
                event,
                FrameworkStatsLog.WAKELOCK_STATE_CHANGED__PROCESS_STATE__PROCESS_STATE_UNKNOWN);
    }

    /** Log WakelockStateChanged push atom with a WorkChain. */
    public void wakelockStateChanged(
            String tag, WorkChain wc, int powerManagerWakeLockLevel, WakelockEventType eventType) {
        int event =
                (eventType == WakelockEventType.ACQUIRE)
                        ? FrameworkStatsLog.WAKELOCK_STATE_CHANGED__STATE__ACQUIRE
                        : FrameworkStatsLog.WAKELOCK_STATE_CHANGED__STATE__RELEASE;
        FrameworkStatsLog.write(
                FrameworkStatsLog.WAKELOCK_STATE_CHANGED,
                wc.getUids(),
                wc.getTags(),
                powerManagerWakeLockLevel,
                tag,
                event,
                FrameworkStatsLog.WAKELOCK_STATE_CHANGED__PROCESS_STATE__PROCESS_STATE_UNKNOWN);
    }
}
