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
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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

    private static final class ChangeReport {
        long mChangeId;
        int mState;

        ChangeReport(long changeId, int state) {
            mChangeId = changeId;
            mState = state;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChangeReport that = (ChangeReport) o;
            return mChangeId == that.mChangeId
                    && mState == that.mState;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mChangeId, mState);
        }
    }

    // Maps uid to a set of ChangeReports (that were reported for that uid).
    @GuardedBy("mReportedChanges")
    private final Map<Integer, Set<ChangeReport>> mReportedChanges;

    // When true will of every time to debug (logcat).
    private boolean mDebugLogAll;

    public ChangeReporter(int source) {
        mSource = source;
        mReportedChanges =  new HashMap<>();
        mDebugLogAll = false;
    }

    /**
     * Report the change to stats log and to the debug log if the change was not previously
     * logged already.
     *
     * @param uid      affected by the change
     * @param changeId the reported change id
     * @param state    of the reported change - enabled/disabled/only logged
     */
    public void reportChange(int uid, long changeId, int state) {
        if (shouldWriteToStatsLog(uid, changeId, state)) {
            StatsLog.write(StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED, uid, changeId,
                    state, mSource);
        }
        if (shouldWriteToDebug(uid, changeId, state)) {
            debugLog(uid, changeId, state);
        }
        markAsReported(uid, new ChangeReport(changeId, state));
    }

    /**
     * Start logging all the time to logcat.
     */
    public void startDebugLogAll() {
        mDebugLogAll = true;
    }

    /**
     * Stop logging all the time to logcat.
     */
    public void stopDebugLogAll() {
        mDebugLogAll = false;
    }


    /**
     * Returns whether the next report should be logged to statsLog.
     *
     * @param uid      affected by the change
     * @param changeId the reported change id
     * @param state    of the reported change - enabled/disabled/only logged
     * @return true if the report should be logged
     */
    @VisibleForTesting
    public boolean shouldWriteToStatsLog(int uid, long changeId, int state) {
        return !isAlreadyReported(uid, new ChangeReport(changeId, state));
    }

    /**
     * Returns whether the next report should be logged to logcat.
     *
     * @param uid      affected by the change
     * @param changeId the reported change id
     * @param state    of the reported change - enabled/disabled/only logged
     * @return true if the report should be logged
     */
    @VisibleForTesting
    public boolean shouldWriteToDebug(int uid, long changeId, int state) {
        return mDebugLogAll || !isAlreadyReported(uid, new ChangeReport(changeId, state));
    }

    private boolean isAlreadyReported(int uid, ChangeReport report) {
        synchronized (mReportedChanges) {
            Set<ChangeReport> reportedChangesForUid = mReportedChanges.get(uid);
            if (reportedChangesForUid == null) {
                return false;
            } else {
                return reportedChangesForUid.contains(report);
            }
        }
    }

    private void markAsReported(int uid, ChangeReport report) {
        synchronized (mReportedChanges) {
            Set<ChangeReport> reportedChangesForUid = mReportedChanges.get(uid);
            if (reportedChangesForUid == null) {
                mReportedChanges.put(uid, new HashSet<ChangeReport>());
                reportedChangesForUid = mReportedChanges.get(uid);
            }
            reportedChangesForUid.add(report);
        }
    }

    /**
     * Clears the saved information about a given uid. Requests to report uid again will be reported
     * regardless to the past reports.
     *
     * <p> Only intended to be called from PlatformCompat.
     *
     * @param uid to reset
     */
    public void resetReportedChanges(int uid) {
        synchronized (mReportedChanges) {
            mReportedChanges.remove(uid);
        }
    }

    private void debugLog(int uid, long changeId, int state) {
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
