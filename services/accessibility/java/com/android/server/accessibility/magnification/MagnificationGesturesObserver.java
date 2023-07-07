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

package com.android.server.accessibility.magnification;

import static com.android.server.accessibility.magnification.MagnificationGestureMatcher.GestureId;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.util.Log;
import android.util.Slog;
import android.view.MotionEvent;

import com.android.server.accessibility.gestures.GestureMatcher;

import java.util.LinkedList;
import java.util.List;

/**
 * Observes multiple {@link GestureMatcher} via {@link GesturesObserver}. In the observing duration,
 * the event stream will be cached and sent through {@link Callback}.
 *
 */
class MagnificationGesturesObserver implements GesturesObserver.Listener {

    private static final String LOG_TAG = "MagnificationGesturesObserver";
    @SuppressLint("LongLogTag")
    private static final boolean DBG = Log.isLoggable(LOG_TAG, Log.DEBUG);

    /**
     * An Interface to determine if canceling detection and invoke the callbacks if the detection
     * has a result.
     */
    interface Callback {
        /**
         * Called when receiving the event stream.
         *
         * @param motionEvent The received {@link MotionEvent}.
         * @return {@code true} to cancel the detection.
         */
        boolean shouldStopDetection(MotionEvent motionEvent);

        /**
         * Called when the gesture is recognized.
         *
         * @param gestureId   The gesture id of {@link GestureMatcher}.
         * @param lastDownEventTime The time when receiving last {@link MotionEvent#ACTION_DOWN}.
         * @param delayedEventQueue The collected event queue in whole detection duration.
         * @param event The last event to determine the gesture. For the holding gestures, it's
         *                  the last event before timeout.
         *
         * @see MagnificationGestureMatcher#GESTURE_SWIPE
         * @see MagnificationGestureMatcher#GESTURE_TWO_FINGERS_DOWN_OR_SWIPE
         */
        void onGestureCompleted(@GestureId int gestureId, long lastDownEventTime,
                List<MotionEventInfo> delayedEventQueue, MotionEvent event);

        /**
         * Called with the following conditions:
         * <ol>
         *   <li> {@link #shouldStopDetection(MotionEvent)} returns {@code true}.
         *   <li> The system has decided an event stream doesn't match any known gesture.
         * <ol>
         *
         * @param lastDownEventTime The time when receiving last {@link MotionEvent#ACTION_DOWN}.
         * @param delayedEventQueue The collected event queue in whole detection duration.
         * @param lastEvent The last event received before all matchers cancelling detection.
         */
        void onGestureCancelled(long lastDownEventTime,
                List<MotionEventInfo> delayedEventQueue, MotionEvent lastEvent);
    }

    @Nullable private List<MotionEventInfo> mDelayedEventQueue;
    private MotionEvent mLastEvent;
    private long mLastDownEventTime = 0;
    private final Callback mCallback;

    private final GesturesObserver mGesturesObserver;

    MagnificationGesturesObserver(@NonNull Callback callback, GestureMatcher... matchers) {
        mGesturesObserver = new GesturesObserver(this, matchers);
        mCallback = callback;
    }

    /**
     * Processes a motion event and attempts to match it to one of the gestures.
     *
     * @param event the event as passed in from the event stream.
     * @param rawEvent the original un-modified event. Useful for calculating movements in physical
     *     space.
     * @param policyFlags the policy flags as passed in from the event stream.
     * @return {@code true} if one of the gesture is matched.
     */
    @MainThread
    boolean onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (DBG) {
            Slog.d(LOG_TAG, "DetectGesture: event = " + event);
        }
        cacheDelayedMotionEvent(event, rawEvent, policyFlags);
        if (mCallback.shouldStopDetection(event)) {
            notifyDetectionCancel();
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mLastDownEventTime = event.getDownTime();
        }
        return mGesturesObserver.onMotionEvent(event, rawEvent, policyFlags);
    }

    @Override
    public void onGestureCompleted(int gestureId, MotionEvent event, MotionEvent rawEvent,
            int policyFlags) {
        if (DBG) {
            Slog.d(LOG_TAG, "onGestureCompleted: " + MagnificationGestureMatcher.gestureIdToString(
                    gestureId) + " event = " + event);
        }
        final List<MotionEventInfo> delayEventQueue = mDelayedEventQueue;
        mDelayedEventQueue = null;
        mCallback.onGestureCompleted(gestureId, mLastDownEventTime, delayEventQueue,
                event);
        clear();
    }

    @Override
    public void onGestureCancelled(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (DBG) {
            Slog.d(LOG_TAG, "onGestureCancelled:  event = " + event);
        }
        notifyDetectionCancel();
    }

    private void notifyDetectionCancel() {
        final List<MotionEventInfo> delayEventQueue = mDelayedEventQueue;
        mDelayedEventQueue = null;
        mCallback.onGestureCancelled(mLastDownEventTime, delayEventQueue,
                mLastEvent);
        clear();
    }

    /**
     * Resets all state to default.
     */
    private void clear() {
        if (DBG) {
            Slog.d(LOG_TAG, "clear:" + mDelayedEventQueue);
        }
        recycleLastEvent();
        mLastDownEventTime = 0;
        if (mDelayedEventQueue != null) {
            for (MotionEventInfo eventInfo2: mDelayedEventQueue) {
                eventInfo2.recycle();
            }
            mDelayedEventQueue.clear();
            mDelayedEventQueue = null;
        }
    }

    private void recycleLastEvent() {
        if (mLastEvent == null) {
            return;
        }
        mLastEvent.recycle();
        mLastEvent = null;
    }

    private void cacheDelayedMotionEvent(MotionEvent event, MotionEvent rawEvent,
            int policyFlags) {
        mLastEvent = MotionEvent.obtain(event);
        MotionEventInfo info =
                MotionEventInfo.obtain(event, rawEvent,
                        policyFlags);
        if (mDelayedEventQueue == null) {
            mDelayedEventQueue = new LinkedList<>();
        }
        mDelayedEventQueue.add(info);
    }

    @Override
    public String toString() {
        return "MagnificationGesturesObserver{"
                + "mDelayedEventQueue=" + mDelayedEventQueue + '}';
    }
}
