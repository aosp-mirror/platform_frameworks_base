/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.appop;

import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT_UI;
import static android.app.ActivityManager.PROCESS_STATE_RECEIVER;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.app.ActivityManager.PROCESS_STATE_UNKNOWN;
import static android.app.AppOpsManager.UID_STATE_BACKGROUND;
import static android.app.AppOpsManager.UID_STATE_CACHED;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND_SERVICE;
import static android.app.AppOpsManager.UID_STATE_PERSISTENT;
import static android.app.AppOpsManager.UID_STATE_TOP;

import android.annotation.CallbackExecutor;
import android.util.SparseArray;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

interface AppOpsUidStateTracker {

    // Map from process states to the uid states we track.
    static int processStateToUidState(int procState) {
        if (procState == PROCESS_STATE_UNKNOWN) {
            return UID_STATE_CACHED;
        }

        if (procState <= PROCESS_STATE_PERSISTENT_UI) {
            return UID_STATE_PERSISTENT;
        }

        if (procState <= PROCESS_STATE_TOP) {
            return UID_STATE_TOP;
        }

        if (procState <= PROCESS_STATE_BOUND_TOP) {
            return UID_STATE_FOREGROUND;
        }

        if (procState <= PROCESS_STATE_FOREGROUND_SERVICE) {
            return UID_STATE_FOREGROUND_SERVICE;
        }

        if (procState <= PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
            return UID_STATE_FOREGROUND;
        }

        if (procState <= PROCESS_STATE_RECEIVER) {
            return UID_STATE_BACKGROUND;
        }

        // UID_STATE_NONEXISTENT is deliberately excluded here
        return UID_STATE_CACHED;
    }

    /*
     * begin data pushed from appopsservice
     */

    void updateUidProcState(int uid, int procState, int capability);

    void updateAppWidgetVisibility(SparseArray<String> uidPackageNames, boolean visible);

    /*
     * end data pushed from appopsservice
     */

    /**
     * Gets the {@link android.app.AppOpsManager.UidState} that the uid current is in.
     */
    int getUidState(int uid);

    /**
     * Determines if the uid is in foreground.
     */
    boolean isUidInForeground(int uid);

    /**
     * Given a uid, code, and mode, resolve any foregroundness to MODE_IGNORED or MODE_ALLOWED
     */
    int evalMode(int uid, int code, int mode);

    /**
     * Listen to changes in {@link android.app.AppOpsManager.UidState}
     */
    void addUidStateChangedCallback(@CallbackExecutor Executor executor,
            UidStateChangedCallback callback);

    /**
     * Remove a {@link UidStateChangedCallback}
     */
    void removeUidStateChangedCallback(UidStateChangedCallback callback);

    interface UidStateChangedCallback {
        /**
         * Invoked when a UID's {@link android.app.AppOpsManager.UidState} changes.
         * @param uid The uid that changed.
         * @param uidState The state that was changed to.
         * @param foregroundModeMayChange True if there may be a op in MODE_FOREGROUND whose
         *                               evaluated result may have changed.
         */
        void onUidStateChanged(int uid, int uidState, boolean foregroundModeMayChange);
    }

    void dumpUidState(PrintWriter pw, int uid, long nowElapsed);

    void dumpEvents(PrintWriter pw);
}
