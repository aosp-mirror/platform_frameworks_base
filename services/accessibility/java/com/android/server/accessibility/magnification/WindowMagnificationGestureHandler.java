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

import static android.view.InputDevice.SOURCE_TOUCHSCREEN;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_UP;

import static java.util.Arrays.asList;
import static java.util.Arrays.copyOfRange;

import android.annotation.Nullable;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.view.MotionEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.accessibility.EventStreamTransformation;
import com.android.server.accessibility.gestures.MultiTap;
import com.android.server.accessibility.gestures.MultiTapAndHold;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * This class handles window magnification in response to touch events and shortcut.
 *
 * The behavior is as follows:

 * <ol>
 *   <li> Window magnification can be "toggled" by tapping shortcut. It is triggered via
 *   {@link #notifyShortcutTriggered()}.
 *   <li> When the window magnifier is visible, pinching with any number of additional fingers
 *   would adjust the magnification scale .<strong>Note</strong> that this operation is valid only
 *   when at least one finger is in the window.
 *   <li> When the window magnifier is visible, to do scrolling to move the window magnifier,
 *   the user can use two or more fingers and at least one of them is inside the window.
 *   <br><strong>Note</strong> that the offset of this callback is opposed to moving direction.
 *  The operation becomes invalid after performing scaling operation until all fingers are
 *  lifted.
 * </ol>
 */
@SuppressWarnings("WeakerAccess")
public class WindowMagnificationGestureHandler extends MagnificationGestureHandler {
    private static final String LOG_TAG = "WindowMagnificationGestureHandler";

    private static final boolean DEBUG_ALL = Log.isLoggable(LOG_TAG, Log.DEBUG);
    private static final boolean DEBUG_STATE_TRANSITIONS = false | DEBUG_ALL;
    private static final boolean DEBUG_DETECTING = false | DEBUG_ALL;
    private static final boolean DEBUG_EVENT_STREAM = false | DEBUG_ALL;

    //Ensure the range has consistency with FullScreenMagnificationGestureHandler.
    private static final float MIN_SCALE = 2.0f;
    private static final float MAX_SCALE = WindowMagnificationManager.MAX_SCALE;

    private final WindowMagnificationManager mWindowMagnificationMgr;

    @VisibleForTesting
    final DelegatingState mDelegatingState;
    @VisibleForTesting
    final DetectingState mDetectingState;
    @VisibleForTesting
    final PanningScalingGestureState mObservePanningScalingState;

    @VisibleForTesting
    State mCurrentState;
    @VisibleForTesting
    State mPreviousState;

    private MotionEventDispatcherDelegate mMotionEventDispatcherDelegate;
    private final int mDisplayId;

    private final Queue<MotionEvent> mDebugOutputEventHistory;

    /**
     * @param context                Context for resolving various magnification-related resources
     * @param windowMagnificationMgr The {@link WindowMagnificationManager}
     * @param displayId              The logical display id.
     */
    public WindowMagnificationGestureHandler(Context context,
            WindowMagnificationManager windowMagnificationMgr,
            MagnificationGestureHandler.ScaleChangedListener listener, int displayId) {
        super(listener);
        if (DEBUG_ALL) {
            Slog.i(LOG_TAG,
                    "WindowMagnificationGestureHandler() , displayId = " + displayId + ")");
        }

        mWindowMagnificationMgr = windowMagnificationMgr;
        mDisplayId = displayId;
        mMotionEventDispatcherDelegate = new MotionEventDispatcherDelegate(context,
                (event, rawEvent, policyFlags) -> super.onMotionEvent(
                        event, rawEvent, policyFlags));
        mDelegatingState = new DelegatingState(mMotionEventDispatcherDelegate);
        mDetectingState = new DetectingState(context);
        mObservePanningScalingState = new PanningScalingGestureState(
                new PanningScalingHandler(context, MAX_SCALE, MIN_SCALE, true,
                        new PanningScalingHandler.MagnificationDelegate() {
                            @Override
                            public boolean processScroll(int displayId, float distanceX,
                                    float distanceY) {
                                return mWindowMagnificationMgr.processScroll(displayId, distanceX,
                                        distanceY);
                            }

                            @Override
                            public void setScale(int displayId, float scale) {
                                mWindowMagnificationMgr.setScale(displayId, scale);
                                mListener.onMagnificationScaleChanged(displayId, getMode());
                            }

                            @Override
                            public float getScale(int displayId) {
                                return mWindowMagnificationMgr.getScale(displayId);
                            }
                        }));

        mDebugOutputEventHistory = DEBUG_EVENT_STREAM ? new ArrayDeque<>() : null;

        transitionTo(mDetectingState);
    }

    @Override
    public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (DEBUG_ALL) {
            Slog.i(LOG_TAG, "onMotionEvent(" + event + ")");
        }
        if (!event.isFromSource(SOURCE_TOUCHSCREEN)) {
            dispatchTransformedEvent(event, rawEvent, policyFlags);
            return;
        } else {
            // To keep InputEventConsistencyVerifiers within GestureDetectors happy.
            mObservePanningScalingState.mPanningScalingHandler.onTouchEvent(event);
            mCurrentState.onMotionEvent(event, rawEvent, policyFlags);
        }
    }

    @Override
    public void clearEvents(int inputSource) {
        if (inputSource == SOURCE_TOUCHSCREEN) {
            resetToDetectState();
        }
        super.clearEvents(inputSource);
    }

    @Override
    public void onDestroy() {
        if (DEBUG_ALL) {
            Slog.i(LOG_TAG, "onDestroy(); delayed = "
                    + mDetectingState.toString());
        }
        mWindowMagnificationMgr.disableWindowMagnifier(mDisplayId, true);
        resetToDetectState();
    }

    @Override
    public void notifyShortcutTriggered() {
        if (DEBUG_ALL) {
            Slog.i(LOG_TAG, "notifyShortcutTriggered():");
        }
        if (mWindowMagnificationMgr.isWindowMagnifierEnabled(mDisplayId)) {
            disableWindowMagnifier();
        } else {
            enableWindowMagnifier(Float.NaN, Float.NaN);
        }
    }

    @Override
    public int getMode() {
        return Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
    }

    private void enableWindowMagnifier(float centerX, float centerY) {
        if (DEBUG_ALL) {
            Slog.i(LOG_TAG, "enableWindowMagnifier :" + centerX + ", " + centerY);
        }

        final float scale = MathUtils.constrain(
                mWindowMagnificationMgr.getPersistedScale(),
                MIN_SCALE, MAX_SCALE);
        mWindowMagnificationMgr.enableWindowMagnifier(mDisplayId, scale, centerX, centerY);
    }

    private void disableWindowMagnifier() {
        if (DEBUG_ALL) {
            Slog.i(LOG_TAG, "disableWindowMagnifier()");
        }
        mWindowMagnificationMgr.disableWindowMagnifier(mDisplayId, false);
    }

    void resetToDetectState() {
        transitionTo(mDetectingState);
    }

    private void dispatchTransformedEvent(MotionEvent event, MotionEvent rawEvent,
            int policyFlags) {
        if (DEBUG_EVENT_STREAM) {
            storeEventInto(mDebugOutputEventHistory, event);
            try {
                super.onMotionEvent(event, rawEvent, policyFlags);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Exception downstream following input events: " + mDebugOutputEventHistory,
                        e);
            }
        } else {
            super.onMotionEvent(event, rawEvent, policyFlags);
        }
    }

    private static void storeEventInto(Queue<MotionEvent> queue, MotionEvent event) {
        queue.add(MotionEvent.obtain(event));
        // Prune old events.
        while (!queue.isEmpty() && (event.getEventTime() - queue.peek().getEventTime() > 5000)) {
            queue.remove().recycle();
        }
    }

    /**
     * An interface to intercept the {@link MotionEvent} for gesture detection. The intercepted
     * events should be delivered to next {@link EventStreamTransformation} with {
     * {@link EventStreamTransformation#onMotionEvent(MotionEvent, MotionEvent, int)}} if there is
     * no valid gestures.
     */
    interface State {
        void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags);

        default void clear() {
        }

        default void onEnter() {
        }

        default void onExit() {
        }

        default String name() {
            return getClass().getSimpleName();
        }

        static String nameOf(@Nullable State s) {
            return s != null ? s.name() : "null";
        }
    }

    private void transitionTo(State state) {
        if (DEBUG_STATE_TRANSITIONS) {
            Slog.i(LOG_TAG, "state transition: " + (State.nameOf(mCurrentState) + " -> "
                    + State.nameOf(state) + " at "
                    + asList(copyOfRange(new RuntimeException().getStackTrace(), 1, 5)))
                    .replace(getClass().getName(), ""));
        }
        mPreviousState = mCurrentState;
        if (mPreviousState != null) {
            mPreviousState.onExit();
        }
        mCurrentState = state;
        if (mCurrentState != null) {
            mCurrentState.onEnter();
        }
    }

    /**
     * When entering this state, {@link PanningScalingHandler} will be enabled to address the
     * gestures until receiving {@link MotionEvent#ACTION_UP} or {@link MotionEvent#ACTION_CANCEL}.
     * When leaving this state, current scale will be persisted.
     */
    final class PanningScalingGestureState implements State {
        private final PanningScalingHandler mPanningScalingHandler;

        PanningScalingGestureState(PanningScalingHandler panningScalingHandler) {
            mPanningScalingHandler = panningScalingHandler;
        }

        @Override
        public void onEnter() {
            mPanningScalingHandler.setEnabled(true);
        }

        @Override
        public void onExit() {
            mPanningScalingHandler.setEnabled(false);
            mWindowMagnificationMgr.persistScale(mDisplayId);
            clear();
        }

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            int action = event.getActionMasked();
            if (action == ACTION_UP || action == ACTION_CANCEL) {
                transitionTo(mDetectingState);
            }
        }

        @Override
        public void clear() {
            mPanningScalingHandler.clear();
        }

        @Override
        public String toString() {
            return "PanningScalingState{"
                    + "mPanningScalingHandler =" + mPanningScalingHandler + '}';
        }
    }

    /**
     * A state not to intercept {@link MotionEvent}. Leaving this state until receiving
     * {@link MotionEvent#ACTION_UP} or {@link MotionEvent#ACTION_CANCEL}.
     */
    final class DelegatingState implements State {
        private final MotionEventDispatcherDelegate mMotionEventDispatcherDelegate;

        DelegatingState(MotionEventDispatcherDelegate motionEventDispatcherDelegate) {
            mMotionEventDispatcherDelegate = motionEventDispatcherDelegate;
        }

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            mMotionEventDispatcherDelegate.dispatchMotionEvent(event, rawEvent, policyFlags);
            switch (event.getActionMasked()) {
                case ACTION_UP:
                case ACTION_CANCEL: {
                    transitionTo(mDetectingState);
                }
                    break;
            }
        }
    }

    /**
     * This class handles motion events in a duration to determine if the user is going to
     *  manipulate the window magnifier or want to interact with current UI. The rule of leaving
     *  this state is as follows:
     * <ol>
     *   <li> If {@link MagnificationGestureMatcher#GESTURE_TWO_FINGER_DOWN} is detected,
     *   {@link State} will be transited to {@link PanningScalingGestureState}.</li>
     *   <li> If other gesture is detected and the last motion event is neither ACTION_UP nor
     *   ACTION_CANCEL.
     * </ol>
     *  <b>Note</b> The motion events will be cached and dispatched before leaving this state.
     */
    final class DetectingState implements State,
            MagnificationGesturesObserver.Callback {

        private final MagnificationGesturesObserver mGesturesObserver;

        DetectingState(Context context) {
            mGesturesObserver = new MagnificationGesturesObserver(this, new SimpleSwipe(context),
                    new MultiTap(context, 1, MagnificationGestureMatcher.GESTURE_SINGLE_TAP, null),
                    new MultiTapAndHold(context, 1,
                            MagnificationGestureMatcher.GESTURE_SINGLE_TAP_AND_HOLD, null),
                    new TwoFingersDown(context));
        }

        @Override
        public void onExit() {
            clear();
        }

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            mGesturesObserver.onMotionEvent(event, rawEvent, policyFlags);
        }

        @Override
        public void clear() {
            mGesturesObserver.clear();
        }

        @Override
        public String toString() {
            return "DetectingState{"
                    + ", mGestureTimeoutObserver =" + mGesturesObserver
                    + '}';
        }

        @Override
        public boolean shouldStopDetection(MotionEvent motionEvent) {
            return !mWindowMagnificationMgr.isWindowMagnifierEnabled(mDisplayId);
        }

        @Override
        public void onGestureCompleted(int gestureId, long lastDownEventTime,
                List<MotionEventInfo> delayedEventQueue,
                MotionEvent motionEvent) {
            if (DEBUG_DETECTING) {
                Slog.d(LOG_TAG, "onGestureDetected : gesture = "
                        + MagnificationGestureMatcher.gestureIdToString(
                        gestureId));
                Slog.d(LOG_TAG,
                        "onGestureDetected : delayedEventQueue = " + delayedEventQueue);
            }
            if (gestureId == MagnificationGestureMatcher.GESTURE_TWO_FINGER_DOWN
                    && mWindowMagnificationMgr.pointersInWindow(mDisplayId, motionEvent) > 0) {
                transitionTo(mObservePanningScalingState);
            } else {
                mMotionEventDispatcherDelegate.sendDelayedMotionEvents(delayedEventQueue,
                        lastDownEventTime);
                changeToDelegateStateIfNeed(motionEvent);
            }
        }

        @Override
        public void onGestureCancelled(long lastDownEventTime,
                List<MotionEventInfo> delayedEventQueue,
                MotionEvent motionEvent) {
            if (DEBUG_DETECTING) {
                Slog.d(LOG_TAG,
                        "onGestureCancelled : delayedEventQueue = " + delayedEventQueue);
            }
            mMotionEventDispatcherDelegate.sendDelayedMotionEvents(delayedEventQueue,
                    lastDownEventTime);
            changeToDelegateStateIfNeed(motionEvent);
        }

        private void changeToDelegateStateIfNeed(MotionEvent motionEvent) {
            if (motionEvent != null && (motionEvent.getActionMasked() == ACTION_UP
                    || motionEvent.getActionMasked() == ACTION_CANCEL)) {
                return;
            }
            transitionTo(mDelegatingState);
        }
    }

    @Override
    public String toString() {
        return "WindowMagnificationGestureHandler{"
                + "mDetectingState=" + mDetectingState
                + ", mDelegatingState=" + mDelegatingState
                + ", mMagnifiedInteractionState=" + mObservePanningScalingState
                + ", mCurrentState=" + State.nameOf(mCurrentState)
                + ", mPreviousState=" + State.nameOf(mPreviousState)
                + ", mWindowMagnificationMgr=" + mWindowMagnificationMgr
                + ", mDisplayId=" + mDisplayId
                + '}';
    }
}
