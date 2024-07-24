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

import static com.android.server.accessibility.gestures.TouchExplorer.DEBUG;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Handler;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * This class describes a common base for gesture matchers. A gesture matcher checks a series of
 * motion events against a single gesture. Coordinating the individual gesture matchers is done by
 * the GestureManifold. To create a new Gesture, extend this class and override the onDown, onMove,
 * onUp, etc methods as necessary. If you don't override a method your matcher will do nothing in
 * response to that type of event. Finally, be sure to give your gesture a name by overriding
 * getGestureName().
 * @hide
 */
public abstract class GestureMatcher {
    // Potential states for this individual gesture matcher.
    // In STATE_CLEAR, this matcher is accepting new motion events but has not formally signaled
    // that there is enough data to judge that a gesture has started.
    public static final int STATE_CLEAR = 0;
    // In STATE_GESTURE_STARTED, this matcher continues to accept motion events and it has signaled
    // to the gesture manifold that what looks like the specified gesture has started.
    public static final int STATE_GESTURE_STARTED = 1;
    // In STATE_GESTURE_COMPLETED, this matcher has successfully matched the specified gesture. and
    // will not accept motion events until it is cleared.
    public static final int STATE_GESTURE_COMPLETED = 2;
    // In STATE_GESTURE_CANCELED, this matcher will not accept new motion events because it is
    // impossible that this set of motion events will match the specified gesture.
    public static final int STATE_GESTURE_CANCELED = 3;

    @IntDef({STATE_CLEAR, STATE_GESTURE_STARTED, STATE_GESTURE_COMPLETED, STATE_GESTURE_CANCELED})
    public @interface State {}

    @State private int mState = STATE_CLEAR;
    // The id number of the gesture that gets passed to accessibility services.
    private final int mGestureId;
    // handler for asynchronous operations like timeouts
    private final Handler mHandler;

    private StateChangeListener mListener;

    // Use this to transition to new states after a delay.
    // e.g. cancel or complete after some timeout.
    // Convenience functions for tapTimeout and doubleTapTimeout are already defined here.
    protected final DelayedTransition mDelayedTransition;

    protected GestureMatcher(int gestureId, Handler handler, StateChangeListener listener) {
        mGestureId = gestureId;
        mHandler = handler;
        mDelayedTransition = new DelayedTransition();
        mListener = listener;
    }

    /**
     * Resets all state information for this matcher. Subclasses that include their own state
     * information should override this method to reset their own state information and call
     * super.clear().
     */
    public void clear() {
        mState = STATE_CLEAR;
        cancelPendingTransitions();
    }

    public final int getState() {
        return mState;
    }

    /**
     * Transitions to a new state and notifies any listeners. Note that any pending transitions are
     * canceled.
     */
    private void setState(
            @State int state, MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        mState = state;
        cancelPendingTransitions();
        if (mListener != null) {
            mListener.onStateChanged(mGestureId, mState, event, rawEvent, policyFlags);
        }
    }

    /** Indicates that there is evidence to suggest that this gesture has started. */
    protected final void startGesture(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        setState(STATE_GESTURE_STARTED, event, rawEvent, policyFlags);
    }

    /** Indicates this stream of motion events can no longer match this gesture. */
    protected final void cancelGesture(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        setState(STATE_GESTURE_CANCELED, event, rawEvent, policyFlags);
    }

    /** Indicates this gesture is completed. */
    protected final void completeGesture(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        setState(STATE_GESTURE_COMPLETED, event, rawEvent, policyFlags);
    }

    public final void setListener(@NonNull StateChangeListener listener) {
        mListener = listener;
    }

    public int getGestureId() {
        return mGestureId;
    }

