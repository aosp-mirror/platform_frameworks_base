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

package com.android.server.display.mode;

import static com.android.internal.util.FrameworkStatsLog.DISPLAY_MODE_DIRECTOR_VOTE_CHANGED;
import static com.android.internal.util.FrameworkStatsLog.DISPLAY_MODE_DIRECTOR_VOTE_CHANGED__VOTE_STATUS__STATUS_ACTIVE;
import static com.android.internal.util.FrameworkStatsLog.DISPLAY_MODE_DIRECTOR_VOTE_CHANGED__VOTE_STATUS__STATUS_ADDED;
import static com.android.internal.util.FrameworkStatsLog.DISPLAY_MODE_DIRECTOR_VOTE_CHANGED__VOTE_STATUS__STATUS_REMOVED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Trace;
import android.util.SparseArray;
import android.view.Display;

import com.android.internal.util.FrameworkStatsLog;

/**
 * The VotesStatsReporter is responsible for collecting and sending Vote related statistics 
 */
class VotesStatsReporter {
    private static final String TAG = "VotesStatsReporter";
    private static final int REFRESH_RATE_NOT_LIMITED = 1000;
    private final boolean mIgnoredRenderRate;
    private final boolean mFrameworkStatsLogReportingEnabled;

    private int mLastMinPriorityReported = Vote.MAX_PRIORITY + 1;

    public VotesStatsReporter(boolean ignoreRenderRate, boolean refreshRateVotingTelemetryEnabled) {
        mIgnoredRenderRate = ignoreRenderRate;
        mFrameworkStatsLogReportingEnabled = refreshRateVotingTelemetryEnabled;
    }

    void reportVoteChanged(int displayId, int priority,  @Nullable Vote vote) {
        if (vote == null) {
            reportVoteRemoved(displayId, priority);
        } else {
            reportVoteAdded(displayId, priority, vote);
        }
    }

    private void reportVoteAdded(int displayId, int priority,  @NonNull Vote vote) {
        int maxRefreshRate = getMaxRefreshRate(vote, mIgnoredRenderRate);
        Trace.traceCounter(Trace.TRACE_TAG_POWER,
                TAG + "." + displayId + ":" + Vote.priorityToString(priority), maxRefreshRate);
        if (mFrameworkStatsLogReportingEnabled) {
            FrameworkStatsLog.write(
                    DISPLAY_MODE_DIRECTOR_VOTE_CHANGED, displayId, priority,
                    DISPLAY_MODE_DIRECTOR_VOTE_CHANGED__VOTE_STATUS__STATUS_ADDED,
                    maxRefreshRate, -1);
        }
    }

    private void reportVoteRemoved(int displayId, int priority) {
        Trace.traceCounter(Trace.TRACE_TAG_POWER,
                TAG + "." + displayId + ":" + Vote.priorityToString(priority), -1);
        if (mFrameworkStatsLogReportingEnabled) {
            FrameworkStatsLog.write(
                    DISPLAY_MODE_DIRECTOR_VOTE_CHANGED, displayId, priority,
                    DISPLAY_MODE_DIRECTOR_VOTE_CHANGED__VOTE_STATUS__STATUS_REMOVED, -1, -1);
        }
    }

    void reportVotesActivated(int displayId, int minPriority, @Nullable Display.Mode baseMode,
            SparseArray<Vote> votes) {
        if (!mFrameworkStatsLogReportingEnabled) {
            return;
        }
        int selectedRefreshRate = baseMode != null ? (int) baseMode.getRefreshRate() : -1;
        for (int priority = Vote.MIN_PRIORITY; priority <= Vote.MAX_PRIORITY; priority++) {
            if (priority < mLastMinPriorityReported && priority < minPriority) {
                continue;
            }
            Vote vote = votes.get(priority);
            if (vote == null) {
                continue;
            }

            // Was previously reported ACTIVE, changed to ADDED
            if (priority >= mLastMinPriorityReported && priority < minPriority) {
                int maxRefreshRate = getMaxRefreshRate(vote, mIgnoredRenderRate);
                FrameworkStatsLog.write(
                        DISPLAY_MODE_DIRECTOR_VOTE_CHANGED, displayId, priority,
                        DISPLAY_MODE_DIRECTOR_VOTE_CHANGED__VOTE_STATUS__STATUS_ADDED,
                        maxRefreshRate, selectedRefreshRate);
            }
            // Was previously reported ADDED, changed to ACTIVE
            if (priority >= minPriority && priority < mLastMinPriorityReported) {
                int maxRefreshRate = getMaxRefreshRate(vote, mIgnoredRenderRate);
                FrameworkStatsLog.write(
                        DISPLAY_MODE_DIRECTOR_VOTE_CHANGED, displayId, priority,
                        DISPLAY_MODE_DIRECTOR_VOTE_CHANGED__VOTE_STATUS__STATUS_ACTIVE,
                        maxRefreshRate, selectedRefreshRate);
            }

            mLastMinPriorityReported = minPriority;
        }
    }

    private static int getMaxRefreshRate(@NonNull Vote vote, boolean ignoreRenderRate) {
        int maxRefreshRate = REFRESH_RATE_NOT_LIMITED;
        if (vote instanceof RefreshRateVote.PhysicalVote physicalVote) {
            maxRefreshRate = (int) physicalVote.mMaxRefreshRate;
        } else if (!ignoreRenderRate && (vote instanceof RefreshRateVote.RenderVote renderVote)) {
            maxRefreshRate = (int)  renderVote.mMaxRefreshRate;
        } else if (vote instanceof SupportedRefreshRatesVote refreshRatesVote) {
            // SupportedRefreshRatesVote limits mode by refreshRates, so highest rr is allowed
            maxRefreshRate = 0;
            for (SupportedRefreshRatesVote.RefreshRates rr : refreshRatesVote.mRefreshRates) {
                maxRefreshRate = Math.max(maxRefreshRate, (int) rr.mPeakRefreshRate);
            }
        } else if (vote instanceof CombinedVote combinedVote) {
            for (Vote subVote: combinedVote.mVotes) {
                // CombinedVote should not have CombinedVote in mVotes, so recursion depth will be 1
                maxRefreshRate = Math.min(maxRefreshRate,
                        getMaxRefreshRate(subVote, ignoreRenderRate));
            }
        }
        return maxRefreshRate;
    }
}
