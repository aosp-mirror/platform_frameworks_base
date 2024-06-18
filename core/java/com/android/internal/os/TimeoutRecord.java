/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.os;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;

import com.android.internal.os.anr.AnrLatencyTracker;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A timeout that has triggered on the system.
 *
 * @hide
 */
public class TimeoutRecord {
    /** Kind of timeout, e.g. BROADCAST_RECEIVER, etc. */
    @IntDef(value = {
            TimeoutKind.INPUT_DISPATCH_NO_FOCUSED_WINDOW,
            TimeoutKind.INPUT_DISPATCH_WINDOW_UNRESPONSIVE,
            TimeoutKind.BROADCAST_RECEIVER,
            TimeoutKind.SERVICE_START,
            TimeoutKind.SERVICE_EXEC,
            TimeoutKind.CONTENT_PROVIDER,
            TimeoutKind.APP_REGISTERED,
            TimeoutKind.SHORT_FGS_TIMEOUT,
            TimeoutKind.JOB_SERVICE,
            TimeoutKind.FGS_TIMEOUT,
    })

    @Retention(RetentionPolicy.SOURCE)
    public @interface TimeoutKind {
        int INPUT_DISPATCH_NO_FOCUSED_WINDOW = 1;
        int INPUT_DISPATCH_WINDOW_UNRESPONSIVE = 2;
        int BROADCAST_RECEIVER = 3;
        int SERVICE_START = 4;
        int SERVICE_EXEC = 5;
        int CONTENT_PROVIDER = 6;
        int APP_REGISTERED = 7;
        int SHORT_FGS_TIMEOUT = 8;
        int JOB_SERVICE = 9;
        int APP_START = 10;
        int FGS_TIMEOUT = 11;
    }

    /** Kind of timeout, e.g. BROADCAST_RECEIVER, etc. */
    @TimeoutKind
    public final int mKind;

    /** Reason for the timeout. */
    public final String mReason;

    /** System uptime in millis when the timeout was triggered. */
    public final long mEndUptimeMillis;

    /**
     * Was the end timestamp taken right after the timeout triggered, before any potentially
     * expensive operations such as taking locks?
     */
    public final boolean mEndTakenBeforeLocks;

    /** Latency tracker associated with this instance. */
    public final AnrLatencyTracker mLatencyTracker;

    private TimeoutRecord(@TimeoutKind int kind, @NonNull String reason, long endUptimeMillis,
            boolean endTakenBeforeLocks) {
        this.mKind = kind;
        this.mReason = reason;
        this.mEndUptimeMillis = endUptimeMillis;
        this.mEndTakenBeforeLocks = endTakenBeforeLocks;
        this.mLatencyTracker = new AnrLatencyTracker(kind, endUptimeMillis);
    }

    private static TimeoutRecord endingNow(@TimeoutKind int kind, String reason) {
        long endUptimeMillis = SystemClock.uptimeMillis();
        return new TimeoutRecord(kind, reason, endUptimeMillis, /* endTakenBeforeLocks */ true);
    }

    private static TimeoutRecord endingApproximatelyNow(@TimeoutKind int kind, String reason) {
        long endUptimeMillis = SystemClock.uptimeMillis();
        return new TimeoutRecord(kind, reason, endUptimeMillis, /* endTakenBeforeLocks */ false);
    }

    /** Record for a broadcast receiver timeout. */
    @NonNull
    public static TimeoutRecord forBroadcastReceiver(@NonNull Intent intent,
            @Nullable String packageName, @Nullable String className) {
        final Intent logIntent;
        if (packageName != null) {
            if (className != null) {
                logIntent = new Intent(intent);
                logIntent.setComponent(new ComponentName(packageName, className));
            } else {
                logIntent = new Intent(intent);
                logIntent.setPackage(packageName);
            }
        } else {
            logIntent = intent;
        }
        return forBroadcastReceiver(logIntent);
    }

    /** Record for a broadcast receiver timeout. */
    @NonNull
    public static TimeoutRecord forBroadcastReceiver(@NonNull Intent intent) {
        final StringBuilder reason = new StringBuilder("Broadcast of ");
        intent.toString(reason);
        return TimeoutRecord.endingNow(TimeoutKind.BROADCAST_RECEIVER, reason.toString());
    }

    /** Record for a broadcast receiver timeout. */
    @NonNull
    public static TimeoutRecord forBroadcastReceiver(@NonNull Intent intent,
            long timeoutDurationMs) {
        final StringBuilder reason = new StringBuilder("Broadcast of ");
        intent.toString(reason);
        reason.append(", waited ");
        reason.append(timeoutDurationMs);
        reason.append("ms");
        return TimeoutRecord.endingNow(TimeoutKind.BROADCAST_RECEIVER, reason.toString());
    }

    /** Record for an input dispatch no focused window timeout */
    @NonNull
    public static TimeoutRecord forInputDispatchNoFocusedWindow(@NonNull String reason) {
        return TimeoutRecord.endingNow(TimeoutKind.INPUT_DISPATCH_NO_FOCUSED_WINDOW, reason);
    }

    /** Record for an input dispatch window unresponsive timeout. */
    @NonNull
    public static TimeoutRecord forInputDispatchWindowUnresponsive(@NonNull String reason) {
        return TimeoutRecord.endingNow(TimeoutKind.INPUT_DISPATCH_WINDOW_UNRESPONSIVE, reason);
    }

    /** Record for a service exec timeout. */
    @NonNull
    public static TimeoutRecord forServiceExec(@NonNull String shortInstanceName,
            long timeoutDurationMs) {
        String reason =
                "executing service " + shortInstanceName + ", waited "
                        + timeoutDurationMs + "ms";
        return TimeoutRecord.endingNow(TimeoutKind.SERVICE_EXEC, reason);
    }

    /** Record for a service start timeout. */
    @NonNull
    public static TimeoutRecord forServiceStartWithEndTime(@NonNull String reason,
            long endUptimeMillis) {
        return new TimeoutRecord(TimeoutKind.SERVICE_START, reason,
                endUptimeMillis, /* endTakenBeforeLocks */ true);
    }

    /** Record for a content provider timeout. */
    @NonNull
    public static TimeoutRecord forContentProvider(@NonNull String reason) {
        return TimeoutRecord.endingApproximatelyNow(TimeoutKind.CONTENT_PROVIDER, reason);
    }

    /** Record for an app registered timeout. */
    @NonNull
    public static TimeoutRecord forApp(@NonNull String reason) {
        return TimeoutRecord.endingApproximatelyNow(TimeoutKind.APP_REGISTERED, reason);
    }

    /** Record for a "short foreground service" timeout. */
    @NonNull
    public static TimeoutRecord forShortFgsTimeout(String reason) {
        return TimeoutRecord.endingNow(TimeoutKind.SHORT_FGS_TIMEOUT, reason);
    }

    /** Record for a "foreground service" timeout. */
    @NonNull
    public static TimeoutRecord forFgsTimeout(String reason) {
        return TimeoutRecord.endingNow(TimeoutKind.FGS_TIMEOUT, reason);
    }

    /** Record for a job related timeout. */
    @NonNull
    public static TimeoutRecord forJobService(String reason) {
        return TimeoutRecord.endingNow(TimeoutKind.JOB_SERVICE, reason);
    }

    /** Record for app startup timeout. */
    @NonNull
    public static TimeoutRecord forAppStart(String reason) {
        return TimeoutRecord.endingNow(TimeoutKind.APP_START, reason);
    }
}
