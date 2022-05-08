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

import android.content.Context;
import android.os.SystemClock;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

/**
 * While starting activity, WindowManager posts a runnable to DisplayThread to updateOomAdj.
 * The latency of the thread switch could cause client app failure when the app is checking
 * {@link ActivityManagerService#isUidActive} before updateOomAdj is done.
 *
 * Use PendingStartActivityUids to save uid after WindowManager start activity and before
 * updateOomAdj is done.
 *
 * <p>NOTE: This object is protected by its own lock, NOT the global activity manager lock!
 */
final class PendingStartActivityUids {
    static final String TAG = ActivityManagerService.TAG;

    // Key is uid, value is Pair of pid and SystemClock.elapsedRealtime() when the
    // uid is added.
    private final SparseArray<Pair<Integer, Long>> mPendingUids = new SparseArray();
    private Context mContext;

    PendingStartActivityUids(Context context) {
        mContext = context;
    }

    synchronized boolean add(int uid, int pid) {
        if (mPendingUids.get(uid) == null) {
            mPendingUids.put(uid, new Pair<>(pid, SystemClock.elapsedRealtime()));
            return true;
        }
        return false;
    }

    synchronized void delete(int uid, long nowElapsed) {
        final Pair<Integer, Long> pendingPid = mPendingUids.get(uid);
        if (pendingPid != null) {
            if (nowElapsed < pendingPid.second) {
                Slog.i(TAG,
                        "updateOomAdj start time is before than pendingPid added,"
                        + " don't delete it");
                return;
            }
            final long delay = SystemClock.elapsedRealtime() - pendingPid.second;
            if (delay >= 1000 /*ms*/) {
                Slog.i(TAG,
                        "PendingStartActivityUids startActivity to updateOomAdj delay:"
                                + delay + "ms," + " uid:" + uid);
            }
            mPendingUids.delete(uid);
        }
    }

    synchronized boolean isPendingTopPid(int uid, int pid) {
        final Pair<Integer, Long> pendingPid = mPendingUids.get(uid);
        if (pendingPid != null) {
            return pendingPid.first == pid;
        } else {
            return false;
        }
    }

    synchronized boolean isPendingTopUid(int uid) {
        return mPendingUids.get(uid) != null;
    }
}
