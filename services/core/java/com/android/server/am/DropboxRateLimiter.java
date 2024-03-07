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

package com.android.server.am;

import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.modules.expresslog.Counter;

/** Rate limiter for adding errors into dropbox. */
public class DropboxRateLimiter {
    private static final String TAG = "DropboxRateLimiter";
    // After RATE_LIMIT_ALLOWED_ENTRIES have been collected (for a single breakdown of
    // process/eventType) further entries will be rejected until RATE_LIMIT_BUFFER_DURATION has
    // elapsed, after which the current count for this breakdown will be reset.
    private static final long RATE_LIMIT_BUFFER_DURATION_DEFAULT = 10 * DateUtils.MINUTE_IN_MILLIS;
    // Indicated how many buffer durations to wait before the rate limit buffer will be cleared.
    // E.g. if set to 3 will wait 3xRATE_LIMIT_BUFFER_DURATION before clearing the buffer.
    private static final long RATE_LIMIT_BUFFER_EXPIRY_FACTOR_DEFAULT = 3;
    // The number of entries to keep per breakdown of process/eventType.
    private static final int RATE_LIMIT_ALLOWED_ENTRIES_DEFAULT = 6;

    // If a process is rate limited twice in a row we consider it crash-looping and rate limit it
    // more aggressively.
    private static final int STRICT_RATE_LIMIT_ALLOWED_ENTRIES_DEFAULT = 1;
    private static final long STRICT_RATE_LIMIT_BUFFER_DURATION_DEFAULT =
            20 * DateUtils.MINUTE_IN_MILLIS;

    private static final String FLAG_NAMESPACE = "dropbox";

    private long mRateLimitBufferDuration;
    private long mRateLimitBufferExpiryFactor;
    private int mRateLimitAllowedEntries;
    private int mStrictRatelimitAllowedEntries;
    private long mStrictRateLimitBufferDuration;

    @GuardedBy("mErrorClusterRecords")
    private final ArrayMap<String, ErrorRecord> mErrorClusterRecords = new ArrayMap<>();
    private final Clock mClock;

    private long mLastMapCleanUp = 0L;

    public DropboxRateLimiter() {
        this(new DefaultClock());
    }

    public DropboxRateLimiter(Clock clock) {
        mClock = clock;

        mRateLimitBufferDuration = RATE_LIMIT_BUFFER_DURATION_DEFAULT;
        mRateLimitBufferExpiryFactor = RATE_LIMIT_BUFFER_EXPIRY_FACTOR_DEFAULT;
        mRateLimitAllowedEntries = RATE_LIMIT_ALLOWED_ENTRIES_DEFAULT;
        mStrictRatelimitAllowedEntries = STRICT_RATE_LIMIT_ALLOWED_ENTRIES_DEFAULT;
        mStrictRateLimitBufferDuration = STRICT_RATE_LIMIT_BUFFER_DURATION_DEFAULT;
    }

    /** Initializes the rate limiter parameters from flags. */
    public void init() {
        mRateLimitBufferDuration = DeviceConfig.getLong(
            FLAG_NAMESPACE,
            "DropboxRateLimiter__rate_limit_buffer_duration",
            RATE_LIMIT_BUFFER_DURATION_DEFAULT);
        mRateLimitBufferExpiryFactor = DeviceConfig.getLong(
            FLAG_NAMESPACE,
            "DropboxRateLimiter__rate_limit_buffer_expiry_factor",
            RATE_LIMIT_BUFFER_EXPIRY_FACTOR_DEFAULT);
        mRateLimitAllowedEntries = DeviceConfig.getInt(
            FLAG_NAMESPACE,
            "DropboxRateLimiter__rate_limit_allowed_entries",
            RATE_LIMIT_ALLOWED_ENTRIES_DEFAULT);
        mStrictRatelimitAllowedEntries = DeviceConfig.getInt(
            FLAG_NAMESPACE,
            "DropboxRateLimiter__strict_rate_limit_allowed_entries",
            STRICT_RATE_LIMIT_ALLOWED_ENTRIES_DEFAULT);
        mStrictRateLimitBufferDuration = DeviceConfig.getLong(
            FLAG_NAMESPACE,
            "DropboxRateLimiter__strict_rate_limit_buffer_duration",
            STRICT_RATE_LIMIT_BUFFER_DURATION_DEFAULT);
    }

    /** The interface clock to use for tracking the time elapsed. */
    public interface Clock {
        /** How long in millis has passed since the device came online. */
        long uptimeMillis();
    }

