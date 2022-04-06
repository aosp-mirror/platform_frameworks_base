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
    private static final long RATE_LIMIT_BUFFER_EXPIRY = 15 * DateUtils.SECOND_IN_MILLIS;
    private static final long RATE_LIMIT_BUFFER_DURATION = 10 * DateUtils.SECOND_IN_MILLIS;
    private static final int RATE_LIMIT_ALLOWED_ENTRIES = 5;

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
    public boolean shouldRateLimit(String eventType, String processName) {
        // Rate-limit how often we're willing to do the heavy lifting to collect and record logs.
        final long now = mClock.uptimeMillis();
        synchronized (mErrorClusterRecords) {
            // Remove expired records if enough time has passed since the last cleanup.
            maybeRemoveExpiredRecords(now);

            ErrorRecord errRecord = mErrorClusterRecords.get(errorKey(eventType, processName));
            if (errRecord == null) {
                errRecord = new ErrorRecord(now, 1);
                mErrorClusterRecords.put(errorKey(eventType, processName), errRecord);
            } else if (now - errRecord.getStartTime() > RATE_LIMIT_BUFFER_DURATION) {
                errRecord.setStartTime(now);
                errRecord.setCount(1);
            } else {
                errRecord.incrementCount();
                if (errRecord.getCount() > RATE_LIMIT_ALLOWED_ENTRIES) return true;
            }
        }
        return false;
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
