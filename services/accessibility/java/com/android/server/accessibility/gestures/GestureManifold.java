/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.accessibility.gestures;

import static android.accessibilityservice.AccessibilityService.GESTURE_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_DOUBLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_RIGHT;

import static com.android.server.accessibility.gestures.Swipe.DOWN;
import static com.android.server.accessibility.gestures.Swipe.LEFT;
import static com.android.server.accessibility.gestures.Swipe.RIGHT;
import static com.android.server.accessibility.gestures.Swipe.UP;
import static com.android.server.accessibility.gestures.TouchExplorer.DEBUG;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.content.Context;
import android.os.Handler;
import android.util.Slog;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * This class coordinates a series of individual gesture matchers to serve as a unified gesture
 * detector. Gesture matchers are tied to a single gesture. It calls listener callback functions
 * when a gesture starts or completes.
 */
class GestureManifold implements GestureMatcher.StateChangeListener {

    private static final String LOG_TAG = "GestureManifold";

    private final List<GestureMatcher> mGestures = new ArrayList<>();
    private final Context mContext;
    // Handler for performing asynchronous operations.
    private final Handler mHandler;
    // Listener to be notified of gesture start and end.
    private Listener mListener;
    // Shared state information.
    private TouchState mState;

    GestureManifold(Context context, Listener listener, TouchState state) {
        mContext = context;
        mHandler = new Handler(context.getMainLooper());
        mListener = listener;
        mState = state;
        // Set up gestures.
        // Start with double tap.
        mGestures.add(new MultiTap(context, 2, GESTURE_DOUBLE_TAP, this));
        mGestures.add(new MultiTapAndHold(context, 2, GESTURE_DOUBLE_TAP_AND_HOLD, this));
        // Second-finger double tap.
        mGestures.add(new SecondFingerMultiTap(context, 2, GESTURE_DOUBLE_TAP, this));
        // One-direction swipes.
        mGestures.add(new Swipe(context, RIGHT, GESTURE_SWIPE_RIGHT, this));
        mGestures.add(new Swipe(context, LEFT, GESTURE_SWIPE_LEFT, this));
        mGestures.add(new Swipe(context, UP, GESTURE_SWIPE_UP, this));
        mGestures.add(new Swipe(context, DOWN, GESTURE_SWIPE_DOWN, this));
        // Two-direction swipes.
        mGestures.add(new Swipe(context, LEFT, RIGHT, GESTURE_SWIPE_LEFT_AND_RIGHT, this));
        mGestures.add(new Swipe(context, LEFT, UP, GESTURE_SWIPE_LEFT_AND_UP, this));
        mGestures.add(new Swipe(context, LEFT, DOWN, GESTURE_SWIPE_LEFT_AND_DOWN, this));
        mGestures.add(new Swipe(context, RIGHT, UP, GESTURE_SWIPE_RIGHT_AND_UP, this));
        mGestures.add(new Swipe(context, RIGHT, DOWN, GESTURE_SWIPE_RIGHT_AND_DOWN, this));
        mGestures.add(new Swipe(context, RIGHT, LEFT, GESTURE_SWIPE_RIGHT_AND_LEFT, this));
        mGestures.add(new Swipe(context, DOWN, UP, GESTURE_SWIPE_DOWN_AND_UP, this));
        mGestures.add(new Swipe(context, DOWN, LEFT, GESTURE_SWIPE_DOWN_AND_LEFT, this));
        mGestures.add(new Swipe(context, DOWN, RIGHT, GESTURE_SWIPE_DOWN_AND_RIGHT, this));
        mGestures.add(new Swipe(context, UP, DOWN, GESTURE_SWIPE_UP_AND_DOWN, this));
        mGestures.add(new Swipe(context, UP, LEFT, GESTURE_SWIPE_UP_AND_LEFT, this));
        mGestures.add(new Swipe(context, UP, RIGHT, GESTURE_SWIPE_UP_AND_RIGHT, this));
    }