    /** Determines whether dropbox entries of a specific tag and process should be rate limited. */
    public RateLimitResult shouldRateLimit(String eventType, String processName) {
        // Rate-limit how often we're willing to do the heavy lifting to collect and record logs.
        final long now = mClock.uptimeMillis();
        synchronized (mErrorClusterRecords) {
            // Remove expired records if enough time has passed since the last cleanup.
            maybeRemoveExpiredRecords(now);

            ErrorRecord errRecord = mErrorClusterRecords.get(errorKey(eventType, processName));
            if (errRecord == null) {
                errRecord = new ErrorRecord(now, 1);
                mErrorClusterRecords.put(errorKey(eventType, processName), errRecord);
                return new RateLimitResult(false, 0);
            }

            final long timeSinceFirstError = now - errRecord.getStartTime();
            if (timeSinceFirstError > errRecord.getBufferDuration()) {
                final int errCount = recentlyDroppedCount(errRecord);
                errRecord.setStartTime(now);
                errRecord.setCount(1);

                // If this error happened exactly the next "rate limiting cycle" after the last
                // error and the previous cycle was rate limiting then increment the successive
                // rate limiting cycle counter. If a full "cycle" has passed since the last error
                // then this is no longer a continuous occurrence and will be rate limited normally.
                if (errCount > 0 && timeSinceFirstError < 2 * errRecord.getBufferDuration()) {
                    errRecord.incrementSuccessiveRateLimitCycles();
                } else {
                    errRecord.setSuccessiveRateLimitCycles(0);
                }

                return new RateLimitResult(false, errCount);
            }

            errRecord.incrementCount();
            if (errRecord.getCount() > errRecord.getAllowedEntries()) {
                return new RateLimitResult(true, recentlyDroppedCount(errRecord));
            }
        }
        return new RateLimitResult(false, 0);
    }

    /**
     * Returns the number of entries of a certain type and process that have recenlty been
     * dropped. Resets every RATE_LIMIT_BUFFER_DURATION if events are still actively created or
     * RATE_LIMIT_BUFFER_EXPIRY if not. */
    private int recentlyDroppedCount(ErrorRecord errRecord) {
        if (errRecord == null || errRecord.getCount() < errRecord.getAllowedEntries()) return 0;
        return errRecord.getCount() - errRecord.getAllowedEntries();
    }


    private void maybeRemoveExpiredRecords(long currentTime) {
        if (currentTime - mLastMapCleanUp
                <= mRateLimitBufferExpiryFactor * mRateLimitBufferDuration) {
            return;
        }

        for (int i = mErrorClusterRecords.size() - 1; i >= 0; i--) {
            if (mErrorClusterRecords.valueAt(i).hasExpired(currentTime)) {
                Counter.logIncrement(
                        "stability_errors.value_dropbox_buffer_expired_count",
                        mErrorClusterRecords.valueAt(i).getCount());
                mErrorClusterRecords.removeAt(i);
            }
        }

        mLastMapCleanUp = currentTime;
    }

    /** Resets the rate limiter memory. */
    public void reset() {
        synchronized (mErrorClusterRecords) {
            mErrorClusterRecords.clear();
        }
        mLastMapCleanUp = 0L;
        Slog.i(TAG, "Rate limiter reset.");
    }

    String errorKey(String eventType, String processName) {
        return eventType + processName;
    }

    /** Holds information on whether we should rate limit and how many events have been dropped. */
    public class RateLimitResult {
        final boolean mShouldRateLimit;
        final int mDroppedCountSinceRateLimitActivated;

        public RateLimitResult(boolean shouldRateLimit, int droppedCountSinceRateLimitActivated) {
            mShouldRateLimit = shouldRateLimit;
            mDroppedCountSinceRateLimitActivated = droppedCountSinceRateLimitActivated;
        }

        /** Whether to rate limit. */
        public boolean shouldRateLimit() {
            return mShouldRateLimit;
        }

        /** The number of dropped events since rate limit was activated. */
        public int droppedCountSinceRateLimitActivated() {
            return mDroppedCountSinceRateLimitActivated;
        }

        /** Returns a header indicating the number of dropped events. */
        public String createHeader() {
            return "Dropped-Count: " + mDroppedCountSinceRateLimitActivated + "\n";
        }
    }

    private class ErrorRecord {
        long mStartTime;
        int mCount;
        int mSuccessiveRateLimitCycles;

        ErrorRecord(long startTime, int count) {
            mStartTime = startTime;
            mCount = count;
            mSuccessiveRateLimitCycles = 0;
        }

        public void setStartTime(long startTime) {
            mStartTime = startTime;
        }

        public void setCount(int count) {
            mCount = count;
        }

        public void incrementCount() {
            mCount++;
        }

        public void setSuccessiveRateLimitCycles(int successiveRateLimitCycles) {
            mSuccessiveRateLimitCycles = successiveRateLimitCycles;
        }

        public void incrementSuccessiveRateLimitCycles() {
            mSuccessiveRateLimitCycles++;
        }

        public long getStartTime() {
            return mStartTime;
        }

        public int getCount() {
            return mCount;
        }

        public int getSuccessiveRateLimitCycles() {
            return mSuccessiveRateLimitCycles;
        }

        public boolean isRepeated() {
            return mSuccessiveRateLimitCycles >= 2;
        }

        public int getAllowedEntries() {
            return isRepeated() ? mStrictRatelimitAllowedEntries : mRateLimitAllowedEntries;
        }

        public long getBufferDuration() {
            return isRepeated() ? mStrictRateLimitBufferDuration : mRateLimitBufferDuration;
        }

        public boolean hasExpired(long currentTime) {
            long bufferExpiry = mRateLimitBufferExpiryFactor * getBufferDuration();
            return currentTime - mStartTime > bufferExpiry;
        }
    }

    private static class DefaultClock implements Clock {
        public long uptimeMillis() {
            return SystemClock.uptimeMillis();
        }
    }
}
