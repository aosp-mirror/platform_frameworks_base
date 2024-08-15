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
import android.hardware.input.IKeyGestureEventListener;
import android.hardware.input.KeyGestureEvent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

/**
 * A thread-safe component of {@link InputManagerService} responsible for managing callbacks when a
 * key gesture event occurs.
 */
final class KeyGestureController {

    private static final String TAG = "KeyGestureController";

    // To enable these logs, run:
    // 'adb shell setprop log.tag.KeyGestureController DEBUG' (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // List of currently registered key gesture event listeners keyed by process pid
    @GuardedBy("mKeyGestureEventListenerRecords")
    private final SparseArray<KeyGestureEventListenerRecord>
            mKeyGestureEventListenerRecords = new SparseArray<>();

    public void onKeyGestureEvent(KeyGestureEvent event) {
        if (DEBUG) {
            Slog.d(TAG, "Key gesture event occurred, event = " + event);
        }

        synchronized (mKeyGestureEventListenerRecords) {
            for (int i = 0; i < mKeyGestureEventListenerRecords.size(); i++) {
                mKeyGestureEventListenerRecords.valueAt(i).onKeyGestureEvent(event);
            }
        }
    }

    /** Register the key gesture event listener for a process. */
    @BinderThread
    public void registerKeyGestureEventListener(IKeyGestureEventListener listener,
            int pid) {
        synchronized (mKeyGestureEventListenerRecords) {
            if (mKeyGestureEventListenerRecords.get(pid) != null) {
                throw new IllegalStateException("The calling process has already registered "
                        + "a KeyGestureEventListener.");
            }
            KeyGestureEventListenerRecord record = new KeyGestureEventListenerRecord(
                    pid, listener);
            try {
                listener.asBinder().linkToDeath(record, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
            mKeyGestureEventListenerRecords.put(pid, record);
        }
    }

    /** Unregister the key gesture event listener for a process. */
    @BinderThread
    public void unregisterKeyGestureEventListener(IKeyGestureEventListener listener,
            int pid) {
        synchronized (mKeyGestureEventListenerRecords) {
            KeyGestureEventListenerRecord record =
                    mKeyGestureEventListenerRecords.get(pid);
            if (record == null) {
                throw new IllegalStateException("The calling process has no registered "
                        + "KeyGestureEventListener.");
            }
            if (record.mListener.asBinder() != listener.asBinder()) {
                throw new IllegalStateException("The calling process has a different registered "
                        + "KeyGestureEventListener.");
            }
            record.mListener.asBinder().unlinkToDeath(record, 0);
            mKeyGestureEventListenerRecords.remove(pid);
        }
    }

    private void onKeyGestureEventListenerDied(int pid) {
        synchronized (mKeyGestureEventListenerRecords) {
            mKeyGestureEventListenerRecords.remove(pid);
        }
    }

    // A record of a registered key gesture event listener from one process.
    private class KeyGestureEventListenerRecord implements IBinder.DeathRecipient {
        public final int mPid;
        public final IKeyGestureEventListener mListener;

        KeyGestureEventListenerRecord(int pid, IKeyGestureEventListener listener) {
            mPid = pid;
            mListener = listener;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Key gesture event listener for pid " + mPid + " died.");
            }
            onKeyGestureEventListenerDied(mPid);
        }

        public void onKeyGestureEvent(KeyGestureEvent event) {
            try {
                mListener.onKeyGestureEvent(event.getDeviceId(), event.getKeycodes(),
                        event.getModifierState(), event.getKeyGestureType());
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid
                        + " that key gesture event occurred, assuming it died.", ex);
                binderDied();
            }
        }
    }
}