    /**
     * Processes a motion event.
     *
     * @param event The event as received from the previous entry in the event stream.
     * @param rawEvent The event without any transformations e.g. magnification.
     * @param policyFlags
     * @return True if the event has been appropriately handled by the gesture manifold and related
     *     callback functions, false if it should be handled further by the calling function.
     */
    boolean onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (mState.isClear()) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // Sanity safeguard: if touch state is clear, then matchers should always be clear
                // before processing the next down event.
                clear();
            } else {
                // If for some reason other events come through while in the clear state they could
                // compromise the state of particular matchers, so we just ignore them.
                return false;
            }
        }
        for (GestureMatcher matcher : mGestures) {
            if (matcher.getState() != GestureMatcher.STATE_GESTURE_CANCELED) {
                if (DEBUG) {
                    Slog.d(LOG_TAG, matcher.toString());
                }
                matcher.onMotionEvent(event, rawEvent, policyFlags);
                if (DEBUG) {
                    Slog.d(LOG_TAG, matcher.toString());
                }
                if (matcher.getState() == GestureMatcher.STATE_GESTURE_COMPLETED) {
                    // Here we just clear and return. The actual gesture dispatch is done in
                    // onStateChanged().
                    clear();
                    // No need to process this event any further.
                    return true;
                }
            }
        }
        return false;
    }

    public void clear() {
        for (GestureMatcher matcher : mGestures) {
            matcher.clear();
        }
    }

    /**
     * Listener that receives notifications of the state of the gesture detector. Listener functions
     * are called as a result of onMotionEvent(). The current MotionEvent in the context of these
     * functions is the event passed into onMotionEvent.
     */
    public interface Listener {
        /**
         * Called when the user has performed a double tap and then held down the second tap.
         */
        void onDoubleTapAndHold();

        /**
         * Called when the user lifts their finger on the second tap of a double tap.
         * @return true if the event is consumed, else false
         */
        boolean onDoubleTap();

        /**
         * Called when the system has decided the event stream is a gesture.
         *
         * @return true if the event is consumed, else false
         */
        boolean onGestureStarted();

        /**
         * Called when an event stream is recognized as a gesture.
         *
         * @param gestureEvent Information about the gesture.
         * @return true if the event is consumed, else false
         */
        boolean onGestureCompleted(AccessibilityGestureEvent gestureEvent);

        /**
         * Called when the system has decided an event stream doesn't match any known gesture.
         *
         * @param event The most recent MotionEvent received.
         * @param policyFlags The policy flags of the most recent event.
         * @return true if the event is consumed, else false
         */
        boolean onGestureCancelled(MotionEvent event, MotionEvent rawEvent, int policyFlags);
    }

    @Override
    public void onStateChanged(
            int gestureId, int state, MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (state == GestureMatcher.STATE_GESTURE_STARTED && !mState.isGestureDetecting()) {
            mListener.onGestureStarted();
        } else if (state == GestureMatcher.STATE_GESTURE_COMPLETED) {
            onGestureCompleted(gestureId);
        } else if (state == GestureMatcher.STATE_GESTURE_CANCELED && mState.isGestureDetecting()) {
            // We only want to call the cancelation callback if there are no other pending
            // detectors.
            for (GestureMatcher matcher : mGestures) {
                if (matcher.getState() == GestureMatcher.STATE_GESTURE_STARTED) {
                    return;
                }
            }
            if (DEBUG) {
                Slog.d(LOG_TAG, "Cancelling.");
            }
            mListener.onGestureCancelled(event, rawEvent, policyFlags);
        }
    }

    private void onGestureCompleted(int gestureId) {
        MotionEvent event = mState.getLastReceivedEvent();
        // Note that gestures that complete immediately call clear() from onMotionEvent.
        // Gestures that complete on a delay call clear() here.
        switch (gestureId) {
            case GESTURE_DOUBLE_TAP:
                mListener.onDoubleTap();
                clear();
                break;
            case GESTURE_DOUBLE_TAP_AND_HOLD:
                mListener.onDoubleTapAndHold();
                clear();
                break;
            default:
                AccessibilityGestureEvent gestureEvent =
                        new AccessibilityGestureEvent(gestureId, event.getDisplayId());
                mListener.onGestureCompleted(gestureEvent);
                break;
        }
    }
}
