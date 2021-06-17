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

import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SINGLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_TRIPLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SINGLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_TRIPLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SINGLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_TRIPLE_TAP;
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
    // Whether double tap and double tap and hold will be dispatched to the service or handled in
    // the framework.
    private boolean mServiceHandlesDoubleTap = false;
    // Whether multi-finger gestures are enabled.
    boolean mMultiFingerGesturesEnabled;
    // Whether the two-finger passthrough is enabled when multi-finger gestures are enabled.
    private boolean mTwoFingerPassthroughEnabled;
    // Whether to send the motion events during gesture dispatch.
    private boolean mSendMotionEventsEnabled = false;
    // A list of all the multi-finger gestures, for easy adding and removal.
    private final List<GestureMatcher> mMultiFingerGestures = new ArrayList<>();
    // A list of two-finger swipes, for easy adding and removal when turning on or off two-finger
    // passthrough.
    private final List<GestureMatcher> mTwoFingerSwipes = new ArrayList<>();
    // The list of motion events for the current gesture.
    private List<MotionEvent> mEvents = new ArrayList<>();
    // Shared state information.
    private TouchState mState;

    GestureManifold(Context context, Listener listener, TouchState state, Handler handler) {
        mContext = context;
        mHandler = handler;
        mListener = listener;
        mState = state;
        mMultiFingerGesturesEnabled = false;
        mTwoFingerPassthroughEnabled = false;
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
        // Set up multi-finger gestures to be enabled later.
        // Two-finger taps.
        mMultiFingerGestures.add(
                new MultiFingerMultiTap(mContext, 2, 1, GESTURE_2_FINGER_SINGLE_TAP, this));
        mMultiFingerGestures.add(
                new MultiFingerMultiTap(mContext, 2, 2, GESTURE_2_FINGER_DOUBLE_TAP, this));
        mMultiFingerGestures.add(
                new MultiFingerMultiTapAndHold(
                        mContext, 2, 2, GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD, this));
        mMultiFingerGestures.add(
                new MultiFingerMultiTap(mContext, 2, 3, GESTURE_2_FINGER_TRIPLE_TAP, this));
        mMultiFingerGestures.add(
                new MultiFingerMultiTapAndHold(
                        mContext, 2, 3, GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD, this));
        // Three-finger taps.
        mMultiFingerGestures.add(
                new MultiFingerMultiTap(mContext, 3, 1, GESTURE_3_FINGER_SINGLE_TAP, this));
        mMultiFingerGestures.add(
                new MultiFingerMultiTap(mContext, 3, 2, GESTURE_3_FINGER_DOUBLE_TAP, this));
        mMultiFingerGestures.add(
                new MultiFingerMultiTapAndHold(
                        mContext, 3, 1, GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD, this));
        mMultiFingerGestures.add(
                new MultiFingerMultiTapAndHold(
                        mContext, 3, 2, GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD, this));
        mMultiFingerGestures.add(
                new MultiFingerMultiTap(mContext, 3, 3, GESTURE_3_FINGER_TRIPLE_TAP, this));
        mMultiFingerGestures.add(
                new MultiFingerMultiTapAndHold(
                        mContext, 3, 3, GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD, this));
        mMultiFingerGestures.add(
                new MultiFingerMultiTap(mContext, 3, 3, GESTURE_3_FINGER_TRIPLE_TAP, this));
        // Four-finger taps.
        mMultiFingerGestures.add(
                new MultiFingerMultiTap(mContext, 4, 1, GESTURE_4_FINGER_SINGLE_TAP, this));
        mMultiFingerGestures.add(
                new MultiFingerMultiTap(mContext, 4, 2, GESTURE_4_FINGER_DOUBLE_TAP, this));
        mMultiFingerGestures.add(
                new MultiFingerMultiTapAndHold(
                        mContext, 4, 2, GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD, this));
        mMultiFingerGestures.add(
                new MultiFingerMultiTap(mContext, 4, 3, GESTURE_4_FINGER_TRIPLE_TAP, this));
        // Two-finger swipes.
        mTwoFingerSwipes.add(
                new MultiFingerSwipe(context, 2, DOWN, GESTURE_2_FINGER_SWIPE_DOWN, this));
        mTwoFingerSwipes.add(
                new MultiFingerSwipe(context, 2, LEFT, GESTURE_2_FINGER_SWIPE_LEFT, this));
        mTwoFingerSwipes.add(
                new MultiFingerSwipe(context, 2, RIGHT, GESTURE_2_FINGER_SWIPE_RIGHT, this));
        mTwoFingerSwipes.add(new MultiFingerSwipe(context, 2, UP, GESTURE_2_FINGER_SWIPE_UP, this));
        mMultiFingerGestures.addAll(mTwoFingerSwipes);
        // Three-finger swipes.
        mMultiFingerGestures.add(
                new MultiFingerSwipe(context, 3, DOWN, GESTURE_3_FINGER_SWIPE_DOWN, this));
        mMultiFingerGestures.add(
                new MultiFingerSwipe(context, 3, LEFT, GESTURE_3_FINGER_SWIPE_LEFT, this));
        mMultiFingerGestures.add(
                new MultiFingerSwipe(context, 3, RIGHT, GESTURE_3_FINGER_SWIPE_RIGHT, this));
        mMultiFingerGestures.add(
                new MultiFingerSwipe(context, 3, UP, GESTURE_3_FINGER_SWIPE_UP, this));
        // Four-finger swipes.
        mMultiFingerGestures.add(
                new MultiFingerSwipe(context, 4, DOWN, GESTURE_4_FINGER_SWIPE_DOWN, this));
        mMultiFingerGestures.add(
                new MultiFingerSwipe(context, 4, LEFT, GESTURE_4_FINGER_SWIPE_LEFT, this));
        mMultiFingerGestures.add(
                new MultiFingerSwipe(context, 4, RIGHT, GESTURE_4_FINGER_SWIPE_RIGHT, this));
        mMultiFingerGestures.add(
                new MultiFingerSwipe(context, 4, UP, GESTURE_4_FINGER_SWIPE_UP, this));
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
                // Validity safeguard: if touch state is clear, then matchers should always be clear
                // before processing the next down event.
                clear();
            } else {
                // If for some reason other events come through while in the clear state they could
                // compromise the state of particular matchers, so we just ignore them.
                return false;
            }
        }
        if (mSendMotionEventsEnabled) {
            mEvents.add(MotionEvent.obtainNoHistory(rawEvent));
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
                    // Here we just return. The actual gesture dispatch is done in
                    // onStateChanged().
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
        if (mEvents != null) {
            while (mEvents.size() > 0) {
                mEvents.remove(0).recycle();
            }
        }
    }

    /**
     * Listener that receives notifications of the state of the gesture detector. Listener functions
     * are called as a result of onMotionEvent(). The current MotionEvent in the context of these
     * functions is the event passed into onMotionEvent.
     */
    public interface Listener {
        /**
         * When FLAG_SERVICE_HANDLES_DOUBLE_TAP is enabled, this method is not called; double-tap
         * and hold is dispatched via onGestureCompleted. Otherwise, this method is called when the
         * user has performed a double tap and then held down the second tap.
         */
        void onDoubleTapAndHold(MotionEvent event, MotionEvent rawEvent, int policyFlags);

        /**
         * When FLAG_SERVICE_HANDLES_DOUBLE_TAP is enabled, this method is not called; double-tap is
         * dispatched via onGestureCompleted. Otherwise, this method is called when the user lifts
         * their finger on the second tap of a double tap.
         *
         * @return true if the event is consumed, else false
         */
        boolean onDoubleTap(MotionEvent event, MotionEvent rawEvent, int policyFlags);

        /**
         * Called when the system has decided the event stream is a potential gesture.
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
            if (gestureId == GESTURE_DOUBLE_TAP || gestureId == GESTURE_DOUBLE_TAP_AND_HOLD) {
                if (mServiceHandlesDoubleTap) {
                    mListener.onGestureStarted();
                }
            } else {
                mListener.onGestureStarted();
            }
        } else if (state == GestureMatcher.STATE_GESTURE_COMPLETED) {
            onGestureCompleted(gestureId, event, rawEvent, policyFlags);
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

    private void onGestureCompleted(
            int gestureId, MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // Note that gestures that complete immediately call clear() from onMotionEvent.
        // Gestures that complete on a delay call clear() here.
        switch (gestureId) {
            case GESTURE_DOUBLE_TAP:
                if (mServiceHandlesDoubleTap) {
                    AccessibilityGestureEvent gestureEvent =
                            new AccessibilityGestureEvent(gestureId, event.getDisplayId(), mEvents);
                    mListener.onGestureCompleted(gestureEvent);
                } else {
                    mListener.onDoubleTap(event, rawEvent, policyFlags);
                }
                break;
            case GESTURE_DOUBLE_TAP_AND_HOLD:
                if (mServiceHandlesDoubleTap) {
                    AccessibilityGestureEvent gestureEvent =
                            new AccessibilityGestureEvent(gestureId, event.getDisplayId(), mEvents);
                    mListener.onGestureCompleted(gestureEvent);
                } else {
                    mListener.onDoubleTapAndHold(event, rawEvent, policyFlags);
                }
                break;
            default:
                AccessibilityGestureEvent gestureEvent =
                        new AccessibilityGestureEvent(gestureId, event.getDisplayId(), mEvents);
                mListener.onGestureCompleted(gestureEvent);
                break;
        }
        clear();
    }

    public boolean isMultiFingerGesturesEnabled() {
        return mMultiFingerGesturesEnabled;
    }

    public void setMultiFingerGesturesEnabled(boolean mode) {
        if (mMultiFingerGesturesEnabled != mode) {
            mMultiFingerGesturesEnabled = mode;
            if (mode) {
                mGestures.addAll(mMultiFingerGestures);
            } else {
                mGestures.removeAll(mMultiFingerGestures);
            }
        }
    }

    public boolean isTwoFingerPassthroughEnabled() {
        return mTwoFingerPassthroughEnabled;
    }

    public void setTwoFingerPassthroughEnabled(boolean mode) {
        if (mTwoFingerPassthroughEnabled != mode) {
            mTwoFingerPassthroughEnabled = mode;
            if (!mode) {
                mMultiFingerGestures.addAll(mTwoFingerSwipes);
                if (mMultiFingerGesturesEnabled) {
                    mGestures.addAll(mTwoFingerSwipes);
                }
            } else {
                mMultiFingerGestures.removeAll(mTwoFingerSwipes);
                mGestures.removeAll(mTwoFingerSwipes);
            }
        }
    }

    public void setServiceHandlesDoubleTap(boolean mode) {
        mServiceHandlesDoubleTap = mode;
    }

    public boolean isServiceHandlesDoubleTapEnabled() {
        return mServiceHandlesDoubleTap;
    }

    public void setSendMotionEventsEnabled(boolean mode) {
        mSendMotionEventsEnabled = mode;
        if (!mode) {
            while (mEvents.size() > 0) {
                mEvents.remove(0).recycle();
            }
        }
    }

    public boolean isSendMotionEventsEnabled() {
        return mSendMotionEventsEnabled;
    }

    /**
     * Returns the current list of motion events. It is the caller's responsibility to copy the list
     * if they want it to persist after a call to clear().
     */
    public List<MotionEvent> getMotionEvents() {
        return mEvents;
    }
}
