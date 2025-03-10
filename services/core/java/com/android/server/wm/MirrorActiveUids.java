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
import android.util.SparseIntArray;

import java.io.PrintWriter;

/**
 * This is a partial mirror of {@link @com.android.server.am.ActiveUids}. It is already thread
 * safe so the heavy service lock is not needed when updating state from activity manager (oom
 * adjustment) or getting state from window manager (background start check).
 */
class MirrorActiveUids {
    /** Uid -> process state. */
    private final SparseIntArray mUidStates = new SparseIntArray();

    /** Uid -> number of non-app visible windows belong to the uid. */
    private final SparseIntArray mNumNonAppVisibleWindowMap = new SparseIntArray();
    /** Type -> Uid -> number of non-app visible windows for type/uid. */
    private final SparseArray<SparseIntArray> mNumNonAppVisibleWindowMapByType = new SparseArray();

    synchronized void onUidActive(int uid, int procState) {
        mUidStates.put(uid, procState);
    }

    synchronized void onUidInactive(int uid) {
        mUidStates.delete(uid);
    }

    synchronized void onUidProcStateChanged(int uid, int procState) {
        final int index = mUidStates.indexOfKey(uid);
        if (index >= 0) {
            mUidStates.setValueAt(index, procState);
        }
    }

    synchronized @ProcessState int getUidState(int uid) {
        return mUidStates.get(uid, PROCESS_STATE_NONEXISTENT);
    }

    /** Called when the surface of non-application (exclude toast) window is shown or hidden. */
    synchronized void onNonAppSurfaceVisibilityChanged(int uid, int type, boolean visible) {
        updateCount(uid, visible, mNumNonAppVisibleWindowMap);
        updateCount(uid, visible, getNumNonAppVisibleWindowMapByType(type));
    }

    private SparseIntArray getNumNonAppVisibleWindowMapByType(int type) {
        SparseIntArray result = mNumNonAppVisibleWindowMapByType.get(type);
        if (result == null) {
            result = new SparseIntArray();
            mNumNonAppVisibleWindowMapByType.append(type, result);
        }
        return result;
    }

    private void updateCount(int uid, boolean visible, SparseIntArray numNonAppVisibleWindowMap) {
        final int index = numNonAppVisibleWindowMap.indexOfKey(uid);
        if (index >= 0) {
            final int num = numNonAppVisibleWindowMap.valueAt(index) + (visible ? 1 : -1);
            if (num > 0) {
                numNonAppVisibleWindowMap.setValueAt(index, num);
            } else {
                numNonAppVisibleWindowMap.removeAt(index);
            }
        } else if (visible) {
            numNonAppVisibleWindowMap.append(uid, 1);
        }
    }

    /**
     * Returns {@code true} if the uid has any non-application (exclude toast) window currently
     * visible to the user. The application window visibility of a uid can be found from
     * {@link VisibleActivityProcessTracker}.
     */
    synchronized boolean hasNonAppVisibleWindow(int uid) {
        return mNumNonAppVisibleWindowMap.get(uid) > 0;
    }

    /**
     * Returns details about the windows that contribute to the result of
     * {@link #hasNonAppVisibleWindow(int)}.
     *
     * @return a map of window type to count
     */
    synchronized SparseIntArray getNonAppVisibleWindowDetails(int uid) {
        SparseIntArray result = new SparseIntArray();
        for (int i = 0; i < mNumNonAppVisibleWindowMapByType.size(); i++) {
            SparseIntArray numNonAppVisibleWindowMap = mNumNonAppVisibleWindowMapByType.valueAt(i);
            int count = numNonAppVisibleWindowMap.get(uid);
            if (count > 0) {
                result.append(mNumNonAppVisibleWindowMapByType.keyAt(i), count);
            }
        }
        return result;
    }

    synchronized void dump(PrintWriter pw, String prefix) {
        pw.print(prefix + "NumNonAppVisibleWindowUidMap:[");
        for (int i = mNumNonAppVisibleWindowMap.size() - 1; i >= 0; i--) {
            pw.print(" " + mNumNonAppVisibleWindowMap.keyAt(i) + ":"
                    + mNumNonAppVisibleWindowMap.valueAt(i));
        }
        pw.println("]");
    }
}
