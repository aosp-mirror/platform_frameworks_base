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

import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.GestureDescription.GestureStep;
import android.accessibilityservice.GestureDescription.TouchPoint;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.MotionEvent;

import com.android.internal.os.SomeArgs;
import com.android.server.policy.WindowManagerPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Injects MotionEvents to permit {@code AccessibilityService}s to touch the screen on behalf of
 * users.
 * <p>
 * All methods except {@code injectEvents} must be called only from the main thread.
 */
public class MotionEventInjector extends BaseEventStreamTransformation implements Handler.Callback {
    private static final String LOG_TAG = "MotionEventInjector";
    private static final int MESSAGE_SEND_MOTION_EVENT = 1;
    private static final int MESSAGE_INJECT_EVENTS = 2;

    /**
     * Constants used to initialize all MotionEvents
     */
    private static final int EVENT_META_STATE = 0;
    private static final int EVENT_BUTTON_STATE = 0;
    private static final int EVENT_DEVICE_ID = 0;
    private static final int EVENT_EDGE_FLAGS = 0;
    private static final int EVENT_SOURCE = InputDevice.SOURCE_TOUCHSCREEN;
    private static final int EVENT_FLAGS = 0;
    private static final float EVENT_X_PRECISION = 1;
    private static final float EVENT_Y_PRECISION = 1;

    private static MotionEvent.PointerCoords[] sPointerCoords;
    private static MotionEvent.PointerProperties[] sPointerProps;

    private final Handler mHandler;
    private final SparseArray<Boolean> mOpenGesturesInProgress = new SparseArray<>();

    private IAccessibilityServiceClient mServiceInterfaceForCurrentGesture;
    private IntArray mSequencesInProgress = new IntArray(5);
    private boolean mIsDestroyed = false;
    private TouchPoint[] mLastTouchPoints;
    private int mNumLastTouchPoints;
    private long mDownTime;
    private long mLastScheduledEventTime;
    private SparseIntArray mStrokeIdToPointerId = new SparseIntArray(5);

    /**
     * @param looper A looper on the main thread to use for dispatching new events
     */
    public MotionEventInjector(Looper looper) {
        mHandler = new Handler(looper, this);
    }

    /**
     * @param handler A handler to post messages. Exposes internal state for testing only.
     */
    public MotionEventInjector(Handler handler) {
        mHandler = handler;
    }

