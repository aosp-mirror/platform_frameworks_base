/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.accessibility;

import android.accessibilityservice.IAccessibilityServiceClient;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManagerPolicy;
import android.view.accessibility.AccessibilityEvent;
import com.android.internal.os.SomeArgs;
import com.android.server.accessibility.AccessibilityManagerService.Service;

import java.util.List;

/**
 * Injects MotionEvents to permit {@code AccessibilityService}s to touch the screen on behalf of
 * users.
 *
 * All methods except {@code injectEvents} must be called only from the main thread.
 */
public class MotionEventInjector implements EventStreamTransformation {
    private static final String LOG_TAG = "MotionEventInjector";
    private static final int MESSAGE_SEND_MOTION_EVENT = 1;
    private static final int MESSAGE_INJECT_EVENTS = 2;
    private static final int MAX_POINTERS = 11; // Non-binding maximum

    private final Handler mHandler;
    private final SparseArray<Boolean> mOpenGesturesInProgress = new SparseArray<>();

    // These two arrays must be the same length
    private MotionEvent.PointerProperties[] mPointerProperties =
            new MotionEvent.PointerProperties[MAX_POINTERS];
    private MotionEvent.PointerCoords[] mPointerCoords =
            new MotionEvent.PointerCoords[MAX_POINTERS];
    private EventStreamTransformation mNext;
    private IAccessibilityServiceClient mServiceInterfaceForCurrentGesture;
    private int mSequenceForCurrentGesture;
    private int mSourceOfInjectedGesture = InputDevice.SOURCE_UNKNOWN;
    private boolean mIsDestroyed = false;

    /**
     * @param looper A looper on the main thread to use for dispatching new events
     */
    public MotionEventInjector(Looper looper) {
        mHandler = new Handler(looper, new Callback());
    }

