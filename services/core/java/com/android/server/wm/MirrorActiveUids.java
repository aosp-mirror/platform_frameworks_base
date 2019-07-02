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

import android.util.SparseIntArray;

/**
 * This is a partial mirror of {@link @com.android.server.am.ActiveUids}. It is already thread
 * safe so the heavy service lock is not needed when updating state from activity manager (oom
 * adjustment) or getting state from window manager (background start check).
 */
class MirrorActiveUids {
    private SparseIntArray mUidStates = new SparseIntArray();

    synchronized void onUidActive(int uid, int procState) {
        mUidStates.put(uid, procState);
    }

    synchronized void onUidInactive(int uid) {
        mUidStates.delete(uid);
    }

    synchronized void onActiveUidsCleared() {
        mUidStates.clear();
    }

    synchronized void onUidProcStateChanged(int uid, int procState) {
        final int index = mUidStates.indexOfKey(uid);
        if (index >= 0) {
            mUidStates.setValueAt(index, procState);
        }
    }

    synchronized int getUidState(int uid) {
        return mUidStates.get(uid, PROCESS_STATE_NONEXISTENT);
    }
}
