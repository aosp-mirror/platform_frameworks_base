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


import android.annotation.MainThread;
import android.view.MotionEvent;

import com.android.server.accessibility.gestures.GestureMatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * A class to coordinates a series of individual {@link GestureMatcher} injecting in the constructor
 * to triggers the appropriate callbacks on the {@link Listener} supplied. The event stream should
 * start with {@link MotionEvent#ACTION_DOWN}, otherwise
 * {@link Listener#onGestureCancelled(MotionEvent, MotionEvent, int)} will be triggered.
 *
 * @hide
 */
public final class GesturesObserver implements GestureMatcher.StateChangeListener {

    /**
     * Listeners to receive the result of gestures matching.
     */
    public interface Listener {
        /**
         * Called when an event stream is recognized as a gesture.
         *
         * @param gestureId   the gesture id of {@link GestureMatcher}.
         * @param event       The last event to determine the gesture. For the holding gestures,
         *                    it's the last event before timeout.
         * @param rawEvent    The event without any transformations.
         * @param policyFlags The policy flags of the most recent event.
         */
        void onGestureCompleted(int gestureId, MotionEvent event, MotionEvent rawEvent,
                int policyFlags);

        /**
         * Called when the system has decided an event stream doesn't match any known gesture or
         * the first event is not {@link MotionEvent#ACTION_DOWN}.
         *
         * @param event       The last event to determine the cancellation before timeout.
         * @param rawEvent    The event without any transformations.
         * @param policyFlags The policy flags of the most recent event.
         */
        void onGestureCancelled(MotionEvent event, MotionEvent rawEvent, int policyFlags);
    }

    private final List<GestureMatcher> mGestureMatchers = new ArrayList<>();
    private final Listener mListener;

    private boolean mObserveStarted = false;
    private boolean mProcessMotionEvent = false;
    private int mCancelledMatcherSize = 0;
    public GesturesObserver(Listener listener,  GestureMatcher... matchers) {
        mListener = listener;
        for (int i = 0; i < matchers.length; i++) {
            matchers[i].setListener(this);
            mGestureMatchers.add(matchers[i]);
        }
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
    public boolean onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (!mObserveStarted) {
            if (event.getActionMasked() != MotionEvent.ACTION_DOWN) {
                mListener.onGestureCancelled(event, rawEvent, policyFlags);
                clear();
                return false;
            } else {
                mObserveStarted = true;
            }
        }
        mProcessMotionEvent = true;
        for (int i = 0; i < mGestureMatchers.size(); i++) {
            final GestureMatcher matcher = mGestureMatchers.get(i);
            matcher.onMotionEvent(event, rawEvent, policyFlags);
            if (matcher.getState() == GestureMatcher.STATE_GESTURE_COMPLETED) {
                clear();
                mProcessMotionEvent = false;
                return true;
            }
        }
        mProcessMotionEvent = false;
        return false;
    }

    /**
     * Clears all states to default.
     */
    @MainThread
    public void clear() {
        for (GestureMatcher matcher : mGestureMatchers) {
            matcher.clear();
        }
        mCancelledMatcherSize = 0;
        mObserveStarted = false;
    }

    @Override
    public void onStateChanged(int gestureId, int state, MotionEvent event,
            MotionEvent rawEvent, int policyFlags) {
        if (state == GestureMatcher.STATE_GESTURE_COMPLETED) {
            mListener.onGestureCompleted(gestureId, event, rawEvent, policyFlags);
            // Ideally we clear the states in onMotionEvent(), this case is for hold gestures.
            // If we clear before processing up event , then MultiTap matcher cancels the gesture
            // due to incorrect state. It ends up listener#onGestureCancelled is called even
            // the gesture is detected.
            if (!mProcessMotionEvent) {
                clear();
            }
        } else if (state == GestureMatcher.STATE_GESTURE_CANCELED) {
            mCancelledMatcherSize++;
            if (mCancelledMatcherSize == mGestureMatchers.size()) {
                mListener.onGestureCancelled(event, rawEvent, policyFlags);
                clear();
            }
        }
    }
}