    /**
     * Schedule a series of events for injection. These events must comprise a complete, valid
     * sequence. All gestures currently in progress will be cancelled, and all {@code downTime}
     * and {@code eventTime} fields will be offset by the current time.
     *
     * @param events The events to inject. Must all be from the same source.
     * @param serviceInterface The interface to call back with a result when the gesture is
     * either complete or cancelled.
     */
    public void injectEvents(List<MotionEvent> events,
            IAccessibilityServiceClient serviceInterface, int sequence) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = events;
        args.arg2 = serviceInterface;
        args.argi1 = sequence;
        mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_INJECT_EVENTS, args));
    }

    @Override
    public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelAnyPendingInjectedEvents();
        sendMotionEventToNext(event, rawEvent, policyFlags);
    }

    @Override
    public void onKeyEvent(KeyEvent event, int policyFlags) {
        if (mNext != null) {
            mNext.onKeyEvent(event, policyFlags);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (mNext != null) {
            mNext.onAccessibilityEvent(event);
        }
    }

    @Override
    public void setNext(EventStreamTransformation next) {
        mNext = next;
    }

    @Override
    public void clearEvents(int inputSource) {
        /*
         * Reset state for motion events passing through so we won't send a cancel event for
         * them.
         */
        if (!mHandler.hasMessages(MESSAGE_SEND_MOTION_EVENT)) {
            mOpenGesturesInProgress.put(inputSource, false);
        }
    }

    @Override
    public void onDestroy() {
        cancelAnyPendingInjectedEvents();
        mIsDestroyed = true;
    }

    private void injectEventsMainThread(List<MotionEvent> events,
            IAccessibilityServiceClient serviceInterface, int sequence) {
        if (mIsDestroyed) {
            try {
                serviceInterface.onPerformGestureResult(sequence, false);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending status with mIsDestroyed to " + serviceInterface,
                        re);
            }
            return;
        }
        cancelAnyPendingInjectedEvents();
        mSourceOfInjectedGesture = events.get(0).getSource();
        cancelAnyGestureInProgress(mSourceOfInjectedGesture);
        mServiceInterfaceForCurrentGesture = serviceInterface;
        mSequenceForCurrentGesture = sequence;
        if (mNext == null) {
            notifyService(false);
            return;
        }

        long startTime = SystemClock.uptimeMillis();
        for (int i = 0; i < events.size(); i++) {
            MotionEvent event = events.get(i);
            int numPointers = event.getPointerCount();
            if (numPointers > mPointerCoords.length) {
                mPointerCoords = new MotionEvent.PointerCoords[numPointers];
                mPointerProperties = new MotionEvent.PointerProperties[numPointers];
            }
            for (int j = 0; j < numPointers; j++) {
                if (mPointerCoords[j] == null) {
                    mPointerCoords[j] = new MotionEvent.PointerCoords();
                    mPointerProperties[j] = new MotionEvent.PointerProperties();
                }
                event.getPointerCoords(j, mPointerCoords[j]);
                event.getPointerProperties(j, mPointerProperties[j]);
            }

            /*
             * MotionEvent doesn't have a setEventTime() method (it carries around history data,
             * which could become inconsistent), so we need to obtain a new one.
             */
            MotionEvent offsetEvent = MotionEvent.obtain(startTime + event.getDownTime(),
                    startTime + event.getEventTime(), event.getAction(), numPointers,
                    mPointerProperties, mPointerCoords, event.getMetaState(),
                    event.getButtonState(), event.getXPrecision(), event.getYPrecision(),
                    event.getDeviceId(), event.getEdgeFlags(), event.getSource(),
                    event.getFlags());
            Message message = mHandler.obtainMessage(MESSAGE_SEND_MOTION_EVENT, offsetEvent);
            mHandler.sendMessageDelayed(message, event.getEventTime());
        }
    }

    private void sendMotionEventToNext(MotionEvent event, MotionEvent rawEvent,
            int policyFlags) {
        if (mNext != null) {
            mNext.onMotionEvent(event, rawEvent, policyFlags);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mOpenGesturesInProgress.put(event.getSource(), true);
            }
            if ((event.getActionMasked() == MotionEvent.ACTION_UP)
                    || (event.getActionMasked() == MotionEvent.ACTION_CANCEL)) {
                mOpenGesturesInProgress.put(event.getSource(), false);
            }
        }
    }

    private void cancelAnyGestureInProgress(int source) {
        if ((mNext != null) && mOpenGesturesInProgress.get(source, false)) {
            long now = SystemClock.uptimeMillis();
            MotionEvent cancelEvent =
                    MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
            sendMotionEventToNext(cancelEvent, cancelEvent,
                    WindowManagerPolicy.FLAG_PASS_TO_USER);
        }
    }

    private void cancelAnyPendingInjectedEvents() {
        if (mHandler.hasMessages(MESSAGE_SEND_MOTION_EVENT)) {
            cancelAnyGestureInProgress(mSourceOfInjectedGesture);
            mHandler.removeMessages(MESSAGE_SEND_MOTION_EVENT);
            notifyService(false);
        }

    }

    private void notifyService(boolean success) {
        try {
            mServiceInterfaceForCurrentGesture.onPerformGestureResult(
                    mSequenceForCurrentGesture, success);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error sending motion event injection status to "
                    + mServiceInterfaceForCurrentGesture, re);
        }
    }

    private class Callback implements Handler.Callback {
        @Override
        public boolean handleMessage(Message message) {
            if (message.what == MESSAGE_INJECT_EVENTS) {
                SomeArgs args = (SomeArgs) message.obj;
                injectEventsMainThread((List<MotionEvent>) args.arg1,
                        (IAccessibilityServiceClient) args.arg2, args.argi1);
                args.recycle();
                return true;
            }
            if (message.what != MESSAGE_SEND_MOTION_EVENT) {
                throw new IllegalArgumentException("Unknown message: " + message.what);
            }
            MotionEvent motionEvent = (MotionEvent) message.obj;
            sendMotionEventToNext(motionEvent, motionEvent,
                    WindowManagerPolicy.FLAG_PASS_TO_USER);
            // If the message queue is now empty, then this gesture is complete
            if (!mHandler.hasMessages(MESSAGE_SEND_MOTION_EVENT)) {
                notifyService(true);
            }
            return true;
        }
    }
}
