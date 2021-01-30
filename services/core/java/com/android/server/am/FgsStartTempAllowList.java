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

import android.os.SystemClock;
import android.util.Slog;
import android.util.SparseLongArray;

/**
 * List of uids that are temporarily allowed to start FGS from background.
 */
final class FgsStartTempAllowList {
    private static final int MAX_SIZE = 100;
    /**
     * The key is the uid, the value is expiration elapse time in ms of this temp-allowed uid.
     */
    private final SparseLongArray mTempAllowListFgs = new SparseLongArray();

    FgsStartTempAllowList() {
    }

    void add(int uid, long duration) {
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
            if (mTempAllowListFgs.valueAt(index) < now) {
                mTempAllowListFgs.removeAt(index);
            }
        }
        final long existingExpirationTime = mTempAllowListFgs.get(uid, -1);
        final long expirationTime = now + duration;
        if (existingExpirationTime == -1 || existingExpirationTime < expirationTime) {
            mTempAllowListFgs.put(uid, expirationTime);
        }
    }

    boolean isAllowed(int uid) {
        final int index = mTempAllowListFgs.indexOfKey(uid);
        if (index < 0) {
            return false;
        } else if (mTempAllowListFgs.valueAt(index) < SystemClock.elapsedRealtime()) {
            mTempAllowListFgs.removeAt(index);
            return false;
        } else {
            return true;
        }
    }

    void remove(int uid) {
        mTempAllowListFgs.delete(uid);
    }
}
