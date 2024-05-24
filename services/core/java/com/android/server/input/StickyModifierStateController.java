/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.input;

import android.annotation.BinderThread;
import android.hardware.input.IStickyModifierStateListener;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

/**
 * A thread-safe component of {@link InputManagerService} responsible for managing the sticky
 * modifier state for A11y Sticky keys feature.
 */
final class StickyModifierStateController {

    private static final String TAG = "ModifierStateController";

    // To enable these logs, run:
    // 'adb shell setprop log.tag.ModifierStateController DEBUG' (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // List of currently registered sticky modifier state listeners
    @GuardedBy("mStickyModifierStateListenerRecords")
    private final SparseArray<StickyModifierStateListenerRecord>
            mStickyModifierStateListenerRecords = new SparseArray<>();

    public void notifyStickyModifierStateChanged(int modifierState, int lockedModifierState) {
        if (DEBUG) {
            Slog.d(TAG, "Sticky modifier state changed, modifierState = " + modifierState
                    + ", lockedModifierState = " + lockedModifierState);
        }

        synchronized (mStickyModifierStateListenerRecords) {
            for (int i = 0; i < mStickyModifierStateListenerRecords.size(); i++) {
                mStickyModifierStateListenerRecords.valueAt(i).notifyStickyModifierStateChanged(
                        modifierState, lockedModifierState);
            }
        }
    }

    /** Register the sticky modifier state listener for a process. */
    @BinderThread
    public void registerStickyModifierStateListener(IStickyModifierStateListener listener,
            int pid) {
        synchronized (mStickyModifierStateListenerRecords) {
            if (mStickyModifierStateListenerRecords.get(pid) != null) {
                throw new IllegalStateException("The calling process has already registered "
                        + "a StickyModifierStateListener.");
            }
            StickyModifierStateListenerRecord record = new StickyModifierStateListenerRecord(pid,
                    listener);
            try {
                listener.asBinder().linkToDeath(record, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
            mStickyModifierStateListenerRecords.put(pid, record);
        }
    }

    /** Unregister the sticky modifier state listener for a process. */
    @BinderThread
    public void unregisterStickyModifierStateListener(IStickyModifierStateListener listener,
            int pid) {
        synchronized (mStickyModifierStateListenerRecords) {
            StickyModifierStateListenerRecord record = mStickyModifierStateListenerRecords.get(pid);
            if (record == null) {
                throw new IllegalStateException("The calling process has no registered "
                        + "StickyModifierStateListener.");
            }
            if (record.mListener.asBinder() != listener.asBinder()) {
                throw new IllegalStateException("The calling process has a different registered "
                        + "StickyModifierStateListener.");
            }
            record.mListener.asBinder().unlinkToDeath(record, 0);
            mStickyModifierStateListenerRecords.remove(pid);
        }
    }

    private void onStickyModifierStateListenerDied(int pid) {
        synchronized (mStickyModifierStateListenerRecords) {
            mStickyModifierStateListenerRecords.remove(pid);
        }
    }

    // A record of a registered sticky modifier state listener from one process.
    private class StickyModifierStateListenerRecord implements IBinder.DeathRecipient {
        public final int mPid;
        public final IStickyModifierStateListener mListener;

        StickyModifierStateListenerRecord(int pid, IStickyModifierStateListener listener) {
            mPid = pid;
            mListener = listener;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Sticky modifier state listener for pid " + mPid + " died.");
            }
            onStickyModifierStateListenerDied(mPid);
        }

        public void notifyStickyModifierStateChanged(int modifierState, int lockedModifierState) {
            try {
                mListener.onStickyModifierStateChanged(modifierState, lockedModifierState);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid
                        + " that sticky modifier state changed, assuming it died.", ex);
                binderDied();
            }
        }
    }
}
