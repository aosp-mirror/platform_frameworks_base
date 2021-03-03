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
import android.graphics.Point;
import android.provider.Settings;
import android.util.MathUtils;
import android.util.Slog;
import android.view.Display;
import android.view.MotionEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.accessibility.EventStreamTransformation;
import com.android.server.accessibility.gestures.MultiTap;
import com.android.server.accessibility.gestures.MultiTapAndHold;

import java.util.List;

/**
 * This class handles window magnification in response to touch events and shortcut.
 *
 * The behavior is as follows:
 *
 * <ol>
 *   <li> 1. Toggle Window magnification by triple-tap gesture shortcut. It is triggered via
 *   {@link #onTripleTap(MotionEvent)}.
 *   <li> 2. Toggle Window magnification by tapping shortcut. It is triggered via
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

    private static final boolean DEBUG_STATE_TRANSITIONS = false | DEBUG_ALL;
    private static final boolean DEBUG_DETECTING = false | DEBUG_ALL;

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
    private final Context mContext;
    private final Point mTempPoint = new Point();

    public WindowMagnificationGestureHandler(Context context,
            WindowMagnificationManager windowMagnificationMgr,
            Callback callback,
            boolean detectTripleTap, boolean detectShortcutTrigger, int displayId) {
        super(displayId, detectTripleTap, detectShortcutTrigger, callback);
        if (DEBUG_ALL) {
            Slog.i(mLogTag,
                    "WindowMagnificationGestureHandler() , displayId = " + displayId + ")");
        }
        mContext = context;
        mWindowMagnificationMgr = windowMagnificationMgr;
        mMotionEventDispatcherDelegate = new MotionEventDispatcherDelegate(context,
                (event, rawEvent, policyFlags) -> dispatchTransformedEvent(event, rawEvent,
                        policyFlags));
        mDelegatingState = new DelegatingState(mMotionEventDispatcherDelegate);
        mDetectingState = new DetectingState(context, mDetectTripleTap);
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
                            }

                            @Override
                            public float getScale(int displayId) {
                                return mWindowMagnificationMgr.getScale(displayId);
                            }
                        }));

        transitionTo(mDetectingState);
    }

    @Override
    void onMotionEventInternal(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // To keep InputEventConsistencyVerifiers within GestureDetectors happy.
        mObservePanningScalingState.mPanningScalingHandler.onTouchEvent(event);
        mCurrentState.onMotionEvent(event, rawEvent, policyFlags);
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
            Slog.i(mLogTag, "onDestroy(); delayed = "
                    + mDetectingState.toString());
        }
        mWindowMagnificationMgr.disableWindowMagnification(mDisplayId, true);
        resetToDetectState();
    }

    @Override
    public void handleShortcutTriggered() {
        final Point screenSize = mTempPoint;
        getScreenSize(mTempPoint);
        toggleMagnification(screenSize.x / 2.0f, screenSize.y / 2.0f);
    }

    private  void getScreenSize(Point outSize) {
        final Display display = mContext.getDisplay();
        display.getRealSize(outSize);
    }

    @Override
    public int getMode() {
        return Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
    }

    private void enableWindowMagnifier(float centerX, float centerY) {
        if (DEBUG_ALL) {
            Slog.i(mLogTag, "enableWindowMagnifier :" + centerX + ", " + centerY);
        }

        final float scale = MathUtils.constrain(
                mWindowMagnificationMgr.getPersistedScale(),
                MIN_SCALE, MAX_SCALE);
        mWindowMagnificationMgr.enableWindowMagnification(mDisplayId, scale, centerX, centerY);
    }

    private void disableWindowMagnifier() {
        if (DEBUG_ALL) {
            Slog.i(mLogTag, "disableWindowMagnifier()");
        }
        mWindowMagnificationMgr.disableWindowMagnification(mDisplayId, false);
    }

    private void toggleMagnification(float centerX, float centerY) {
        if (mWindowMagnificationMgr.isWindowMagnifierEnabled(mDisplayId)) {
            disableWindowMagnifier();
        } else {
            enableWindowMagnifier(centerX, centerY);
        }
    }

    private void onTripleTap(MotionEvent up) {
        if (DEBUG_DETECTING) {
            Slog.i(mLogTag, "onTripleTap()");
        }
        toggleMagnification(up.getX(), up.getY());
        mCallback.onTripleTapped(mDisplayId, getMode());
    }

    void resetToDetectState() {
        transitionTo(mDetectingState);
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
            Slog.i(mLogTag, "state transition: " + (State.nameOf(mCurrentState) + " -> "
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
     * manipulate the window magnifier or want to interact with current UI. The rule of leaving
     * this state is as follows:
     * <ol>
     *   <li> If {@link MagnificationGestureMatcher#GESTURE_TWO_FINGERS_DOWN_OR_SWIPE} is detected,
     *   {@link State} will be transited to {@link PanningScalingGestureState}.</li>
     *   <li> If other gesture is detected and the last motion event is neither ACTION_UP nor
     *   ACTION_CANCEL.
     * </ol>
     *  <b>Note</b> The motion events will be cached and dispatched before leaving this state.
     */
    final class DetectingState implements State,
            MagnificationGesturesObserver.Callback {

        private final MagnificationGesturesObserver mGesturesObserver;

        /**
         * {@code true} if this detector should detect and respond to triple-tap
         * gestures for engaging and disengaging magnification,
         * {@code false} if it should ignore such gestures
         */
        private final boolean mDetectTripleTap;

        DetectingState(Context context, boolean detectTripleTap) {
            mDetectTripleTap = detectTripleTap;
            final MultiTap multiTap = new MultiTap(context, mDetectTripleTap ? 3 : 1,
                    mDetectTripleTap
                            ? MagnificationGestureMatcher.GESTURE_TRIPLE_TAP
                            : MagnificationGestureMatcher.GESTURE_SINGLE_TAP, null);
            final MultiTapAndHold multiTapAndHold = new MultiTapAndHold(context,
                    mDetectTripleTap ? 3 : 1,
                    mDetectTripleTap
                            ? MagnificationGestureMatcher.GESTURE_TRIPLE_TAP_AND_HOLD
                            : MagnificationGestureMatcher.GESTURE_SINGLE_TAP_AND_HOLD, null);
            mGesturesObserver = new MagnificationGesturesObserver(this,
                    new SimpleSwipe(context),
                    multiTap,
                    multiTapAndHold,
                    new TwoFingersDownOrSwipe(context));
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
            return !mWindowMagnificationMgr.isWindowMagnifierEnabled(mDisplayId)
                    && !mDetectTripleTap;
        }

        @Override
        public void onGestureCompleted(int gestureId, long lastDownEventTime,
                List<MotionEventInfo> delayedEventQueue,
                MotionEvent motionEvent) {
            if (DEBUG_DETECTING) {
                Slog.d(mLogTag, "onGestureDetected : gesture = "
                        + MagnificationGestureMatcher.gestureIdToString(
                        gestureId));
                Slog.d(mLogTag,
                        "onGestureDetected : delayedEventQueue = " + delayedEventQueue);
            }
            if (gestureId == MagnificationGestureMatcher.GESTURE_TWO_FINGERS_DOWN_OR_SWIPE
                    && mWindowMagnificationMgr.pointersInWindow(mDisplayId, motionEvent) > 0) {
                transitionTo(mObservePanningScalingState);
            } else if (gestureId == MagnificationGestureMatcher.GESTURE_TRIPLE_TAP) {
                onTripleTap(motionEvent);
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
                Slog.d(mLogTag,
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
