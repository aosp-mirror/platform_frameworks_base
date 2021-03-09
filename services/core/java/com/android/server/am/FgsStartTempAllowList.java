/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;

import android.annotation.Nullable;
import android.os.PowerWhitelistManager;
import android.os.PowerWhitelistManager.ReasonCode;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import java.io.PrintWriter;

/**
 * List of uids that are temporarily allowed to start FGS from background.
 */
final class FgsStartTempAllowList {
    private static final int MAX_SIZE = 100;

    public static final class TempFgsAllowListEntry {
        final long mExpirationTime;
        final long mDuration;
        final @ReasonCode int mReasonCode;
        final String mReason;
        final int mCallingUid;

        TempFgsAllowListEntry(long expirationTime, long duration, @ReasonCode int reasonCode,
                String reason, int callingUid) {
            mExpirationTime = expirationTime;
            mDuration = duration;
            mReasonCode = reasonCode;
            mReason = reason;
            mCallingUid = callingUid;
        }
    }

    /**
     * The key is the uid, the value is a TempAllowListEntry.
     */
    private final SparseArray<TempFgsAllowListEntry> mTempAllowListFgs = new SparseArray<>();

    FgsStartTempAllowList() {
    }

    /**
     * Add a uid and its duration with reason into the FGS temp-allowlist.
     * @param uid
     * @param duration temp-allowlisted duration in milliseconds.
     * @param reason A human-readable reason for logging purposes.
     * @param callingUid the callingUid that setup this temp allowlist, only valid when param adding
     *                   is true.
     */
    void add(int uid, long duration, @ReasonCode int reasonCode, @Nullable String reason,
            int callingUid) {
        if (duration <= 0) {
            Slog.e(TAG_AM, "FgsStartTempAllowList bad duration:" + duration + " uid: "
                    + uid);
            return;
        }
        // The temp allowlist should be a short list with only a few entries in it.
        final int size = mTempAllowListFgs.size();
        if (size > MAX_SIZE) {
            Slog.w(TAG_AM, "FgsStartTempAllowList length:" + size + " exceeds " + MAX_SIZE);
        }
        final long now = SystemClock.elapsedRealtime();
        for (int index = mTempAllowListFgs.size() - 1; index >= 0; index--) {
            if (mTempAllowListFgs.valueAt(index).mExpirationTime < now) {
                mTempAllowListFgs.removeAt(index);
            }
        }
        final TempFgsAllowListEntry existing = mTempAllowListFgs.get(uid);
        final long expirationTime = now + duration;
        if (existing == null || existing.mExpirationTime < expirationTime) {
            mTempAllowListFgs.put(uid,
                    new TempFgsAllowListEntry(expirationTime, duration, reasonCode,
                            reason == null ? "" : reason, callingUid));
        }
    }

    /**
     * Is this uid temp-allowlisted to start FGS.
     * @param uid
     * @return If uid is in the temp-allowlist, return the {@link TempFgsAllowListEntry}; If not in
     *         temp-allowlist, return null.
     */
    @Nullable
    TempFgsAllowListEntry getAllowedDurationAndReason(int uid) {
        final int index = mTempAllowListFgs.indexOfKey(uid);
        if (index < 0) {
            return null;
        } else if (mTempAllowListFgs.valueAt(index).mExpirationTime
                < SystemClock.elapsedRealtime()) {
            mTempAllowListFgs.removeAt(index);
            return null;
        } else {
            return mTempAllowListFgs.valueAt(index);
        }
    }

    void remove(int uid) {
        mTempAllowListFgs.delete(uid);
    }

    void dump(PrintWriter pw) {
        final long currentTimeNow = System.currentTimeMillis();
        final long elapsedRealtimeNow = SystemClock.elapsedRealtime();
        for (int i = 0; i < mTempAllowListFgs.size(); i++) {
            final int uid = mTempAllowListFgs.keyAt(i);
            final TempFgsAllowListEntry entry = mTempAllowListFgs.valueAt(i);
            pw.println(
                    "    " + UserHandle.formatUid(uid) + ": " +
                    " callingUid=" + UserHandle.formatUid(entry.mCallingUid) +
                    " reasonCode=" + PowerWhitelistManager.reasonCodeToString(entry.mReasonCode) +
                    " reason=" + entry.mReason);
            pw.print("        duration=" + entry.mDuration +
                    "ms expiration=");

            // Convert entry.mExpirationTime, which is an elapsed time since boot,
            // to a time since epoch (i.e. System.currentTimeMillis()-based time.)
            final long expirationInCurrentTime =
                    currentTimeNow - elapsedRealtimeNow + entry.mExpirationTime;
            TimeUtils.dumpTimeWithDelta(pw, expirationInCurrentTime, currentTimeNow);
            pw.println();
        }
    }
}
