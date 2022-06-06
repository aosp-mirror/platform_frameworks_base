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
import android.text.format.DateUtils;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;

/** Rate limiter for adding errors into dropbox. */
public class DropboxRateLimiter {
    // After RATE_LIMIT_ALLOWED_ENTRIES have been collected (for a single breakdown of
    // process/eventType) further entries will be rejected until RATE_LIMIT_BUFFER_DURATION has
    // elapsed, after which the current count for this breakdown will be reset.
    private static final long RATE_LIMIT_BUFFER_DURATION = 10 * DateUtils.MINUTE_IN_MILLIS;
    // The time duration after which the rate limit buffer will be cleared.
    private static final long RATE_LIMIT_BUFFER_EXPIRY = 3 * RATE_LIMIT_BUFFER_DURATION;
    // The number of entries to keep per breakdown of process/eventType.
    private static final int RATE_LIMIT_ALLOWED_ENTRIES = 6;

    @GuardedBy("mErrorClusterRecords")
    private final ArrayMap<String, ErrorRecord> mErrorClusterRecords = new ArrayMap<>();
    private final Clock mClock;

    private long mLastMapCleanUp = 0L;

    public DropboxRateLimiter() {
        this(new DefaultClock());
    }

    public DropboxRateLimiter(Clock clock) {
        mClock = clock;
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

            if (now - errRecord.getStartTime() > RATE_LIMIT_BUFFER_DURATION) {
                final int errCount = recentlyDroppedCount(errRecord);
                errRecord.setStartTime(now);
                errRecord.setCount(1);
                return new RateLimitResult(false, errCount);
            }

            errRecord.incrementCount();
            if (errRecord.getCount() > RATE_LIMIT_ALLOWED_ENTRIES) {
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
        if (errRecord == null || errRecord.getCount() < RATE_LIMIT_ALLOWED_ENTRIES) return 0;
        return errRecord.getCount() - RATE_LIMIT_ALLOWED_ENTRIES;
    }


    private void maybeRemoveExpiredRecords(long now) {
        if (now - mLastMapCleanUp <= RATE_LIMIT_BUFFER_EXPIRY) return;

        for (int i = mErrorClusterRecords.size() - 1; i >= 0; i--) {
            if (now - mErrorClusterRecords.valueAt(i).getStartTime() > RATE_LIMIT_BUFFER_EXPIRY) {
                mErrorClusterRecords.removeAt(i);
            }
        }

        mLastMapCleanUp = now;
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

        ErrorRecord(long startTime, int count) {
            mStartTime = startTime;
            mCount = count;
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

        public long getStartTime() {
            return mStartTime;
        }

        public int getCount() {
            return mCount;
        }
    }

    private static class DefaultClock implements Clock {
        public long uptimeMillis() {
            return SystemClock.uptimeMillis();
        }
    }
}
