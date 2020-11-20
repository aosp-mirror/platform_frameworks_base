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

package com.android.server.wm;

import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;

import android.app.ActivityManager.ProcessState;
import android.util.SparseArray;

import java.io.PrintWriter;

/**
 * This is a partial mirror of {@link @com.android.server.am.ActiveUids}. It is already thread
 * safe so the heavy service lock is not needed when updating state from activity manager (oom
 * adjustment) or getting state from window manager (background start check).
 */
class MirrorActiveUids {
    private final SparseArray<UidRecord> mUidStates = new SparseArray<>();

    synchronized void onUidActive(int uid, int procState) {
        UidRecord r = mUidStates.get(uid);
        if (r == null) {
            r = new UidRecord();
            mUidStates.put(uid, r);
        }
        r.mProcState = procState;
    }

    synchronized void onUidInactive(int uid) {
        mUidStates.delete(uid);
    }

    synchronized void onUidProcStateChanged(int uid, int procState) {
        final UidRecord r = mUidStates.get(uid);
        if (r != null) {
            r.mProcState = procState;
        }
    }

    synchronized @ProcessState int getUidState(int uid) {
        final UidRecord r = mUidStates.get(uid);
        return r != null ? r.mProcState : PROCESS_STATE_NONEXISTENT;
    }

    /** Called when the surface of non-application (exclude toast) window is shown or hidden. */
    synchronized void onNonAppSurfaceVisibilityChanged(int uid, boolean visible) {
        final UidRecord r = mUidStates.get(uid);
        if (r != null) {
            r.mNumNonAppVisibleWindow += visible ? 1 : -1;
        }
    }

    /**
     * Returns {@code true} if the uid has any non-application (exclude toast) window currently
     * visible to the user. The application window visibility of a uid can be found from
     * {@link VisibleActivityProcessTracker}.
     */
    synchronized boolean hasNonAppVisibleWindow(int uid) {
        final UidRecord r = mUidStates.get(uid);
        return r != null && r.mNumNonAppVisibleWindow > 0;
    }

    synchronized void dump(PrintWriter pw, String prefix) {
        pw.print(prefix + "NumNonAppVisibleWindowByUid:[");
        for (int i = mUidStates.size() - 1; i >= 0; i--) {
            final UidRecord r = mUidStates.valueAt(i);
            if (r.mNumNonAppVisibleWindow > 0) {
                pw.print(" " + mUidStates.keyAt(i) + ":" + r.mNumNonAppVisibleWindow);
            }
        }
        pw.println("]");
    }

    private static final class UidRecord {
        @ProcessState int mProcState;
        int mNumNonAppVisibleWindow;
    }
}
