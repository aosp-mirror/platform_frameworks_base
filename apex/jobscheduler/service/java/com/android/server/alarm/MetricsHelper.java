/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.alarm;

import static com.android.internal.util.FrameworkStatsLog.ALARM_SCHEDULED__EXACT_ALARM_ALLOWED_REASON__ALLOW_LIST;
import static com.android.internal.util.FrameworkStatsLog.ALARM_SCHEDULED__EXACT_ALARM_ALLOWED_REASON__CHANGE_DISABLED;
import static com.android.internal.util.FrameworkStatsLog.ALARM_SCHEDULED__EXACT_ALARM_ALLOWED_REASON__NOT_APPLICABLE;
import static com.android.internal.util.FrameworkStatsLog.ALARM_SCHEDULED__EXACT_ALARM_ALLOWED_REASON__PERMISSION;
import static com.android.internal.util.FrameworkStatsLog.ALARM_SCHEDULED__EXACT_ALARM_ALLOWED_REASON__POLICY_PERMISSION;
import static com.android.server.alarm.AlarmManagerService.INDEFINITE_DELAY;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.StatsManager;
import android.content.Context;
import android.os.SystemClock;

import com.android.internal.os.BackgroundThread;
import com.android.internal.util.FrameworkStatsLog;

import java.util.function.Supplier;

/**
 * A helper class to write logs to statsd.
 */
class MetricsHelper {
    private final Context mContext;
    private final Object mLock;

    MetricsHelper(Context context, Object lock) {
        mContext = context;
        mLock = lock;
    }

    void registerPuller(Supplier<AlarmStore> alarmStoreSupplier) {
        final StatsManager statsManager = mContext.getSystemService(StatsManager.class);
        statsManager.setPullAtomCallback(FrameworkStatsLog.PENDING_ALARM_INFO, null,
                BackgroundThread.getExecutor(), (atomTag, data) -> {
                    if (atomTag != FrameworkStatsLog.PENDING_ALARM_INFO) {
                        throw new UnsupportedOperationException("Unknown tag" + atomTag);
                    }
                    final long now = SystemClock.elapsedRealtime();
                    synchronized (mLock) {
                        final AlarmStore alarmStore = alarmStoreSupplier.get();
                        data.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                                alarmStore.size(),
                                alarmStore.getCount(a -> a.windowLength == 0),
                                alarmStore.getCount(a -> a.wakeup),
                                alarmStore.getCount(
                                        a -> (a.flags & AlarmManager.FLAG_ALLOW_WHILE_IDLE) != 0),
                                alarmStore.getCount(
                                        a -> (a.flags & AlarmManager.FLAG_PRIORITIZE) != 0),
                                alarmStore.getCount(a -> (a.operation != null
                                        && a.operation.isForegroundService())),
                                alarmStore.getCount(
                                        a -> (a.operation != null && a.operation.isActivity())),
                                alarmStore.getCount(
                                        a -> (a.operation != null && a.operation.isService())),
                                alarmStore.getCount(a -> (a.listener != null)),
                                alarmStore.getCount(
                                        a -> (a.getRequestedElapsed() > now + INDEFINITE_DELAY)),
                                alarmStore.getCount(a -> (a.repeatInterval != 0)),
                                alarmStore.getCount(a -> (a.alarmClock != null)),
                                alarmStore.getCount(a -> AlarmManagerService.isRtc(a.type))
                        ));
                        return StatsManager.PULL_SUCCESS;
                    }
                });
    }

    private static int reasonToStatsReason(int reasonCode) {
        switch (reasonCode) {
            case Alarm.EXACT_ALLOW_REASON_ALLOW_LIST:
                return ALARM_SCHEDULED__EXACT_ALARM_ALLOWED_REASON__ALLOW_LIST;
            case Alarm.EXACT_ALLOW_REASON_PERMISSION:
                return ALARM_SCHEDULED__EXACT_ALARM_ALLOWED_REASON__PERMISSION;
            case Alarm.EXACT_ALLOW_REASON_COMPAT:
                return ALARM_SCHEDULED__EXACT_ALARM_ALLOWED_REASON__CHANGE_DISABLED;
            case Alarm.EXACT_ALLOW_REASON_POLICY_PERMISSION:
                return ALARM_SCHEDULED__EXACT_ALARM_ALLOWED_REASON__POLICY_PERMISSION;
            default:
                return ALARM_SCHEDULED__EXACT_ALARM_ALLOWED_REASON__NOT_APPLICABLE;
        }
    }

    static void pushAlarmScheduled(Alarm a, int callerProcState) {
        FrameworkStatsLog.write(
                FrameworkStatsLog.ALARM_SCHEDULED,
                a.uid,
                a.windowLength == 0,
                a.wakeup,
                (a.flags & AlarmManager.FLAG_ALLOW_WHILE_IDLE) != 0,
                a.alarmClock != null,
                a.repeatInterval != 0,
                reasonToStatsReason(a.mExactAllowReason),
                AlarmManagerService.isRtc(a.type),
                ActivityManager.processStateAmToProto(callerProcState));
    }

    static void pushAlarmBatchDelivered(int numAlarms, int wakeups) {
        FrameworkStatsLog.write(
                FrameworkStatsLog.ALARM_BATCH_DELIVERED,
                numAlarms,
                wakeups);
    }
}
