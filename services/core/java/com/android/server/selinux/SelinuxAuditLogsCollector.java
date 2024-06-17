/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.selinux;

import android.util.EventLog;
import android.util.EventLog.Event;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.selinux.SelinuxAuditLogBuilder.SelinuxAuditLog;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Class in charge of collecting SELinux audit logs and push the SELinux atoms. */
class SelinuxAuditLogsCollector {

    private static final String TAG = "SelinuxAuditLogs";

    private static final String SELINUX_PATTERN = "^.*\\bavc:\\s+(?<denial>.*)$";

    @VisibleForTesting
    static final Matcher SELINUX_MATCHER = Pattern.compile(SELINUX_PATTERN).matcher("");

    private final RateLimiter mRateLimiter;
    private final QuotaLimiter mQuotaLimiter;

    @VisibleForTesting Instant mLastWrite = Instant.MIN;

    final AtomicBoolean mStopRequested = new AtomicBoolean(false);

    SelinuxAuditLogsCollector(RateLimiter rateLimiter, QuotaLimiter quotaLimiter) {
        mRateLimiter = rateLimiter;
        mQuotaLimiter = quotaLimiter;
    }

    /**
     * Collect and push SELinux audit logs for the provided {@code tagCode}.
     *
     * @return true if the job was completed. If the job was interrupted, return false.
     */
    boolean collect(int tagCode) {
        Queue<Event> logLines = new ArrayDeque<>();
        Instant latestTimestamp = collectLogLines(tagCode, logLines);

        boolean quotaExceeded = writeAuditLogs(logLines);
        if (quotaExceeded) {
            Log.w(TAG, "Too many SELinux logs in the queue, I am giving up.");
            mLastWrite = latestTimestamp; // next run we will ignore all these logs.
            logLines.clear();
        }

        return logLines.isEmpty();
    }

    private Instant collectLogLines(int tagCode, Queue<Event> logLines) {
        List<Event> events = new ArrayList<>();
        try {
            EventLog.readEvents(new int[] {tagCode}, events);
        } catch (IOException e) {
            Log.e(TAG, "Error reading event logs", e);
        }

        Instant latestTimestamp = mLastWrite;
        for (Event event : events) {
            Instant eventTime = Instant.ofEpochSecond(0, event.getTimeNanos());
            if (eventTime.isAfter(latestTimestamp)) {
                latestTimestamp = eventTime;
            }
            if (eventTime.compareTo(mLastWrite) <= 0) {
                continue;
            }
            Object eventData = event.getData();
            if (!(eventData instanceof String)) {
                continue;
            }
            logLines.add(event);
        }
        return latestTimestamp;
    }

    private boolean writeAuditLogs(Queue<Event> logLines) {
        final SelinuxAuditLogBuilder auditLogBuilder = new SelinuxAuditLogBuilder();

        while (!mStopRequested.get() && !logLines.isEmpty()) {
            Event event = logLines.poll();
            String logLine = (String) event.getData();
            Instant logTime = Instant.ofEpochSecond(0, event.getTimeNanos());
            if (!SELINUX_MATCHER.reset(logLine).matches()) {
                continue;
            }

            auditLogBuilder.reset(SELINUX_MATCHER.group("denial"));
            final SelinuxAuditLog auditLog = auditLogBuilder.build();
            if (auditLog == null) {
                continue;
            }

            if (!mQuotaLimiter.acquire()) {
                return true;
            }
            mRateLimiter.acquire();

            FrameworkStatsLog.write(
                    FrameworkStatsLog.SELINUX_AUDIT_LOG,
                    auditLog.mGranted,
                    auditLog.mPermissions,
                    auditLog.mSType,
                    auditLog.mSCategories,
                    auditLog.mTType,
                    auditLog.mTCategories,
                    auditLog.mTClass,
                    auditLog.mPath,
                    auditLog.mPermissive);

            if (logTime.isAfter(mLastWrite)) {
                mLastWrite = logTime;
            }
        }

        return false;
    }
}
