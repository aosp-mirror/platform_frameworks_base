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

import static android.view.InputDevice.SOURCE_TOUCHSCREEN;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_UP;
import static android.view.MotionEvent.ACTION_UP;

import static com.android.server.accessibility.GestureUtils.distance;

import static java.lang.Math.abs;
import static java.util.Arrays.asList;
import static java.util.Arrays.copyOfRange;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;
import android.view.ViewConfiguration;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * This class handles magnification in response to touch events.
 *
 * The behavior is as follows:
 *
 * 1. Triple tap toggles permanent screen magnification which is magnifying
 *    the area around the location of the triple tap. One can think of the
 *    location of the triple tap as the center of the magnified viewport.
 *    For example, a triple tap when not magnified would magnify the screen
 *    and leave it in a magnified state. A triple tapping when magnified would
 *    clear magnification and leave the screen in a not magnified state.
 *
 * 2. Triple tap and hold would magnify the screen if not magnified and enable
 *    viewport dragging mode until the finger goes up. One can think of this
 *    mode as a way to move the magnified viewport since the area around the
 *    moving finger will be magnified to fit the screen. For example, if the
 *    screen was not magnified and the user triple taps and holds the screen
 *    would magnify and the viewport will follow the user's finger. When the
 *    finger goes up the screen will zoom out. If the same user interaction
 *    is performed when the screen is magnified, the viewport movement will
 *    be the same but when the finger goes up the screen will stay magnified.
 *    In other words, the initial magnified state is sticky.
 *
 * 3. Magnification can optionally be "triggered" by some external shortcut
 *    affordance. When this occurs via {@link #notifyShortcutTriggered()} a
 *    subsequent tap in a magnifiable region will engage permanent screen
 *    magnification as described in #1. Alternatively, a subsequent long-press
 *    or drag will engage magnification with viewport dragging as described in
 *    #2. Once magnified, all following behaviors apply whether magnification
 *    was engaged via a triple-tap or by a triggered shortcut.
 *
 * 4. Pinching with any number of additional fingers when viewport dragging
 *    is enabled, i.e. the user triple tapped and holds, would adjust the
 *    magnification scale which will become the current default magnification
 *    scale. The next time the user magnifies the same magnification scale
 *    would be used.
 *
 * 5. When in a permanent magnified state the user can use two or more fingers
 *    to pan the viewport. Note that in this mode the content is panned as
 *    opposed to the viewport dragging mode in which the viewport is moved.
 *
 * 6. When in a permanent magnified state the user can use two or more
 *    fingers to change the magnification scale which will become the current
 *    default magnification scale. The next time the user magnifies the same
 *    magnification scale would be used.
 *
 * 7. The magnification scale will be persisted in settings and in the cloud.
 */
@SuppressWarnings("WeakerAccess")
class MagnificationGestureHandler extends BaseEventStreamTransformation {
    private static final String LOG_TAG = "MagnificationGestureHandler";

    private static final boolean DEBUG_ALL = false;
    private static final boolean DEBUG_STATE_TRANSITIONS = false || DEBUG_ALL;
    private static final boolean DEBUG_DETECTING = false || DEBUG_ALL;
    private static final boolean DEBUG_PANNING_SCALING = false || DEBUG_ALL;
    private static final boolean DEBUG_EVENT_STREAM = false || DEBUG_ALL;

    // The MIN_SCALE is different from MagnificationController.MIN_SCALE due
    // to AccessibilityService.MagnificationController#setScale() has
    // different scale range
    private static final float MIN_SCALE = 2.0f;
    private static final float MAX_SCALE = MagnificationController.MAX_SCALE;

    @VisibleForTesting final MagnificationController mMagnificationController;

    @VisibleForTesting final DelegatingState mDelegatingState;
    @VisibleForTesting final DetectingState mDetectingState;
    @VisibleForTesting final PanningScalingState mPanningScalingState;
    @VisibleForTesting final ViewportDraggingState mViewportDraggingState;

    private final ScreenStateReceiver mScreenStateReceiver;

    /**
     * {@code true} if this detector should detect and respond to triple-tap
     * gestures for engaging and disengaging magnification,
     * {@code false} if it should ignore such gestures
     */
    final boolean mDetectTripleTap;

    /**
     * Whether {@link DetectingState#mShortcutTriggered shortcut} is enabled
     */
    final boolean mDetectShortcutTrigger;

    @VisibleForTesting State mCurrentState;
    @VisibleForTesting State mPreviousState;

    private PointerCoords[] mTempPointerCoords;
    private PointerProperties[] mTempPointerProperties;

    private final Queue<MotionEvent> mDebugInputEventHistory;
    private final Queue<MotionEvent> mDebugOutputEventHistory;

    /**
     * @param context Context for resolving various magnification-related resources
     * @param magnificationController the {@link MagnificationController}
     *
     * @param detectTripleTap {@code true} if this detector should detect and respond to triple-tap
     *                                gestures for engaging and disengaging magnification,
     *                                {@code false} if it should ignore such gestures
     * @param detectShortcutTrigger {@code true} if this detector should be "triggerable" by some
     *                           external shortcut invoking {@link #notifyShortcutTriggered},
     *                           {@code false} if it should ignore such triggers.
     */
    public MagnificationGestureHandler(Context context,
            MagnificationController magnificationController,
            boolean detectTripleTap,
            boolean detectShortcutTrigger) {
        if (DEBUG_ALL) {
            Log.i(LOG_TAG,
                    "MagnificationGestureHandler(detectTripleTap = " + detectTripleTap
                            + ", detectShortcutTrigger = " + detectShortcutTrigger + ")");
        }

        mMagnificationController = magnificationController;

        mDelegatingState = new DelegatingState();
        mDetectingState = new DetectingState(context);
        mViewportDraggingState = new ViewportDraggingState();
        mPanningScalingState = new PanningScalingState(context);

        mDetectTripleTap = detectTripleTap;
        mDetectShortcutTrigger = detectShortcutTrigger;

        if (mDetectShortcutTrigger) {
            mScreenStateReceiver = new ScreenStateReceiver(context, this);
            mScreenStateReceiver.register();
        } else {
            mScreenStateReceiver = null;
        }

        mDebugInputEventHistory = DEBUG_EVENT_STREAM ? new ArrayDeque<>() : null;
        mDebugOutputEventHistory = DEBUG_EVENT_STREAM ? new ArrayDeque<>() : null;

        transitionTo(mDetectingState);
    }

    @Override
    public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (DEBUG_EVENT_STREAM) {
            storeEventInto(mDebugInputEventHistory, event);
            try {
                onMotionEventInternal(event, rawEvent, policyFlags);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Exception following input events: " + mDebugInputEventHistory, e);
            }
        } else {
            onMotionEventInternal(event, rawEvent, policyFlags);
        }
    }

    private void onMotionEventInternal(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (DEBUG_ALL) Slog.i(LOG_TAG, "onMotionEvent(" + event + ")");

        if ((!mDetectTripleTap && !mDetectShortcutTrigger)
                || !event.isFromSource(SOURCE_TOUCHSCREEN)) {
            dispatchTransformedEvent(event, rawEvent, policyFlags);
            return;
        }

        handleEventWith(mCurrentState, event, rawEvent, policyFlags);
    }

    private void handleEventWith(State stateHandler,
            MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // To keep InputEventConsistencyVerifiers within GestureDetectors happy
        mPanningScalingState.mScrollGestureDetector.onTouchEvent(event);
        mPanningScalingState.mScaleGestureDetector.onTouchEvent(event);

        stateHandler.onMotionEvent(event, rawEvent, policyFlags);
    }

    @Override
    public void clearEvents(int inputSource) {
        if (inputSource == SOURCE_TOUCHSCREEN) {
            clearAndTransitionToStateDetecting();
        }

        super.clearEvents(inputSource);
    }

    @Override
    public void onDestroy() {
        if (DEBUG_STATE_TRANSITIONS) {
            Slog.i(LOG_TAG, "onDestroy(); delayed = "
                    + MotionEventInfo.toString(mDetectingState.mDelayedEventQueue));
        }

        if (mScreenStateReceiver != null) {
            mScreenStateReceiver.unregister();
        }
        // Check if need to reset when MagnificationGestureHandler is the last magnifying service.
        mMagnificationController.resetIfNeeded(
                AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
        clearAndTransitionToStateDetecting();
    }

    void notifyShortcutTriggered() {
        if (mDetectShortcutTrigger) {
            boolean wasMagnifying = mMagnificationController.resetIfNeeded(/* animate */ true);
            if (wasMagnifying) {
                clearAndTransitionToStateDetecting();
            } else {
                mDetectingState.toggleShortcutTriggered();
            }
        }
    }

    void clearAndTransitionToStateDetecting() {
        mCurrentState = mDetectingState;
        mDetectingState.clear();
        mViewportDraggingState.clear();
        mPanningScalingState.clear();
    }

    private void dispatchTransformedEvent(MotionEvent event, MotionEvent rawEvent,
            int policyFlags) {
        if (DEBUG_EVENT_STREAM) {
            storeEventInto(mDebugOutputEventHistory, event);
            try {
                super.onMotionEvent(event, rawEvent, policyFlags);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Exception downstream following input events: " + mDebugInputEventHistory
                                + "\nTransformed into output events: " + mDebugOutputEventHistory,
                        e);
            }
        } else {
            super.onMotionEvent(event, rawEvent, policyFlags);
        }
    }

    private static void storeEventInto(Queue<MotionEvent> queue, MotionEvent event) {
        queue.add(MotionEvent.obtain(event));
        // Prune old events
        while (!queue.isEmpty() && (event.getEventTime() - queue.peek().getEventTime() > 5000)) {
            queue.remove().recycle();
        }
    }

    private PointerCoords[] getTempPointerCoordsWithMinSize(int size) {
        final int oldSize = (mTempPointerCoords != null) ? mTempPointerCoords.length : 0;
        if (oldSize < size) {
            PointerCoords[] oldTempPointerCoords = mTempPointerCoords;
            mTempPointerCoords = new PointerCoords[size];
            if (oldTempPointerCoords != null) {
                System.arraycopy(oldTempPointerCoords, 0, mTempPointerCoords, 0, oldSize);
            }
        }
        for (int i = oldSize; i < size; i++) {
            mTempPointerCoords[i] = new PointerCoords();
        }
        return mTempPointerCoords;
    }

    private PointerProperties[] getTempPointerPropertiesWithMinSize(int size) {
        final int oldSize = (mTempPointerProperties != null) ? mTempPointerProperties.length
                : 0;
        if (oldSize < size) {
            PointerProperties[] oldTempPointerProperties = mTempPointerProperties;
            mTempPointerProperties = new PointerProperties[size];
            if (oldTempPointerProperties != null) {
                System.arraycopy(oldTempPointerProperties, 0, mTempPointerProperties, 0,
                        oldSize);
            }
        }
        for (int i = oldSize; i < size; i++) {
            mTempPointerProperties[i] = new PointerProperties();
        }
        return mTempPointerProperties;
    }

    private void transitionTo(State state) {
        if (DEBUG_STATE_TRANSITIONS) {
            Slog.i(LOG_TAG,
                    (State.nameOf(mCurrentState) + " -> " + State.nameOf(state)
                    + " at " + asList(copyOfRange(new RuntimeException().getStackTrace(), 1, 5)))
                    .replace(getClass().getName(), ""));
        }
        mPreviousState = mCurrentState;
        mCurrentState = state;
    }

    interface State {
        void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags);

        default void clear() {}

        default String name() {
            return getClass().getSimpleName();
        }

        static String nameOf(@Nullable State s) {
            return s != null ? s.name() : "null";
        }
    }

    /**
     * This class determines if the user is performing a scale or pan gesture.
     *
     * Unlike when {@link ViewportDraggingState dragging the viewport}, in panning mode the viewport
     * moves in the same direction as the fingers, and allows to easily and precisely scale the
     * magnification level.
     * This makes it the preferred mode for one-off adjustments, due to its precision and ease of
     * triggering.
     */
    final class PanningScalingState extends SimpleOnGestureListener
            implements OnScaleGestureListener, State {

        private final ScaleGestureDetector mScaleGestureDetector;
        private final GestureDetector mScrollGestureDetector;
        final float mScalingThreshold;

        float mInitialScaleFactor = -1;
        boolean mScaling;

        public PanningScalingState(Context context) {
            final TypedValue scaleValue = new TypedValue();
            context.getResources().getValue(
                    com.android.internal.R.dimen.config_screen_magnification_scaling_threshold,
                    scaleValue, false);
            mScalingThreshold = scaleValue.getFloat();
            mScaleGestureDetector = new ScaleGestureDetector(context, this, Handler.getMain());
            mScaleGestureDetector.setQuickScaleEnabled(false);
            mScrollGestureDetector = new GestureDetector(context, this, Handler.getMain());
        }

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            int action = event.getActionMasked();

            if (action == ACTION_POINTER_UP
                    && event.getPointerCount() == 2 // includes the pointer currently being released
                    && mPreviousState == mViewportDraggingState) {

                persistScaleAndTransitionTo(mViewportDraggingState);

            } else if (action == ACTION_UP || action == ACTION_CANCEL) {

                persistScaleAndTransitionTo(mDetectingState);

            }
        }

        public void persistScaleAndTransitionTo(State state) {
            mMagnificationController.persistScale();
            clear();
            transitionTo(state);
        }

        @Override
        public boolean onScroll(MotionEvent first, MotionEvent second,
                float distanceX, float distanceY) {
            if (mCurrentState != mPanningScalingState) {
                return true;
            }
            if (DEBUG_PANNING_SCALING) {
                Slog.i(LOG_TAG, "Panned content by scrollX: " + distanceX
                        + " scrollY: " + distanceY);
            }
            mMagnificationController.offsetMagnifiedRegion(distanceX, distanceY,
                    AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
            return /* event consumed: */ true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (!mScaling) {
                if (mInitialScaleFactor < 0) {
                    mInitialScaleFactor = detector.getScaleFactor();
                    return false;
                }
                final float deltaScale = detector.getScaleFactor() - mInitialScaleFactor;
                mScaling = abs(deltaScale) > mScalingThreshold;
                return mScaling;
            }

            final float initialScale = mMagnificationController.getScale();
            final float targetScale = initialScale * detector.getScaleFactor();

            // Don't allow a gesture to move the user further outside the
            // desired bounds for gesture-controlled scaling.
            final float scale;
            if (targetScale > MAX_SCALE && targetScale > initialScale) {
                // The target scale is too big and getting bigger.
                scale = MAX_SCALE;
            } else if (targetScale < MIN_SCALE && targetScale < initialScale) {
                // The target scale is too small and getting smaller.
                scale = MIN_SCALE;
            } else {
                // The target scale may be outside our bounds, but at least
                // it's moving in the right direction. This avoids a "jump" if
                // we're at odds with some other service's desired bounds.
                scale = targetScale;
            }

            final float pivotX = detector.getFocusX();
            final float pivotY = detector.getFocusY();
            if (DEBUG_PANNING_SCALING) Slog.i(LOG_TAG, "Scaled content to: " + scale + "x");
            mMagnificationController.setScale(scale, pivotX, pivotY, false,
                    AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
            return /* handled: */ true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return /* continue recognizing: */ (mCurrentState == mPanningScalingState);
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            clear();
        }

        @Override
        public void clear() {
            mInitialScaleFactor = -1;
            mScaling = false;
        }

        @Override
        public String toString() {
            return "PanningScalingState{" +
                    "mInitialScaleFactor=" + mInitialScaleFactor +
                    ", mScaling=" + mScaling +
                    '}';
        }
    }

    /**
     * This class handles motion events when the event dispatcher has
     * determined that the user is performing a single-finger drag of the
     * magnification viewport.
     *
     * Unlike when {@link PanningScalingState panning}, the viewport moves in the opposite direction
     * of the finger, and any part of the screen is reachable without lifting the finger.
     * This makes it the preferable mode for tasks like reading text spanning full screen width.
     */
    final class ViewportDraggingState implements State {

        /** Whether to disable zoom after dragging ends */
        boolean mZoomedInBeforeDrag;
        private boolean mLastMoveOutsideMagnifiedRegion;

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            final int action = event.getActionMasked();
            switch (action) {
                case ACTION_POINTER_DOWN: {
                    clear();
                    transitionTo(mPanningScalingState);
                }
                break;
                case ACTION_MOVE: {
                    if (event.getPointerCount() != 1) {
                        throw new IllegalStateException("Should have one pointer down.");
                    }
                    final float eventX = event.getX();
                    final float eventY = event.getY();
                    if (mMagnificationController.magnificationRegionContains(eventX, eventY)) {
                        mMagnificationController.setCenter(eventX, eventY,
                                /* animate */ mLastMoveOutsideMagnifiedRegion,
                                AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
                        mLastMoveOutsideMagnifiedRegion = false;
                    } else {
                        mLastMoveOutsideMagnifiedRegion = true;
                    }
                }
                break;

                case ACTION_UP:
                case ACTION_CANCEL: {
                    if (!mZoomedInBeforeDrag) zoomOff();
                    clear();
                    transitionTo(mDetectingState);
                }
                break;

                case ACTION_DOWN:
                case ACTION_POINTER_UP: {
                    throw new IllegalArgumentException(
                            "Unexpected event type: " + MotionEvent.actionToString(action));
                }
            }
        }

        @Override
        public void clear() {
            mLastMoveOutsideMagnifiedRegion = false;
        }

        @Override
        public String toString() {
            return "ViewportDraggingState{" +
                    "mZoomedInBeforeDrag=" + mZoomedInBeforeDrag +
                    ", mLastMoveOutsideMagnifiedRegion=" + mLastMoveOutsideMagnifiedRegion +
                    '}';
        }
    }

    final class DelegatingState implements State {
        /**
         * Time of last {@link MotionEvent#ACTION_DOWN} while in {@link DelegatingState}
         */
        public long mLastDelegatedDownEventTime;

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {

        	// Ensure that the state at the end of delegation is consistent with the last delegated
            // UP/DOWN event in queue: still delegating if pointer is down, detecting otherwise
            switch (event.getActionMasked()) {
                case ACTION_UP:
                case ACTION_CANCEL: {
                    transitionTo(mDetectingState);
                } break;

                case ACTION_DOWN: {
                	transitionTo(mDelegatingState);
                    mLastDelegatedDownEventTime = event.getDownTime();
                } break;
            }

            if (getNext() != null) {
                // We cache some events to see if the user wants to trigger magnification.
                // If no magnification is triggered we inject these events with adjusted
                // time and down time to prevent subsequent transformations being confused
                // by stale events. After the cached events, which always have a down, are
                // injected we need to also update the down time of all subsequent non cached
                // events. All delegated events cached and non-cached are delivered here.
                event.setDownTime(mLastDelegatedDownEventTime);
                dispatchTransformedEvent(event, rawEvent, policyFlags);
            }
        }
    }

    /**
     * This class handles motion events when the event dispatch has not yet
     * determined what the user is doing. It watches for various tap events.
     */
    final class DetectingState implements State, Handler.Callback {

        private static final int MESSAGE_ON_TRIPLE_TAP_AND_HOLD = 1;
        private static final int MESSAGE_TRANSITION_TO_DELEGATING_STATE = 2;

        final int mLongTapMinDelay;
        final int mSwipeMinDistance;
        final int mMultiTapMaxDelay;
        final int mMultiTapMaxDistance;

        private MotionEventInfo mDelayedEventQueue;
        MotionEvent mLastDown;
        private MotionEvent mPreLastDown;
        private MotionEvent mLastUp;
        private MotionEvent mPreLastUp;

        @VisibleForTesting boolean mShortcutTriggered;

        @VisibleForTesting Handler mHandler = new Handler(Looper.getMainLooper(), this);

        public DetectingState(Context context) {
            mLongTapMinDelay = ViewConfiguration.getLongPressTimeout();
            mMultiTapMaxDelay = ViewConfiguration.getDoubleTapTimeout()
                    + context.getResources().getInteger(
                    com.android.internal.R.integer.config_screen_magnification_multi_tap_adjustment);
            mSwipeMinDistance = ViewConfiguration.get(context).getScaledTouchSlop();
            mMultiTapMaxDistance = ViewConfiguration.get(context).getScaledDoubleTapSlop();
        }

        @Override
        public boolean handleMessage(Message message) {
            final int type = message.what;
            switch (type) {
                case MESSAGE_ON_TRIPLE_TAP_AND_HOLD: {
                    MotionEvent down = (MotionEvent) message.obj;
                    transitionToViewportDraggingStateAndClear(down);
                    down.recycle();
                }
                break;
                case MESSAGE_TRANSITION_TO_DELEGATING_STATE: {
                    transitionToDelegatingStateAndClear();
                }
                break;
                default: {
                    throw new IllegalArgumentException("Unknown message type: " + type);
                }
            }
            return true;
        }

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            cacheDelayedMotionEvent(event, rawEvent, policyFlags);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {

                    mHandler.removeMessages(MESSAGE_TRANSITION_TO_DELEGATING_STATE);

                    if (!mMagnificationController.magnificationRegionContains(
                            event.getX(), event.getY())) {

                        transitionToDelegatingStateAndClear();

                    } else if (isMultiTapTriggered(2 /* taps */)) {

                        // 3tap and hold
                        afterLongTapTimeoutTransitionToDraggingState(event);

                    } else if (mDetectTripleTap
                            // If magnified, delay an ACTION_DOWN for mMultiTapMaxDelay
                            // to ensure reachability of
                            // STATE_PANNING_SCALING(triggerable with ACTION_POINTER_DOWN)
                            || mMagnificationController.isMagnifying()) {

                        afterMultiTapTimeoutTransitionToDelegatingState();

                    } else {

                        // Delegate pending events without delay
                        transitionToDelegatingStateAndClear();
                    }
                }
                break;
                case ACTION_POINTER_DOWN: {
                    if (mMagnificationController.isMagnifying()) {
                        transitionTo(mPanningScalingState);
                        clear();
                    } else {
                        transitionToDelegatingStateAndClear();
                    }
                }
                break;
                case ACTION_MOVE: {
                    if (isFingerDown()
                            && distance(mLastDown, /* move */ event) > mSwipeMinDistance) {

                        // Swipe detected - transition immediately

                        // For convenience, viewport dragging takes precedence
                        // over insta-delegating on 3tap&swipe
                        // (which is a rare combo to be used aside from magnification)
                        if (isMultiTapTriggered(2 /* taps */)) {
                            transitionToViewportDraggingStateAndClear(event);
                        } else {
                            transitionToDelegatingStateAndClear();
                        }
                    }
                }
                break;
                case ACTION_UP: {

                    mHandler.removeMessages(MESSAGE_ON_TRIPLE_TAP_AND_HOLD);

                    if (!mMagnificationController.magnificationRegionContains(
                            event.getX(), event.getY())) {

                        transitionToDelegatingStateAndClear();

                    } else if (isMultiTapTriggered(3 /* taps */)) {

                        onTripleTap(/* up */ event);

                    } else if (
                            // Possible to be false on: 3tap&drag -> scale -> PTR_UP -> UP
                            isFingerDown()
                            //TODO long tap should never happen here
                            && ((timeBetween(mLastDown, mLastUp) >= mLongTapMinDelay)
                                    || (distance(mLastDown, mLastUp) >= mSwipeMinDistance))) {

                        transitionToDelegatingStateAndClear();

                    }
                }
                break;
            }
        }

        public boolean isMultiTapTriggered(int numTaps) {

            // Shortcut acts as the 2 initial taps
            if (mShortcutTriggered) return tapCount() + 2 >= numTaps;

            return mDetectTripleTap
                    && tapCount() >= numTaps
                    && isMultiTap(mPreLastDown, mLastDown)
                    && isMultiTap(mPreLastUp, mLastUp);
        }

        private boolean isMultiTap(MotionEvent first, MotionEvent second) {
            return GestureUtils.isMultiTap(first, second, mMultiTapMaxDelay, mMultiTapMaxDistance);
        }

        public boolean isFingerDown() {
            return mLastDown != null;
        }

        private long timeBetween(@Nullable MotionEvent a, @Nullable MotionEvent b) {
            if (a == null && b == null) return 0;
            return abs(timeOf(a) - timeOf(b));
        }

        /**
         * Nullsafe {@link MotionEvent#getEventTime} that interprets null event as something that
         * has happened long enough ago to be gone from the event queue.
         * Thus the time for a null event is a small number, that is below any other non-null
         * event's time.
         *
         * @return {@link MotionEvent#getEventTime}, or {@link Long#MIN_VALUE} if the event is null
         */
        private long timeOf(@Nullable MotionEvent event) {
            return event != null ? event.getEventTime() : Long.MIN_VALUE;
        }

        public int tapCount() {
            return MotionEventInfo.countOf(mDelayedEventQueue, ACTION_UP);
        }

        /** -> {@link DelegatingState} */
        public void afterMultiTapTimeoutTransitionToDelegatingState() {
            mHandler.sendEmptyMessageDelayed(
                    MESSAGE_TRANSITION_TO_DELEGATING_STATE,
                    mMultiTapMaxDelay);
        }

        /** -> {@link ViewportDraggingState} */
        public void afterLongTapTimeoutTransitionToDraggingState(MotionEvent event) {
            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(MESSAGE_ON_TRIPLE_TAP_AND_HOLD,
                            MotionEvent.obtain(event)),
                    ViewConfiguration.getLongPressTimeout());
        }

        @Override
        public void clear() {
            setShortcutTriggered(false);
            removePendingDelayedMessages();
            clearDelayedMotionEvents();
        }

        private void removePendingDelayedMessages() {
            mHandler.removeMessages(MESSAGE_ON_TRIPLE_TAP_AND_HOLD);
            mHandler.removeMessages(MESSAGE_TRANSITION_TO_DELEGATING_STATE);
        }

        private void cacheDelayedMotionEvent(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
            if (event.getActionMasked() == ACTION_DOWN) {
                mPreLastDown = mLastDown;
                mLastDown = MotionEvent.obtain(event);
            } else if (event.getActionMasked() == ACTION_UP) {
                mPreLastUp = mLastUp;
                mLastUp = MotionEvent.obtain(event);
            }

            MotionEventInfo info = MotionEventInfo.obtain(event, rawEvent,
                    policyFlags);
            if (mDelayedEventQueue == null) {
                mDelayedEventQueue = info;
            } else {
                MotionEventInfo tail = mDelayedEventQueue;
                while (tail.mNext != null) {
                    tail = tail.mNext;
                }
                tail.mNext = info;
            }
        }

        private void sendDelayedMotionEvents() {
            while (mDelayedEventQueue != null) {
                MotionEventInfo info = mDelayedEventQueue;
                mDelayedEventQueue = info.mNext;

                handleEventWith(mDelegatingState, info.event, info.rawEvent, info.policyFlags);

                info.recycle();
            }
        }

        private void clearDelayedMotionEvents() {
            while (mDelayedEventQueue != null) {
                MotionEventInfo info = mDelayedEventQueue;
                mDelayedEventQueue = info.mNext;
                info.recycle();
            }
            mPreLastDown = null;
            mPreLastUp = null;
            mLastDown = null;
            mLastUp = null;
        }

        void transitionToDelegatingStateAndClear() {
            transitionTo(mDelegatingState);
            sendDelayedMotionEvents();
            removePendingDelayedMessages();
        }

        private void onTripleTap(MotionEvent up) {

            if (DEBUG_DETECTING) {
                Slog.i(LOG_TAG, "onTripleTap(); delayed: "
                        + MotionEventInfo.toString(mDelayedEventQueue));
            }
            clear();

            // Toggle zoom
            if (mMagnificationController.isMagnifying()) {
                zoomOff();
            } else {
                zoomOn(up.getX(), up.getY());
            }
        }

        void transitionToViewportDraggingStateAndClear(MotionEvent down) {

            if (DEBUG_DETECTING) Slog.i(LOG_TAG, "onTripleTapAndHold()");
            clear();

            mViewportDraggingState.mZoomedInBeforeDrag =
                    mMagnificationController.isMagnifying();

            zoomOn(down.getX(), down.getY());

            transitionTo(mViewportDraggingState);
        }

        @Override
        public String toString() {
            return "DetectingState{" +
                    "tapCount()=" + tapCount() +
                    ", mShortcutTriggered=" + mShortcutTriggered +
                    ", mDelayedEventQueue=" + MotionEventInfo.toString(mDelayedEventQueue) +
                    '}';
        }

        void toggleShortcutTriggered() {
            setShortcutTriggered(!mShortcutTriggered);
        }

        void setShortcutTriggered(boolean state) {
            if (mShortcutTriggered == state) {
                return;
            }
            if (DEBUG_DETECTING) Slog.i(LOG_TAG, "setShortcutTriggered(" + state + ")");

            mShortcutTriggered = state;
            mMagnificationController.setForceShowMagnifiableBounds(state);
        }
    }

    private void zoomOn(float centerX, float centerY) {
        if (DEBUG_DETECTING) Slog.i(LOG_TAG, "zoomOn(" + centerX + ", " + centerY + ")");

        final float scale = MathUtils.constrain(
                mMagnificationController.getPersistedScale(),
                MIN_SCALE, MAX_SCALE);
        mMagnificationController.setScaleAndCenter(
                scale, centerX, centerY,
                /* animate */ true,
                AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
    }

    private void zoomOff() {
        if (DEBUG_DETECTING) Slog.i(LOG_TAG, "zoomOff()");

        mMagnificationController.reset(/* animate */ true);
    }

    private static MotionEvent recycleAndNullify(@Nullable MotionEvent event) {
        if (event != null) {
            event.recycle();
        }
        return null;
    }

    @Override
    public String toString() {
        return "MagnificationGesture{" +
                "mDetectingState=" + mDetectingState +
                ", mDelegatingState=" + mDelegatingState +
                ", mMagnifiedInteractionState=" + mPanningScalingState +
                ", mViewportDraggingState=" + mViewportDraggingState +
                ", mDetectTripleTap=" + mDetectTripleTap +
                ", mDetectShortcutTrigger=" + mDetectShortcutTrigger +
                ", mCurrentState=" + State.nameOf(mCurrentState) +
                ", mPreviousState=" + State.nameOf(mPreviousState) +
                ", mMagnificationController=" + mMagnificationController +
                '}';
    }

    private static final class MotionEventInfo {

        private static final int MAX_POOL_SIZE = 10;
        private static final Object sLock = new Object();
        private static MotionEventInfo sPool;
        private static int sPoolSize;

        private MotionEventInfo mNext;
        private boolean mInPool;

        public MotionEvent event;
        public MotionEvent rawEvent;
        public int policyFlags;

        public static MotionEventInfo obtain(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
            synchronized (sLock) {
                MotionEventInfo info = obtainInternal();
                info.initialize(event, rawEvent, policyFlags);
                return info;
            }
        }

        @NonNull
        private static MotionEventInfo obtainInternal() {
            MotionEventInfo info;
            if (sPoolSize > 0) {
                sPoolSize--;
                info = sPool;
                sPool = info.mNext;
                info.mNext = null;
                info.mInPool = false;
            } else {
                info = new MotionEventInfo();
            }
            return info;
        }

        private void initialize(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
            this.event = MotionEvent.obtain(event);
            this.rawEvent = MotionEvent.obtain(rawEvent);
            this.policyFlags = policyFlags;
        }

        public void recycle() {
            synchronized (sLock) {
                if (mInPool) {
                    throw new IllegalStateException("Already recycled.");
                }
                clear();
                if (sPoolSize < MAX_POOL_SIZE) {
                    sPoolSize++;
                    mNext = sPool;
                    sPool = this;
                    mInPool = true;
                }
            }
        }

        private void clear() {
            event = recycleAndNullify(event);
            rawEvent = recycleAndNullify(rawEvent);
            policyFlags = 0;
        }

        static int countOf(MotionEventInfo info, int eventType) {
            if (info == null) return 0;
            return (info.event.getAction() == eventType ? 1 : 0)
                    + countOf(info.mNext, eventType);
        }

        public static String toString(MotionEventInfo info) {
            return info == null
                    ? ""
                    : MotionEvent.actionToString(info.event.getAction()).replace("ACTION_", "")
                            + " " + MotionEventInfo.toString(info.mNext);
        }
    }

    /**
     * BroadcastReceiver used to cancel the magnification shortcut when the screen turns off
     */
    private static class ScreenStateReceiver extends BroadcastReceiver {
        private final Context mContext;
        private final MagnificationGestureHandler mGestureHandler;

        public ScreenStateReceiver(Context context, MagnificationGestureHandler gestureHandler) {
            mContext = context;
            mGestureHandler = gestureHandler;
        }

        public void register() {
            mContext.registerReceiver(this, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        }

        public void unregister() {
            mContext.unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            mGestureHandler.mDetectingState.setShortcutTriggered(false);
        }
    }
}