    /**
     * Process a motion event and attempt to match it to this gesture.
     *
     * @param event the event as passed in from the event stream.
     * @param rawEvent the original un-modified event. Useful for calculating movements in physical
     *     space.
     * @param policyFlags the policy flags as passed in from the event stream.
     * @return the state of this matcher.
     */
    public final int onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (mState == STATE_GESTURE_CANCELED || mState == STATE_GESTURE_COMPLETED) {
            return mState;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onDown(event, rawEvent, policyFlags);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                onPointerDown(event, rawEvent, policyFlags);
                break;
            case MotionEvent.ACTION_MOVE:
                onMove(event, rawEvent, policyFlags);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                onPointerUp(event, rawEvent, policyFlags);
                break;
            case MotionEvent.ACTION_UP:
                onUp(event, rawEvent, policyFlags);
                break;
            default:
                // Cancel because of invalid event.
                setState(STATE_GESTURE_CANCELED, event, rawEvent, policyFlags);
                break;
        }
        return mState;
    }

    /**
     * Matchers override this method to respond to ACTION_DOWN events. ACTION_DOWN events indicate
     * the first finger has touched the screen. If not overridden the default response is to do
     * nothing.
     */
    protected void onDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {}

    /**
     * Matchers override this method to respond to ACTION_POINTER_DOWN events. ACTION_POINTER_DOWN
     * indicates that more than one finger has touched the screen. If not overridden the default
     * response is to do nothing.
     *
     * @param event the event as passed in from the event stream.
     * @param rawEvent the original un-modified event. Useful for calculating movements in physical
     *     space.
     * @param policyFlags the policy flags as passed in from the event stream.
     */
    protected void onPointerDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {}

    /**
     * Matchers override this method to respond to ACTION_MOVE events. ACTION_MOVE indicates that
     * one or fingers has moved. If not overridden the default response is to do nothing.
     *
     * @param event the event as passed in from the event stream.
     * @param rawEvent the original un-modified event. Useful for calculating movements in physical
     *     space.
     * @param policyFlags the policy flags as passed in from the event stream.
     */
    protected void onMove(MotionEvent event, MotionEvent rawEvent, int policyFlags) {}

    /**
     * Matchers override this method to respond to ACTION_POINTER_UP events. ACTION_POINTER_UP
     * indicates that a finger has lifted from the screen but at least one finger continues to touch
     * the screen. If not overridden the default response is to do nothing.
     *
     * @param event the event as passed in from the event stream.
     * @param rawEvent the original un-modified event. Useful for calculating movements in physical
     *     space.
     * @param policyFlags the policy flags as passed in from the event stream.
     */
    protected void onPointerUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {}

    /**
     * Matchers override this method to respond to ACTION_UP events. ACTION_UP indicates that there
     * are no more fingers touching the screen. If not overridden the default response is to do
     * nothing.
     *
     * @param event the event as passed in from the event stream.
     * @param rawEvent the original un-modified event. Useful for calculating movements in physical
     *     space.
     * @param policyFlags the policy flags as passed in from the event stream.
     */
    protected void onUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {}

    /** Cancels this matcher after the tap timeout. Any pending state transitions are removed. */
    protected void cancelAfterTapTimeout(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelAfter(ViewConfiguration.getTapTimeout(), event, rawEvent, policyFlags);
    }

    /** Cancels this matcher after the double tap timeout. Any pending cancelations are removed. */
    protected final void cancelAfterDoubleTapTimeout(
            MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelAfter(ViewConfiguration.getDoubleTapTimeout(), event, rawEvent, policyFlags);
    }

    /**
     * Cancels this matcher after the specified timeout. Any pending cancelations are removed. Used
     * to prevent this matcher from accepting motion events until it is cleared.
     */
    protected final void cancelAfter(
            long timeout, MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        mDelayedTransition.cancel();
        mDelayedTransition.post(STATE_GESTURE_CANCELED, timeout, event, rawEvent, policyFlags);
    }

    /** Cancels any delayed transitions between states scheduled for this matcher. */
    protected final void cancelPendingTransitions() {
        mDelayedTransition.cancel();
    }

    /**
     * Signals that this gesture has been completed after the tap timeout has expired. Used to
     * ensure that there is no conflict with another gesture or for gestures that explicitly require
     * a hold.
     */
    protected final void completeAfterLongPressTimeout(
            MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        completeAfter(ViewConfiguration.getLongPressTimeout(), event, rawEvent, policyFlags);
    }

    /**
     * Signals that this gesture has been completed after the tap timeout has expired. Used to
     * ensure that there is no conflict with another gesture or for gestures that explicitly require
     * a hold.
     */
    protected final void completeAfterTapTimeout(
            MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        completeAfter(ViewConfiguration.getTapTimeout(), event, rawEvent, policyFlags);
    }

    /**
     * Signals that this gesture has been completed after the specified timeout has expired. Used to
     * ensure that there is no conflict with another gesture or for gestures that explicitly require
     * a hold.
     */
    protected final void completeAfter(
            long timeout, MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        mDelayedTransition.cancel();
        mDelayedTransition.post(STATE_GESTURE_COMPLETED, timeout, event, rawEvent, policyFlags);
    }

    /**
     * Signals that this gesture has been completed after the double-tap timeout has expired. Used
     * to ensure that there is no conflict with another gesture or for gestures that explicitly
     * require a hold.
     */
    protected final void completeAfterDoubleTapTimeout(
            MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        completeAfter(ViewConfiguration.getDoubleTapTimeout(), event, rawEvent, policyFlags);
    }

    static String getStateSymbolicName(@State int state) {
        switch (state) {
            case STATE_CLEAR:
                return "STATE_CLEAR";
            case STATE_GESTURE_STARTED:
                return "STATE_GESTURE_STARTED";
            case STATE_GESTURE_COMPLETED:
                return "STATE_GESTURE_COMPLETED";
            case STATE_GESTURE_CANCELED:
                return "STATE_GESTURE_CANCELED";
            default:
                return "Unknown state: " + state;
        }
    }

    /**
     * Returns a readable name for this matcher that can be displayed to the user and in system
     * logs.
     */
    protected abstract String getGestureName();

    /**
     * Returns a String representation of this matcher. Each matcher can override this method to add
     * extra state information to the string representation.
     */
    public String toString() {
        return getGestureName() + ":" + getStateSymbolicName(mState);
    }

    /** This class allows matchers to transition between states on a delay. */
    protected final class DelayedTransition implements Runnable {

        private static final String LOG_TAG = "GestureMatcher.DelayedTransition";
        int mTargetState;
        MotionEvent mEvent;
        MotionEvent mRawEvent;
        int mPolicyFlags;

        public void cancel() {
            // Avoid meaningless debug messages.
            if (DEBUG && isPending()) {
                Slog.d(
                        LOG_TAG,
                        getGestureName()
                                + ": canceling delayed transition to "
                                + getStateSymbolicName(mTargetState));
            }
            mHandler.removeCallbacks(this);
            recycleEvent();
        }

        public void post(
                int state, long delay, MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            // Recycle the old event first if necessary, to handle duplicate calls to post.
            recycleEvent();
            mTargetState = state;
            if (android.view.accessibility.Flags.copyEventsForGestureDetection()) {
                mEvent = event.copy();
                mRawEvent = rawEvent.copy();
            } else {
                mEvent = event;
                mRawEvent = rawEvent;
            }
            mPolicyFlags = policyFlags;
            mHandler.postDelayed(this, delay);
            if (DEBUG) {
                Slog.d(
                        LOG_TAG,
                        getGestureName()
                                + ": posting delayed transition to "
                                + getStateSymbolicName(mTargetState));
            }
        }

        public boolean isPending() {
            return mHandler.hasCallbacks(this);
        }

        public void forceSendAndRemove() {
            if (isPending()) {
                run();
                cancel();
            }
        }

        @Override
        public void run() {
            if (DEBUG) {
                Slog.d(
                        LOG_TAG,
                        getGestureName()
                                + ": executing delayed transition to "
                                + getStateSymbolicName(mTargetState));
            }
            setState(mTargetState, mEvent, mRawEvent, mPolicyFlags);
            recycleEvent();
        }

        private void recycleEvent() {
            if (android.view.accessibility.Flags.copyEventsForGestureDetection()) {
                if (mEvent == null || mRawEvent == null) {
                    return;
                }
                mEvent.recycle();
                mRawEvent.recycle();
                mEvent = null;
                mRawEvent = null;
            }
        }
    }

    /** Interface to allow a class to listen for state changes in a specific gesture matcher */
    public interface StateChangeListener {

        void onStateChanged(
                int gestureId, int state, MotionEvent event, MotionEvent rawEvent, int policyFlags);
    }
}