    /**
     * Schedule a gesture for injection. The gesture is defined by a set of {@code GestureStep}s,
     * from which {@code MotionEvent}s will be derived. All gestures currently in progress will be
     * cancelled.
     *
     * @param gestureSteps The gesture steps to inject.
     * @param serviceInterface The interface to call back with a result when the gesture is
     * either complete or cancelled.
     */
    public void injectEvents(List<GestureStep> gestureSteps,
            IAccessibilityServiceClient serviceInterface, int sequence, int displayId) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = gestureSteps;
        args.arg2 = serviceInterface;
        args.argi1 = sequence;
        args.argi2 = displayId;
        mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_INJECT_EVENTS, args));
    }

    @Override
    public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // MotionEventInjector would cancel any injected gesture when any MotionEvent arrives.
        // For user using an external device to control the pointer movement, it's almost
        // impossible to perform the gestures. Any slightly unintended movement results in the
        // cancellation of the gesture.
        if ((event.isFromSource(InputDevice.SOURCE_MOUSE)
                && event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE)
                && mOpenGesturesInProgress.get(EVENT_SOURCE, false)) {
            return;
        }
        cancelAnyPendingInjectedEvents();
        sendMotionEventToNext(event, rawEvent, policyFlags);
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

    @Override
    public boolean handleMessage(Message message) {
        if (message.what == MESSAGE_INJECT_EVENTS) {
            SomeArgs args = (SomeArgs) message.obj;
            injectEventsMainThread((List<GestureStep>) args.arg1,
                    (IAccessibilityServiceClient) args.arg2, args.argi1, args.argi2);
            args.recycle();
            return true;
        }
        if (message.what != MESSAGE_SEND_MOTION_EVENT) {
            Slog.e(LOG_TAG, "Unknown message: " + message.what);
            return false;
        }
        MotionEvent motionEvent = (MotionEvent) message.obj;
        sendMotionEventToNext(motionEvent, motionEvent, WindowManagerPolicy.FLAG_PASS_TO_USER);
        boolean isEndOfSequence = message.arg1 != 0;
        if (isEndOfSequence) {
            notifyService(mServiceInterfaceForCurrentGesture, mSequencesInProgress.get(0), true);
            mSequencesInProgress.remove(0);
        }
        return true;
    }

    private void injectEventsMainThread(List<GestureStep> gestureSteps,
            IAccessibilityServiceClient serviceInterface, int sequence, int displayId) {
        if (mIsDestroyed) {
            try {
                serviceInterface.onPerformGestureResult(sequence, false);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending status with mIsDestroyed to " + serviceInterface,
                        re);
            }
            return;
        }

        if (getNext() == null) {
            notifyService(serviceInterface, sequence, false);
            return;
        }

        boolean continuingGesture = newGestureTriesToContinueOldOne(gestureSteps);

        if (continuingGesture) {
            if ((serviceInterface != mServiceInterfaceForCurrentGesture)
                    || !prepareToContinueOldGesture(gestureSteps)) {
                cancelAnyPendingInjectedEvents();
                notifyService(serviceInterface, sequence, false);
                return;
            }
        }
        if (!continuingGesture) {
            cancelAnyPendingInjectedEvents();
            // Injected gestures have been canceled, but real gestures still need cancelling
            cancelAnyGestureInProgress(EVENT_SOURCE);
        }
        mServiceInterfaceForCurrentGesture = serviceInterface;

        long currentTime = SystemClock.uptimeMillis();
        List<MotionEvent> events = getMotionEventsFromGestureSteps(gestureSteps,
                (mSequencesInProgress.size() == 0) ? currentTime : mLastScheduledEventTime);
        if (events.isEmpty()) {
            notifyService(serviceInterface, sequence, false);
            return;
        }
        mSequencesInProgress.add(sequence);

        for (int i = 0; i < events.size(); i++) {
            MotionEvent event = events.get(i);
            event.setDisplayId(displayId);
            int isEndOfSequence = (i == events.size() - 1) ? 1 : 0;
            Message message = mHandler.obtainMessage(
                    MESSAGE_SEND_MOTION_EVENT, isEndOfSequence, 0, event);
            mLastScheduledEventTime = event.getEventTime();
            mHandler.sendMessageDelayed(message, Math.max(0, event.getEventTime() - currentTime));
        }
    }

    private boolean newGestureTriesToContinueOldOne(List<GestureStep> gestureSteps) {
        if (gestureSteps.isEmpty()) {
            return false;
        }
        GestureStep firstStep = gestureSteps.get(0);
        for (int i = 0; i < firstStep.numTouchPoints; i++) {
            if (!firstStep.touchPoints[i].mIsStartOfPath) {
                return true;
            }
        }
        return false;
    }

    /**
     * A gesture can only continue a gesture if it contains intermediate points that continue
     * each continued stroke of the last gesture, and no extra points.
     *
     * @param gestureSteps The steps of the new gesture
     * @return {@code true} if the new gesture could continue the last one dispatched. {@code false}
     * otherwise.
     */
    private boolean prepareToContinueOldGesture(List<GestureStep> gestureSteps) {
        if (gestureSteps.isEmpty() || (mLastTouchPoints == null) || (mNumLastTouchPoints == 0)) {
            return false;
        }
        GestureStep firstStep = gestureSteps.get(0);
        // Make sure all of the continuing paths match up
        int numContinuedStrokes = 0;
        for (int i = 0; i < firstStep.numTouchPoints; i++) {
            TouchPoint touchPoint = firstStep.touchPoints[i];
            if (!touchPoint.mIsStartOfPath) {
                int continuedPointerId = mStrokeIdToPointerId
                        .get(touchPoint.mContinuedStrokeId, -1);
                if (continuedPointerId == -1) {
                    Slog.w(LOG_TAG, "Can't continue gesture due to unknown continued stroke id in "
                            + touchPoint);
                    return false;
                }
                mStrokeIdToPointerId.put(touchPoint.mStrokeId, continuedPointerId);
                int lastPointIndex = findPointByStrokeId(
                        mLastTouchPoints, mNumLastTouchPoints, touchPoint.mContinuedStrokeId);
                if (lastPointIndex < 0) {
                    Slog.w(LOG_TAG, "Can't continue gesture due continued gesture id of "
                            + touchPoint + " not matching any previous strokes in "
                            + Arrays.asList(mLastTouchPoints));
                    return false;
                }
                if (mLastTouchPoints[lastPointIndex].mIsEndOfPath
                        || (mLastTouchPoints[lastPointIndex].mX != touchPoint.mX)
                        || (mLastTouchPoints[lastPointIndex].mY != touchPoint.mY)) {
                    Slog.w(LOG_TAG, "Can't continue gesture due to points mismatch between "
                            + mLastTouchPoints[lastPointIndex] + " and " + touchPoint);
                    return false;
                }
                // Update the last touch point to match the continuation, so the gestures will
                // line up
                mLastTouchPoints[lastPointIndex].mStrokeId = touchPoint.mStrokeId;
            }
            numContinuedStrokes++;
        }
        // Make sure we didn't miss any paths
        for (int i = 0; i < mNumLastTouchPoints; i++) {
            if (!mLastTouchPoints[i].mIsEndOfPath) {
                numContinuedStrokes--;
            }
        }
        return numContinuedStrokes == 0;
    }

    private void sendMotionEventToNext(MotionEvent event, MotionEvent rawEvent,
            int policyFlags) {
        if (getNext() != null) {
            super.onMotionEvent(event, rawEvent, policyFlags);
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
        if ((getNext() != null) && mOpenGesturesInProgress.get(source, false)) {
            long now = SystemClock.uptimeMillis();
            MotionEvent cancelEvent =
                    obtainMotionEvent(now, now, MotionEvent.ACTION_CANCEL, getLastTouchPoints(), 1);
            sendMotionEventToNext(cancelEvent, cancelEvent,
                    WindowManagerPolicy.FLAG_PASS_TO_USER);
            mOpenGesturesInProgress.put(source, false);
        }
    }

    private void cancelAnyPendingInjectedEvents() {
        if (mHandler.hasMessages(MESSAGE_SEND_MOTION_EVENT)) {
            mHandler.removeMessages(MESSAGE_SEND_MOTION_EVENT);
            cancelAnyGestureInProgress(EVENT_SOURCE);
            for (int i = mSequencesInProgress.size() - 1; i >= 0; i--) {
                notifyService(mServiceInterfaceForCurrentGesture,
                        mSequencesInProgress.get(i), false);
                mSequencesInProgress.remove(i);
            }
        } else if (mNumLastTouchPoints != 0) {
            // An injected gesture is in progress and waiting for a continuation. Cancel it.
            cancelAnyGestureInProgress(EVENT_SOURCE);
        }
        mNumLastTouchPoints = 0;
        mStrokeIdToPointerId.clear();
    }

    private void notifyService(IAccessibilityServiceClient service, int sequence, boolean success) {
        try {
            service.onPerformGestureResult(sequence, success);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error sending motion event injection status to "
                    + mServiceInterfaceForCurrentGesture, re);
        }
    }

    private List<MotionEvent> getMotionEventsFromGestureSteps(
            List<GestureStep> steps, long startTime) {
        final List<MotionEvent> motionEvents = new ArrayList<>();

        TouchPoint[] lastTouchPoints = getLastTouchPoints();

        for (int i = 0; i < steps.size(); i++) {
            GestureDescription.GestureStep step = steps.get(i);
            int currentTouchPointSize = step.numTouchPoints;
            if (currentTouchPointSize > lastTouchPoints.length) {
                mNumLastTouchPoints = 0;
                motionEvents.clear();
                return motionEvents;
            }

            appendMoveEventIfNeeded(motionEvents, step.touchPoints, currentTouchPointSize,
                    startTime + step.timeSinceGestureStart);
            appendUpEvents(motionEvents, step.touchPoints, currentTouchPointSize,
                    startTime + step.timeSinceGestureStart);
            appendDownEvents(motionEvents, step.touchPoints, currentTouchPointSize,
                    startTime + step.timeSinceGestureStart);
        }
        return motionEvents;
    }

    private TouchPoint[] getLastTouchPoints() {
        if (mLastTouchPoints == null) {
            int capacity = GestureDescription.getMaxStrokeCount();
            mLastTouchPoints = new TouchPoint[capacity];
            for (int i = 0; i < capacity; i++) {
                mLastTouchPoints[i] = new GestureDescription.TouchPoint();
            }
        }
        return mLastTouchPoints;
    }

    private void appendMoveEventIfNeeded(List<MotionEvent> motionEvents,
            TouchPoint[] currentTouchPoints, int currentTouchPointsSize, long currentTime) {
            /* Look for pointers that have moved */
        boolean moveFound = false;
        TouchPoint[] lastTouchPoints = getLastTouchPoints();
        for (int i = 0; i < currentTouchPointsSize; i++) {
            int lastPointsIndex = findPointByStrokeId(lastTouchPoints, mNumLastTouchPoints,
                    currentTouchPoints[i].mStrokeId);
            if (lastPointsIndex >= 0) {
                moveFound |= (lastTouchPoints[lastPointsIndex].mX != currentTouchPoints[i].mX)
                        || (lastTouchPoints[lastPointsIndex].mY != currentTouchPoints[i].mY);
                lastTouchPoints[lastPointsIndex].copyFrom(currentTouchPoints[i]);
            }
        }

        if (moveFound) {
            motionEvents.add(obtainMotionEvent(mDownTime, currentTime, MotionEvent.ACTION_MOVE,
                    lastTouchPoints, mNumLastTouchPoints));
        }
    }

    private void appendUpEvents(List<MotionEvent> motionEvents,
            TouchPoint[] currentTouchPoints, int currentTouchPointsSize, long currentTime) {
        /* Look for a pointer at the end of its path */
        TouchPoint[] lastTouchPoints = getLastTouchPoints();
        for (int i = 0; i < currentTouchPointsSize; i++) {
            if (currentTouchPoints[i].mIsEndOfPath) {
                int indexOfUpEvent = findPointByStrokeId(lastTouchPoints, mNumLastTouchPoints,
                        currentTouchPoints[i].mStrokeId);
                if (indexOfUpEvent < 0) {
                    continue; // Should not happen
                }
                int action = (mNumLastTouchPoints == 1) ? MotionEvent.ACTION_UP
                        : MotionEvent.ACTION_POINTER_UP;
                action |= indexOfUpEvent << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                motionEvents.add(obtainMotionEvent(mDownTime, currentTime, action,
                        lastTouchPoints, mNumLastTouchPoints));
                    /* Remove this point from lastTouchPoints */
                for (int j = indexOfUpEvent; j < mNumLastTouchPoints - 1; j++) {
                    lastTouchPoints[j].copyFrom(mLastTouchPoints[j + 1]);
                }
                mNumLastTouchPoints--;
                if (mNumLastTouchPoints == 0) {
                    mStrokeIdToPointerId.clear();
                }
            }
        }
    }

    private void appendDownEvents(List<MotionEvent> motionEvents,
            TouchPoint[] currentTouchPoints, int currentTouchPointsSize, long currentTime) {
        /* Look for a pointer that is just starting */
        TouchPoint[] lastTouchPoints = getLastTouchPoints();
        for (int i = 0; i < currentTouchPointsSize; i++) {
            if (currentTouchPoints[i].mIsStartOfPath) {
                /* Add the point to last coords and use the new array to generate the event */
                lastTouchPoints[mNumLastTouchPoints++].copyFrom(currentTouchPoints[i]);
                int action = (mNumLastTouchPoints == 1) ? MotionEvent.ACTION_DOWN
                        : MotionEvent.ACTION_POINTER_DOWN;
                if (action == MotionEvent.ACTION_DOWN) {
                    mDownTime = currentTime;
                }
                action |= i << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                motionEvents.add(obtainMotionEvent(mDownTime, currentTime, action,
                        lastTouchPoints, mNumLastTouchPoints));
            }
        }
    }

    private MotionEvent obtainMotionEvent(long downTime, long eventTime, int action,
            TouchPoint[] touchPoints, int touchPointsSize) {
        if ((sPointerCoords == null) || (sPointerCoords.length < touchPointsSize)) {
            sPointerCoords = new MotionEvent.PointerCoords[touchPointsSize];
            for (int i = 0; i < touchPointsSize; i++) {
                sPointerCoords[i] = new MotionEvent.PointerCoords();
            }
        }
        if ((sPointerProps == null) || (sPointerProps.length < touchPointsSize)) {
            sPointerProps = new MotionEvent.PointerProperties[touchPointsSize];
            for (int i = 0; i < touchPointsSize; i++) {
                sPointerProps[i] = new MotionEvent.PointerProperties();
            }
        }
        for (int i = 0; i < touchPointsSize; i++) {
            int pointerId = mStrokeIdToPointerId.get(touchPoints[i].mStrokeId, -1);
            if (pointerId == -1) {
                pointerId = getUnusedPointerId();
                mStrokeIdToPointerId.put(touchPoints[i].mStrokeId, pointerId);
            }
            sPointerProps[i].id = pointerId;
            sPointerProps[i].toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
            sPointerCoords[i].clear();
            sPointerCoords[i].pressure = 1.0f;
            sPointerCoords[i].size = 1.0f;
            sPointerCoords[i].x = touchPoints[i].mX;
            sPointerCoords[i].y = touchPoints[i].mY;
        }
        return MotionEvent.obtain(downTime, eventTime, action, touchPointsSize,
                sPointerProps, sPointerCoords, EVENT_META_STATE, EVENT_BUTTON_STATE,
                EVENT_X_PRECISION, EVENT_Y_PRECISION, EVENT_DEVICE_ID, EVENT_EDGE_FLAGS,
                EVENT_SOURCE, EVENT_FLAGS);
    }

    private static int findPointByStrokeId(TouchPoint[] touchPoints, int touchPointsSize,
            int strokeId) {
        for (int i = 0; i < touchPointsSize; i++) {
            if (touchPoints[i].mStrokeId == strokeId) {
                return i;
            }
        }
        return -1;
    }
    private int getUnusedPointerId() {
        int MAX_POINTER_ID = 10;
        int pointerId = 0;
        while (mStrokeIdToPointerId.indexOfValue(pointerId) >= 0) {
            pointerId++;
            if (pointerId >= MAX_POINTER_ID) {
                return MAX_POINTER_ID;
            }
        }
        return pointerId;
    }
}
