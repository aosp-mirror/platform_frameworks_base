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

package com.android.server.display.mode;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

class VotesStorage {
    private static final String TAG = "VotesStorage";
    // Special ID used to indicate that given vote is to be applied globally, rather than to a
    // specific display.
    @VisibleForTesting
    static final int GLOBAL_ID = -1;

    private boolean mLoggingEnabled;

    private final Listener mListener;

    @Nullable
    private final VotesStatsReporter mVotesStatsReporter;

    private final Object mStorageLock = new Object();
    // A map from the display ID to the collection of votes and their priority. The latter takes
    // the form of another map from the priority to the vote itself so that each priority is
    // guaranteed to have exactly one vote, which is also easily and efficiently replaceable.
    @GuardedBy("mStorageLock")
    private final SparseArray<SparseArray<Vote>> mVotesByDisplay = new SparseArray<>();

    VotesStorage(@NonNull Listener listener, @Nullable VotesStatsReporter votesStatsReporter) {
        mListener = listener;
        mVotesStatsReporter = votesStatsReporter;
    }
    /** sets logging enabled/disabled for this class */
    void setLoggingEnabled(boolean loggingEnabled) {
        mLoggingEnabled = loggingEnabled;
    }
    /**
     * gets all votes for specific display, note that global display votes are also added to result
     */
    @NonNull
    SparseArray<Vote> getVotes(int displayId) {
        SparseArray<Vote> votesLocal;
        SparseArray<Vote> globalVotesLocal;
        synchronized (mStorageLock) {
            SparseArray<Vote> displayVotes = mVotesByDisplay.get(displayId);
            votesLocal = displayVotes != null ? displayVotes.clone() : new SparseArray<>();
            SparseArray<Vote> globalVotes = mVotesByDisplay.get(GLOBAL_ID);
            globalVotesLocal = globalVotes != null ? globalVotes.clone() : new SparseArray<>();
        }
        for (int i = 0; i < globalVotesLocal.size(); i++) {
            int priority = globalVotesLocal.keyAt(i);
            if (!votesLocal.contains(priority)) {
                votesLocal.put(priority, globalVotesLocal.valueAt(i));
            }
        }
        return votesLocal;
    }

    /** updates vote storage for all displays */
    void updateGlobalVote(int priority, @Nullable Vote vote) {
        updateVote(GLOBAL_ID, priority, vote);
    }

    /** updates vote storage */
    void updateVote(int displayId, int priority, @Nullable Vote vote) {
        if (mLoggingEnabled) {
            Slog.i(TAG, "updateVoteLocked(displayId=" + displayId
                    + ", priority=" + Vote.priorityToString(priority)
                    + ", vote=" + vote + ")");
        }
        if (priority < Vote.MIN_PRIORITY || priority > Vote.MAX_PRIORITY) {
            Slog.w(TAG, "Received a vote with an invalid priority, ignoring:"
                    + " priority=" + Vote.priorityToString(priority)
                    + ", vote=" + vote);
            return;
        }
        boolean changed = false;
        SparseArray<Vote> votes;
        synchronized (mStorageLock) {
            if (mVotesByDisplay.contains(displayId)) {
                votes = mVotesByDisplay.get(displayId);
            } else {
                votes = new SparseArray<>();
                mVotesByDisplay.put(displayId, votes);
            }
            var currentVote = votes.get(priority);
            if (vote != null && !vote.equals(currentVote)) {
                votes.put(priority, vote);
                changed = true;
            } else if (vote == null && currentVote != null) {
                votes.remove(priority);
                changed = true;
            }
        }
        if (mLoggingEnabled) {
            Slog.i(TAG, "Updated votes for display=" + displayId + " votes=" + votes);
        }
        if (changed) {
            if (mVotesStatsReporter != null) {
                mVotesStatsReporter.reportVoteChanged(displayId, priority, vote);
            }
            mListener.onChanged();
        }
    }

    /** dump class values, for debugging */
    void dump(@NonNull PrintWriter pw) {
        SparseArray<SparseArray<Vote>> votesByDisplayLocal = new SparseArray<>();
        synchronized (mStorageLock) {
            for (int i = 0; i < mVotesByDisplay.size(); i++) {
                votesByDisplayLocal.put(mVotesByDisplay.keyAt(i),
                        mVotesByDisplay.valueAt(i).clone());
            }
        }
        pw.println("  mVotesByDisplay:");
        for (int i = 0; i < votesByDisplayLocal.size(); i++) {
            SparseArray<Vote> votes = votesByDisplayLocal.valueAt(i);
            if (votes.size() == 0) {
                continue;
            }
            pw.println("    " + votesByDisplayLocal.keyAt(i) + ":");
            for (int p = Vote.MAX_PRIORITY; p >= Vote.MIN_PRIORITY; p--) {
                Vote vote = votes.get(p);
                if (vote == null) {
                    continue;
                }
                pw.println("      " + Vote.priorityToString(p) + " -> " + vote);
            }
        }
    }

    @VisibleForTesting
    void injectVotesByDisplay(SparseArray<SparseArray<Vote>> votesByDisplay) {
        synchronized (mStorageLock) {
            mVotesByDisplay.clear();
            for (int i = 0; i < votesByDisplay.size(); i++) {
                mVotesByDisplay.put(votesByDisplay.keyAt(i), votesByDisplay.valueAt(i));
            }
        }
    }

    interface Listener {
        void onChanged();
    }
}
