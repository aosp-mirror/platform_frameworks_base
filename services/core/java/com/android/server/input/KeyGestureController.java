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
import android.annotation.MainThread;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.input.AidlKeyGestureEvent;
import android.hardware.input.IKeyGestureEventListener;
import android.hardware.input.IKeyGestureHandler;
import android.hardware.input.InputManager;
import android.hardware.input.KeyGestureEvent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;
import java.util.TreeMap;

/**
 * A thread-safe component of {@link InputManagerService} responsible for managing callbacks when a
 * key gesture event occurs.
 */
final class KeyGestureController {

    private static final String TAG = "KeyGestureController";

    // To enable these logs, run:
    // 'adb shell setprop log.tag.KeyGestureController DEBUG' (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int MSG_NOTIFY_KEY_GESTURE_EVENT = 1;

    private final Context mContext;
    private final Handler mHandler;
    private final int mSystemPid;

    // List of currently registered key gesture event listeners keyed by process pid
    @GuardedBy("mKeyGestureEventListenerRecords")
    private final SparseArray<KeyGestureEventListenerRecord>
            mKeyGestureEventListenerRecords = new SparseArray<>();

    // List of currently registered key gesture event handler keyed by process pid. The map sorts
    // in the order of preference of the handlers, and we prioritize handlers in system server
    // over external handlers..
    @GuardedBy("mKeyGestureHandlerRecords")
    private final TreeMap<Integer, KeyGestureHandlerRecord> mKeyGestureHandlerRecords;

    KeyGestureController(Context context, Looper looper) {
        mContext = context;
        mHandler = new Handler(looper, this::handleMessage);
        mSystemPid = Process.myPid();
        mKeyGestureHandlerRecords = new TreeMap<>((p1, p2) -> {
            if (Objects.equals(p1, p2)) {
                return 0;
            }
            if (p1 == mSystemPid) {
                return -1;
            } else if (p2 == mSystemPid) {
                return 1;
            } else {
                return Integer.compare(p1, p2);
            }
        });
    }

    public int interceptKeyBeforeDispatching(IBinder focus, KeyEvent event, int policyFlags) {
        // TODO(b/358569822): Handle shortcuts trigger logic here and pass it to appropriate
        //  KeyGestureHandler (PWM is one of the handlers)
        return 0;
    }

