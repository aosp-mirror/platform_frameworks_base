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

package com.android.server.timezonedetector;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.util.IndentingPrintWriter;

import com.android.server.SystemTimeZone.TimeZoneConfidence;
import com.android.server.timezonedetector.TimeZoneDetectorStrategy.Origin;

import java.util.Objects;

public interface TimeZoneChangeListener {

    /** Record a time zone change. */
    void process(TimeZoneChangeEvent event);

    /** Dump internal state. */
    void dump(IndentingPrintWriter ipw);

    class TimeZoneChangeEvent {

        private final @ElapsedRealtimeLong long mElapsedRealtimeMillis;
        private final @CurrentTimeMillisLong long mUnixEpochTimeMillis;
        private final @Origin int mOrigin;
        private final @UserIdInt int mUserId;
        private final String mOldZoneId;
        private final String mNewZoneId;
        private final @TimeZoneConfidence int mNewConfidence;
        private final String mCause;

        public TimeZoneChangeEvent(@ElapsedRealtimeLong long elapsedRealtimeMillis,
                @CurrentTimeMillisLong long unixEpochTimeMillis,
                @Origin int origin, @UserIdInt int userId, @NonNull String oldZoneId,
                @NonNull String newZoneId, int newConfidence, @NonNull String cause) {
            mElapsedRealtimeMillis = elapsedRealtimeMillis;
            mUnixEpochTimeMillis = unixEpochTimeMillis;
            mOrigin = origin;
            mUserId = userId;
            mOldZoneId = Objects.requireNonNull(oldZoneId);
            mNewZoneId = Objects.requireNonNull(newZoneId);
            mNewConfidence = newConfidence;
            mCause = Objects.requireNonNull(cause);
        }

        public @ElapsedRealtimeLong long getElapsedRealtimeMillis() {
            return mElapsedRealtimeMillis;
        }

        public @CurrentTimeMillisLong long getUnixEpochTimeMillis() {
            return mUnixEpochTimeMillis;
        }

        public @Origin int getOrigin() {
            return mOrigin;
        }

        /**
         * The ID of the user that triggered the change.
         *
         * <p>If automatic time zone is turned on, the user ID returned is the system's user id.
         */
        public @UserIdInt int getUserId() {
            return mUserId;
        }

        public String getOldZoneId() {
            return mOldZoneId;
        }

        public String getNewZoneId() {
            return mNewZoneId;
        }

        @Override
        public String toString() {
            return "TimeZoneChangeEvent{"
                    + "mElapsedRealtimeMillis=" + mElapsedRealtimeMillis
                    + ", mUnixEpochTimeMillis=" + mUnixEpochTimeMillis
                    + ", mOrigin=" + mOrigin
                    + ", mUserId=" + mUserId
                    + ", mOldZoneId='" + mOldZoneId + '\''
                    + ", mNewZoneId='" + mNewZoneId + '\''
                    + ", mNewConfidence=" + mNewConfidence
                    + ", mCause='" + mCause + '\''
                    + '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o instanceof TimeZoneChangeEvent that) {
                return mElapsedRealtimeMillis == that.mElapsedRealtimeMillis
                        && mUnixEpochTimeMillis == that.mUnixEpochTimeMillis
                        && mOrigin == that.mOrigin
                        && mUserId == that.mUserId
                        && Objects.equals(mOldZoneId, that.mOldZoneId)
                        && Objects.equals(mNewZoneId, that.mNewZoneId)
                        && mNewConfidence == that.mNewConfidence
                        && Objects.equals(mCause, that.mCause);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mElapsedRealtimeMillis, mUnixEpochTimeMillis, mOrigin, mUserId,
                    mOldZoneId, mNewZoneId, mNewConfidence, mCause);
        }
    }
}
