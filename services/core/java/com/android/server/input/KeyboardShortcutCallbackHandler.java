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
import android.hardware.input.IKeyboardSystemShortcutListener;
import android.hardware.input.KeyboardSystemShortcut;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

/**
 * A thread-safe component of {@link InputManagerService} responsible for managing callbacks when a
 * keyboard shortcut is triggered.
 */
final class KeyboardShortcutCallbackHandler {

    private static final String TAG = "KeyboardShortcut";

    // To enable these logs, run:
    // 'adb shell setprop log.tag.KeyboardShortcutCallbackHandler DEBUG' (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // List of currently registered keyboard system shortcut listeners keyed by process pid
    @GuardedBy("mKeyboardSystemShortcutListenerRecords")
    private final SparseArray<KeyboardSystemShortcutListenerRecord>
            mKeyboardSystemShortcutListenerRecords = new SparseArray<>();

    public void onKeyboardSystemShortcutTriggered(int deviceId,
            KeyboardSystemShortcut systemShortcut) {
        if (DEBUG) {
            Slog.d(TAG, "Keyboard system shortcut triggered, deviceId = " + deviceId
                    + ", systemShortcut = " + systemShortcut);
        }

        synchronized (mKeyboardSystemShortcutListenerRecords) {
            for (int i = 0; i < mKeyboardSystemShortcutListenerRecords.size(); i++) {
                mKeyboardSystemShortcutListenerRecords.valueAt(i).onKeyboardSystemShortcutTriggered(
                        deviceId, systemShortcut);
            }
        }
    }

    /** Register the keyboard system shortcut listener for a process. */
    @BinderThread
    public void registerKeyboardSystemShortcutListener(IKeyboardSystemShortcutListener listener,
            int pid) {
        synchronized (mKeyboardSystemShortcutListenerRecords) {
            if (mKeyboardSystemShortcutListenerRecords.get(pid) != null) {
                throw new IllegalStateException("The calling process has already registered "
                        + "a KeyboardSystemShortcutListener.");
            }
            KeyboardSystemShortcutListenerRecord record = new KeyboardSystemShortcutListenerRecord(
                    pid, listener);
            try {
                listener.asBinder().linkToDeath(record, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
            mKeyboardSystemShortcutListenerRecords.put(pid, record);
        }
    }

    /** Unregister the keyboard system shortcut listener for a process. */
    @BinderThread
    public void unregisterKeyboardSystemShortcutListener(IKeyboardSystemShortcutListener listener,
            int pid) {
        synchronized (mKeyboardSystemShortcutListenerRecords) {
            KeyboardSystemShortcutListenerRecord record =
                    mKeyboardSystemShortcutListenerRecords.get(pid);
            if (record == null) {
                throw new IllegalStateException("The calling process has no registered "
                        + "KeyboardSystemShortcutListener.");
            }
            if (record.mListener.asBinder() != listener.asBinder()) {
                throw new IllegalStateException("The calling process has a different registered "
                        + "KeyboardSystemShortcutListener.");
            }
            record.mListener.asBinder().unlinkToDeath(record, 0);
            mKeyboardSystemShortcutListenerRecords.remove(pid);
        }
    }

    private void onKeyboardSystemShortcutListenerDied(int pid) {
        synchronized (mKeyboardSystemShortcutListenerRecords) {
            mKeyboardSystemShortcutListenerRecords.remove(pid);
        }
    }

    // A record of a registered keyboard system shortcut listener from one process.
    private class KeyboardSystemShortcutListenerRecord implements IBinder.DeathRecipient {
        public final int mPid;
        public final IKeyboardSystemShortcutListener mListener;

        KeyboardSystemShortcutListenerRecord(int pid, IKeyboardSystemShortcutListener listener) {
            mPid = pid;
            mListener = listener;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Keyboard system shortcut listener for pid " + mPid + " died.");
            }
            onKeyboardSystemShortcutListenerDied(mPid);
        }

        public void onKeyboardSystemShortcutTriggered(int deviceId, KeyboardSystemShortcut data) {
            try {
                mListener.onKeyboardSystemShortcutTriggered(deviceId, data.getKeycodes(),
                        data.getModifierState(), data.getSystemShortcut());
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid
                        + " that keyboard system shortcut was triggered, assuming it died.", ex);
                binderDied();
            }
        }
    }
}