    @VisibleForTesting
    boolean handleKeyGesture(int deviceId, int[] keycodes, int modifierState,
            @KeyGestureEvent.KeyGestureType int gestureType, int action, int displayId,
            IBinder focusedToken, int flags) {
        AidlKeyGestureEvent event = createKeyGestureEvent(deviceId, keycodes,
                modifierState, gestureType, action, displayId, flags);
        synchronized (mKeyGestureHandlerRecords) {
            for (KeyGestureHandlerRecord handler : mKeyGestureHandlerRecords.values()) {
                if (handler.handleKeyGesture(event, focusedToken)) {
                    Message msg = Message.obtain(mHandler, MSG_NOTIFY_KEY_GESTURE_EVENT, event);
                    mHandler.sendMessage(msg);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isKeyGestureSupported(@KeyGestureEvent.KeyGestureType int gestureType) {
        synchronized (mKeyGestureHandlerRecords) {
            for (KeyGestureHandlerRecord handler : mKeyGestureHandlerRecords.values()) {
                if (handler.isKeyGestureSupported(gestureType)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void notifyKeyGestureCompleted(int deviceId, int[] keycodes, int modifierState,
            @KeyGestureEvent.KeyGestureType int gestureType) {
        // TODO(b/358569822): Once we move the gesture detection logic to IMS, we ideally
        //  should not rely on PWM to tell us about the gesture start and end.
        AidlKeyGestureEvent event = createKeyGestureEvent(deviceId, keycodes, modifierState,
                gestureType, KeyGestureEvent.ACTION_GESTURE_COMPLETE, Display.DEFAULT_DISPLAY, 0);
        mHandler.obtainMessage(MSG_NOTIFY_KEY_GESTURE_EVENT, event).sendToTarget();
    }

    @MainThread
    private void notifyKeyGestureEvent(AidlKeyGestureEvent event) {
        InputDevice device = getInputDevice(event.deviceId);
        if (device == null || device.isVirtual()) {
            return;
        }
        if (event.action == KeyGestureEvent.ACTION_GESTURE_COMPLETE) {
            KeyboardMetricsCollector.logKeyboardSystemsEventReportedAtom(device, event.keycodes,
                    event.modifierState,
                    KeyGestureEvent.keyGestureTypeToLogEvent(event.gestureType));
        }
        notifyAllListeners(event);
    }

    @MainThread
    private void notifyAllListeners(AidlKeyGestureEvent event) {
        if (DEBUG) {
            Slog.d(TAG, "Key gesture event occurred, event = " + event);
        }

        synchronized (mKeyGestureEventListenerRecords) {
            for (int i = 0; i < mKeyGestureEventListenerRecords.size(); i++) {
                mKeyGestureEventListenerRecords.valueAt(i).onKeyGestureEvent(event);
            }
        }
    }

    @MainThread
    private boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_NOTIFY_KEY_GESTURE_EVENT:
                AidlKeyGestureEvent event = (AidlKeyGestureEvent) msg.obj;
                notifyKeyGestureEvent(event);
                break;
        }
        return true;
    }

    /** Register the key gesture event listener for a process. */
    @BinderThread
    public void registerKeyGestureEventListener(IKeyGestureEventListener listener, int pid) {
        synchronized (mKeyGestureEventListenerRecords) {
            if (mKeyGestureEventListenerRecords.get(pid) != null) {
                throw new IllegalStateException("The calling process has already registered "
                        + "a KeyGestureEventListener.");
            }
            KeyGestureEventListenerRecord record = new KeyGestureEventListenerRecord(pid, listener);
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
    public void unregisterKeyGestureEventListener(IKeyGestureEventListener listener, int pid) {
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

        public void onKeyGestureEvent(AidlKeyGestureEvent event) {
            try {
                mListener.onKeyGestureEvent(event);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid
                        + " that key gesture event occurred, assuming it died.", ex);
                binderDied();
            }
        }
    }

    /** Register the key gesture event handler for a process. */
    @BinderThread
    public void registerKeyGestureHandler(IKeyGestureHandler handler, int pid) {
        synchronized (mKeyGestureHandlerRecords) {
            if (mKeyGestureHandlerRecords.get(pid) != null) {
                throw new IllegalStateException("The calling process has already registered "
                        + "a KeyGestureHandler.");
            }
            KeyGestureHandlerRecord record = new KeyGestureHandlerRecord(pid, handler);
            try {
                handler.asBinder().linkToDeath(record, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
            mKeyGestureHandlerRecords.put(pid, record);
        }
    }

    /** Unregister the key gesture event handler for a process. */
    @BinderThread
    public void unregisterKeyGestureHandler(IKeyGestureHandler handler, int pid) {
        synchronized (mKeyGestureHandlerRecords) {
            KeyGestureHandlerRecord record = mKeyGestureHandlerRecords.get(pid);
            if (record == null) {
                throw new IllegalStateException("The calling process has no registered "
                        + "KeyGestureHandler.");
            }
            if (record.mKeyGestureHandler.asBinder() != handler.asBinder()) {
                throw new IllegalStateException("The calling process has a different registered "
                        + "KeyGestureHandler.");
            }
            record.mKeyGestureHandler.asBinder().unlinkToDeath(record, 0);
            mKeyGestureHandlerRecords.remove(pid);
        }
    }

    private void onKeyGestureHandlerDied(int pid) {
        synchronized (mKeyGestureHandlerRecords) {
            mKeyGestureHandlerRecords.remove(pid);
        }
    }

    // A record of a registered key gesture event listener from one process.
    private class KeyGestureHandlerRecord implements IBinder.DeathRecipient {
        public final int mPid;
        public final IKeyGestureHandler mKeyGestureHandler;

        KeyGestureHandlerRecord(int pid, IKeyGestureHandler keyGestureHandler) {
            mPid = pid;
            mKeyGestureHandler = keyGestureHandler;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Key gesture event handler for pid " + mPid + " died.");
            }
            onKeyGestureHandlerDied(mPid);
        }

        public boolean handleKeyGesture(AidlKeyGestureEvent event, IBinder focusedToken) {
            try {
                return mKeyGestureHandler.handleKeyGesture(event, focusedToken);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to send key gesture to process " + mPid
                        + ", assuming it died.", ex);
                binderDied();
            }
            return false;
        }

        public boolean isKeyGestureSupported(@KeyGestureEvent.KeyGestureType int gestureType) {
            try {
                return mKeyGestureHandler.isKeyGestureSupported(gestureType);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to identify if key gesture type is supported by the "
                        + "process " + mPid + ", assuming it died.", ex);
                binderDied();
            }
            return false;
        }
    }

    @Nullable
    private InputDevice getInputDevice(int deviceId) {
        InputManager inputManager = mContext.getSystemService(InputManager.class);
        return inputManager != null ? inputManager.getInputDevice(deviceId) : null;
    }

    private AidlKeyGestureEvent createKeyGestureEvent(int deviceId, int[] keycodes,
            int modifierState, @KeyGestureEvent.KeyGestureType int gestureType, int action,
            int displayId, int flags) {
        AidlKeyGestureEvent event = new AidlKeyGestureEvent();
        event.deviceId = deviceId;
        event.keycodes = keycodes;
        event.modifierState = modifierState;
        event.gestureType = gestureType;
        event.action = action;
        event.displayId = displayId;
        event.flags = flags;
        return event;
    }
}
