/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.compat;

import android.util.Log;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.annotations.GuardedBy;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A helper class to report changes to stats log.
 *
 * @hide
 */
public final class ChangeReporter {
    private static final String TAG = "CompatibilityChangeReporter";
    private int mSource;

    private final class ChangeReport {
        int mUid;
        long mChangeId;
        int mState;

        ChangeReport(int uid, long changeId, int state) {
            mUid = uid;
            mChangeId = changeId;
            mState = state;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChangeReport that = (ChangeReport) o;
            return mUid == that.mUid
                    && mChangeId == that.mChangeId
                    && mState == that.mState;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mUid, mChangeId, mState);
        }
    }

    @GuardedBy("mReportedChanges")
    private Set<ChangeReport> mReportedChanges =  new HashSet<>();

    public ChangeReporter(int source) {
        mSource = source;
    }

    /**
     * Report the change to stats log.
     *
     * @param uid      affected by the change
     * @param changeId the reported change id
     * @param state    of the reported change - enabled/disabled/only logged
     */
    public void reportChange(int uid, long changeId, int state) {
        debugLog(uid, changeId, state);
        ChangeReport report = new ChangeReport(uid, changeId, state);
        synchronized (mReportedChanges) {
            if (!mReportedChanges.contains(report)) {
                StatsLog.write(StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED, uid, changeId,
                        state, mSource);
                mReportedChanges.add(report);
            }
        }
    }

    private void debugLog(int uid, long changeId, int state) {
        //TODO(b/138374585): Implement rate limiting for the logs.
        String message = String.format("Compat change id reported: %d; UID %d; state: %s", changeId,
                uid, stateToString(state));
        if (mSource == StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__SOURCE__SYSTEM_SERVER) {
            Slog.d(TAG, message);
        } else {
            Log.d(TAG, message);
        }

    }

    /**
     * Transforms StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE enum to a string.
     *
     * @param state to transform
     * @return a string representing the state
     */
    private static String stateToString(int state) {
        switch (state) {
            case StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__LOGGED:
                return "LOGGED";
            case StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__ENABLED:
                return "ENABLED";
            case StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__DISABLED:
                return "DISABLED";
            default:
                return "UNKNOWN";
        }
    }
}
