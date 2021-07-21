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

import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;

/**
 * The state info of all broadcast receivers in the process.
 */
final class ProcessReceiverRecord {
    final ProcessRecord mApp;
    private final ActivityManagerService mService;

    /**
     * mReceivers currently running in the app.
     */
    private final ArraySet<BroadcastRecord> mCurReceivers = new ArraySet<BroadcastRecord>();

    /**
     * All IIntentReceivers that are registered from this process.
     */
    private final ArraySet<ReceiverList> mReceivers = new ArraySet<>();

    int numberOfCurReceivers() {
        return mCurReceivers.size();
    }

    BroadcastRecord getCurReceiverAt(int index) {
        return mCurReceivers.valueAt(index);
    }

    boolean hasCurReceiver(BroadcastRecord receiver) {
        return mCurReceivers.contains(receiver);
    }

    void addCurReceiver(BroadcastRecord receiver) {
        mCurReceivers.add(receiver);
    }

    void removeCurReceiver(BroadcastRecord receiver) {
        mCurReceivers.remove(receiver);
    }

    int numberOfReceivers() {
        return mReceivers.size();
    }

    ReceiverList getReceiverAt(int index) {
        return mReceivers.valueAt(index);
    }

    void addReceiver(ReceiverList receiver) {
        mReceivers.add(receiver);
    }

    void removeReceiver(ReceiverList receiver) {
        mReceivers.remove(receiver);
    }

    ProcessReceiverRecord(ProcessRecord app) {
        mApp = app;
        mService = app.mService;
    }

    @GuardedBy("mService")
    void onCleanupApplicationRecordLocked() {
        // Unregister any mReceivers.
        for (int i = mReceivers.size() - 1; i >= 0; i--) {
            mService.removeReceiverLocked(mReceivers.valueAt(i));
        }
        mReceivers.clear();
    }

    void dump(PrintWriter pw, String prefix, long nowUptime) {
        if (!mCurReceivers.isEmpty()) {
            pw.print(prefix); pw.println("Current mReceivers:");
            for (int i = 0, size = mCurReceivers.size(); i < size; i++) {
                pw.print(prefix); pw.print("  - "); pw.println(mCurReceivers.valueAt(i));
            }
        }
        if (mReceivers.size() > 0) {
            pw.print(prefix); pw.println("mReceivers:");
            for (int i = 0, size = mReceivers.size(); i < size; i++) {
                pw.print(prefix); pw.print("  - "); pw.println(mReceivers.valueAt(i));
            }
        }
    }
}
