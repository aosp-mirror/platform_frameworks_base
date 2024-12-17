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

import android.provider.DeviceConfig;
import android.util.EventLog;
import android.util.EventLog.Event;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.selinux.SelinuxAuditLogBuilder.SelinuxAuditLog;
import com.android.server.utils.Slogf;

import java.io.IOException;
import java.time.Instant;
import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Class in charge of collecting SELinux audit logs and push the SELinux atoms. */
class SelinuxAuditLogsCollector {

    private static final String TAG = "SelinuxAuditLogs";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String SELINUX_PATTERN = "^.*\\bavc:\\s+(?<denial>.*)$";

    // This config indicates which Selinux logs for source domains to collect. The string will be
    // inserted into a regex, so it must follow the regex syntax. For example, a valid value would
    // be "system_server|untrusted_app".
    @VisibleForTesting static final String CONFIG_SELINUX_AUDIT_DOMAIN = "selinux_audit_domain";
    @VisibleForTesting static final String DEFAULT_SELINUX_AUDIT_DOMAIN = "no_match^";

    @VisibleForTesting
    static final Matcher SELINUX_MATCHER = Pattern.compile(SELINUX_PATTERN).matcher("");

    private final Supplier<String> mAuditDomainSupplier;
    private final RateLimiter mRateLimiter;
    private final QuotaLimiter mQuotaLimiter;
    private EventLogCollection mEventCollection;

    @VisibleForTesting Instant mLastWrite = Instant.MIN;

    AtomicBoolean mStopRequested = new AtomicBoolean(false);

    SelinuxAuditLogsCollector(
            Supplier<String> auditDomainSupplier,
            RateLimiter rateLimiter,
            QuotaLimiter quotaLimiter) {
        mAuditDomainSupplier = auditDomainSupplier;
        mRateLimiter = rateLimiter;
        mQuotaLimiter = quotaLimiter;
        mEventCollection = new EventLogCollection();
    }

    SelinuxAuditLogsCollector(RateLimiter rateLimiter, QuotaLimiter quotaLimiter) {
        this(
                () ->
                        DeviceConfig.getString(
                                DeviceConfig.NAMESPACE_ADSERVICES,
                                CONFIG_SELINUX_AUDIT_DOMAIN,
                                DEFAULT_SELINUX_AUDIT_DOMAIN),
                rateLimiter,
                quotaLimiter);
    }

    public void setStopRequested(boolean stopRequested) {
        mStopRequested.set(stopRequested);
    }

    /** A Collection to work around EventLog.readEvents() constraints.
     *
     * This collection only supports add(). Any other method inherited from
     * Collection will throw an UnsupportedOperationException exception.
     *
     * This collection ensures that we are processing one event at a time and
     * avoid collecting all the event objects before processing (e.g.,
     * ArrayList), which could lead to an OOM situation.
     */
    class EventLogCollection extends AbstractCollection<Event> {

        SelinuxAuditLogBuilder mAuditLogBuilder;
        int mAuditsWritten = 0;
        Instant mLatestTimestamp;

        void reset() {
            mAuditsWritten = 0;
            mLatestTimestamp = mLastWrite;
            mAuditLogBuilder = new SelinuxAuditLogBuilder(mAuditDomainSupplier.get());
        }

        int getAuditsWritten() {
            return mAuditsWritten;
        }

        Instant getLatestTimestamp() {
            return mLatestTimestamp;
        }

        @Override
        public Iterator<Event> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int size() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean add(Event event) {
            if (mStopRequested.get()) {
                throw new IllegalStateException(new InterruptedException());
            }

            Instant eventTime = Instant.ofEpochSecond(/* epochSecond= */ 0, event.getTimeNanos());
            if (eventTime.compareTo(mLastWrite) <= 0) {
                return true;
            }
            Object eventData = event.getData();
            if (!(eventData instanceof String)) {
                return true;
            }
            String logLine = (String) eventData;
            if (!SELINUX_MATCHER.reset(logLine).matches()) {
                return true;
            }

            mAuditLogBuilder.reset(SELINUX_MATCHER.group("denial"));
            final SelinuxAuditLog auditLog = mAuditLogBuilder.build();
            if (auditLog == null) {
                return true;
            }

            if (!mQuotaLimiter.acquire()) {
                throw new IllegalStateException(new QuotaExceededException());
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

            mAuditsWritten++;
            if (eventTime.isAfter(mLatestTimestamp)) {
                mLatestTimestamp = eventTime;
            }

            return true;
        }
    }

    /**
     * Collect and push SELinux audit logs for the provided {@code tagCode}.
     *
     * @return true if the job was completed. If the job was interrupted or
     * failed because of IOException, return false.
     * @throws QuotaExceededException if it ran out of quota.
     */
    boolean collect(int tagCode) throws QuotaExceededException {
        mEventCollection.reset();
        try {
            EventLog.readEvents(new int[] {tagCode}, mEventCollection);
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof QuotaExceededException) {
                if (DEBUG) {
                    Slogf.d(TAG, "Running out of quota after %d logs.",
                            mEventCollection.getAuditsWritten());
                }
                // next run we will ignore all these logs.
                mLastWrite = mEventCollection.getLatestTimestamp();
                throw (QuotaExceededException) e.getCause();
            } else if (e.getCause() instanceof InterruptedException) {
                mLastWrite = mEventCollection.getLatestTimestamp();
                return false;
            }
            throw e;
        } catch (IOException e) {
            Slog.e(TAG, "Error reading event logs", e);
            return false;
        }

        mLastWrite = mEventCollection.getLatestTimestamp();
        if (DEBUG) {
            Slogf.d(TAG, "Written %d logs", mEventCollection.getAuditsWritten());
        }
        return true;
    }
}
