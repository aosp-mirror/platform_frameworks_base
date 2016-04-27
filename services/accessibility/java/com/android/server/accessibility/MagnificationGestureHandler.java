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

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.MathUtils;
import android.util.Slog;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

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
 * 3. Pinching with any number of additional fingers when viewport dragging
 *    is enabled, i.e. the user triple tapped and holds, would adjust the
 *    magnification scale which will become the current default magnification
 *    scale. The next time the user magnifies the same magnification scale
 *    would be used.
 *
 * 4. When in a permanent magnified state the user can use two or more fingers
 *    to pan the viewport. Note that in this mode the content is panned as
 *    opposed to the viewport dragging mode in which the viewport is moved.
 *
 * 5. When in a permanent magnified state the user can use two or more
 *    fingers to change the magnification scale which will become the current
 *    default magnification scale. The next time the user magnifies the same
 *    magnification scale would be used.
 *
 * 6. The magnification scale will be persisted in settings and in the cloud.
 */
class MagnificationGestureHandler implements EventStreamTransformation {
    private static final String LOG_TAG = "MagnificationEventHandler";

    private static final boolean DEBUG_STATE_TRANSITIONS = false;
    private static final boolean DEBUG_DETECTING = false;
    private static final boolean DEBUG_PANNING = false;

    private static final int STATE_DELEGATING = 1;
    private static final int STATE_DETECTING = 2;
    private static final int STATE_VIEWPORT_DRAGGING = 3;
    private static final int STATE_MAGNIFIED_INTERACTION = 4;

    private static final float MIN_SCALE = 2.0f;
    private static final float MAX_SCALE = 5.0f;

    private final MagnificationController mMagnificationController;
    private final DetectingStateHandler mDetectingStateHandler;
    private final MagnifiedContentInteractionStateHandler mMagnifiedContentInteractionStateHandler;
    private final StateViewportDraggingHandler mStateViewportDraggingHandler;


    private final boolean mDetectControlGestures;

    private EventStreamTransformation mNext;

    private int mCurrentState;
    private int mPreviousState;

    private boolean mTranslationEnabledBeforePan;

    private PointerCoords[] mTempPointerCoords;
    private PointerProperties[] mTempPointerProperties;

    private long mDelegatingStateDownTime;

    public MagnificationGestureHandler(Context context, AccessibilityManagerService ams,
            boolean detectControlGestures) {
        mMagnificationController = ams.getMagnificationController();
        mDetectingStateHandler = new DetectingStateHandler(context);
        mStateViewportDraggingHandler = new StateViewportDraggingHandler();
        mMagnifiedContentInteractionStateHandler =
                new MagnifiedContentInteractionStateHandler(context);
        mDetectControlGestures = detectControlGestures;

        transitionToState(STATE_DETECTING);
    }

    @Override
    public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            if (mNext != null) {
                mNext.onMotionEvent(event, rawEvent, policyFlags);
            }
            return;
        }
        if (!mDetectControlGestures) {
            if (mNext != null) {
                dispatchTransformedEvent(event, rawEvent, policyFlags);
            }
            return;
        }
        mMagnifiedContentInteractionStateHandler.onMotionEvent(event, rawEvent, policyFlags);
        switch (mCurrentState) {
            case STATE_DELEGATING: {
                handleMotionEventStateDelegating(event, rawEvent, policyFlags);
            }
            break;
            case STATE_DETECTING: {
                mDetectingStateHandler.onMotionEvent(event, rawEvent, policyFlags);
            }
            break;
            case STATE_VIEWPORT_DRAGGING: {
                mStateViewportDraggingHandler.onMotionEvent(event, rawEvent, policyFlags);
            }
            break;
            case STATE_MAGNIFIED_INTERACTION: {
                // mMagnifiedContentInteractionStateHandler handles events only
                // if this is the current state since it uses ScaleGestureDetecotr
                // and a GestureDetector which need well formed event stream.
            }
            break;
            default: {
                throw new IllegalStateException("Unknown state: " + mCurrentState);
            }
        }
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
        if (inputSource == InputDevice.SOURCE_TOUCHSCREEN) {
            clear();
        }

        if (mNext != null) {
            mNext.clearEvents(inputSource);
        }
    }

    @Override
    public void onDestroy() {
        clear();
    }

    private void clear() {
        mCurrentState = STATE_DETECTING;
        mDetectingStateHandler.clear();
        mStateViewportDraggingHandler.clear();
        mMagnifiedContentInteractionStateHandler.clear();
    }

    private void handleMotionEventStateDelegating(MotionEvent event,
            MotionEvent rawEvent, int policyFlags) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mDelegatingStateDownTime = event.getDownTime();
            }
            break;
            case MotionEvent.ACTION_UP: {
                if (mDetectingStateHandler.mDelayedEventQueue == null) {
                    transitionToState(STATE_DETECTING);
                }
            }
            break;
        }
        if (mNext != null) {
            // We cache some events to see if the user wants to trigger magnification.
            // If no magnification is triggered we inject these events with adjusted
            // time and down time to prevent subsequent transformations being confused
            // by stale events. After the cached events, which always have a down, are
            // injected we need to also update the down time of all subsequent non cached
            // events. All delegated events cached and non-cached are delivered here.
            event.setDownTime(mDelegatingStateDownTime);
            dispatchTransformedEvent(event, rawEvent, policyFlags);
        }
    }

    private void dispatchTransformedEvent(MotionEvent event, MotionEvent rawEvent,
            int policyFlags) {
        // If the event is within the magnified portion of the screen we have
        // to change its location to be where the user thinks he is poking the
        // UI which may have been magnified and panned.
        final float eventX = event.getX();
        final float eventY = event.getY();
        if (mMagnificationController.isMagnifying()
                && mMagnificationController.magnificationRegionContains(eventX, eventY)) {
            final float scale = mMagnificationController.getScale();
            final float scaledOffsetX = mMagnificationController.getOffsetX();
            final float scaledOffsetY = mMagnificationController.getOffsetY();
            final int pointerCount = event.getPointerCount();
            PointerCoords[] coords = getTempPointerCoordsWithMinSize(pointerCount);
            PointerProperties[] properties = getTempPointerPropertiesWithMinSize(
                    pointerCount);
            for (int i = 0; i < pointerCount; i++) {
                event.getPointerCoords(i, coords[i]);
                coords[i].x = (coords[i].x - scaledOffsetX) / scale;
                coords[i].y = (coords[i].y - scaledOffsetY) / scale;
                event.getPointerProperties(i, properties[i]);
            }
            event = MotionEvent.obtain(event.getDownTime(),
                    event.getEventTime(), event.getAction(), pointerCount, properties,
                    coords, 0, 0, 1.0f, 1.0f, event.getDeviceId(), 0, event.getSource(),
                    event.getFlags());
        }
        mNext.onMotionEvent(event, rawEvent, policyFlags);
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

    private void transitionToState(int state) {
        if (DEBUG_STATE_TRANSITIONS) {
            switch (state) {
                case STATE_DELEGATING: {
                    Slog.i(LOG_TAG, "mCurrentState: STATE_DELEGATING");
                }
                break;
                case STATE_DETECTING: {
                    Slog.i(LOG_TAG, "mCurrentState: STATE_DETECTING");
                }
                break;
                case STATE_VIEWPORT_DRAGGING: {
                    Slog.i(LOG_TAG, "mCurrentState: STATE_VIEWPORT_DRAGGING");
                }
                break;
                case STATE_MAGNIFIED_INTERACTION: {
                    Slog.i(LOG_TAG, "mCurrentState: STATE_MAGNIFIED_INTERACTION");
                }
                break;
                default: {
                    throw new IllegalArgumentException("Unknown state: " + state);
                }
            }
        }
        mPreviousState = mCurrentState;
        mCurrentState = state;
    }

    private interface MotionEventHandler {

        void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags);

        void clear();
    }

    /**
     * This class determines if the user is performing a scale or pan gesture.
     */
    private final class MagnifiedContentInteractionStateHandler extends SimpleOnGestureListener
            implements OnScaleGestureListener, MotionEventHandler {

        private final ScaleGestureDetector mScaleGestureDetector;

        private final GestureDetector mGestureDetector;

        private final float mScalingThreshold;

        private float mInitialScaleFactor = -1;

        private boolean mScaling;

        public MagnifiedContentInteractionStateHandler(Context context) {
            final TypedValue scaleValue = new TypedValue();
            context.getResources().getValue(
                    com.android.internal.R.dimen.config_screen_magnification_scaling_threshold,
                    scaleValue, false);
            mScalingThreshold = scaleValue.getFloat();
            mScaleGestureDetector = new ScaleGestureDetector(context, this);
            mScaleGestureDetector.setQuickScaleEnabled(false);
            mGestureDetector = new GestureDetector(context, this);
        }

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            mScaleGestureDetector.onTouchEvent(event);
            mGestureDetector.onTouchEvent(event);
            if (mCurrentState != STATE_MAGNIFIED_INTERACTION) {
                return;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                clear();
                mMagnificationController.persistScale();
                if (mPreviousState == STATE_VIEWPORT_DRAGGING) {
                    transitionToState(STATE_VIEWPORT_DRAGGING);
                } else {
                    transitionToState(STATE_DETECTING);
                }
            }
        }

        @Override
        public boolean onScroll(MotionEvent first, MotionEvent second, float distanceX,
                float distanceY) {
            if (mCurrentState != STATE_MAGNIFIED_INTERACTION) {
                return true;
            }
            if (DEBUG_PANNING) {
                Slog.i(LOG_TAG, "Panned content by scrollX: " + distanceX
                        + " scrollY: " + distanceY);
            }
            mMagnificationController.offsetMagnifiedRegionCenter(distanceX, distanceY,
                    AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (!mScaling) {
                if (mInitialScaleFactor < 0) {
                    mInitialScaleFactor = detector.getScaleFactor();
                } else {
                    final float deltaScale = detector.getScaleFactor() - mInitialScaleFactor;
                    if (Math.abs(deltaScale) > mScalingThreshold) {
                        mScaling = true;
                        return true;
                    }
                }
                return false;
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
            mMagnificationController.setScale(scale, pivotX, pivotY, false,
                    AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return (mCurrentState == STATE_MAGNIFIED_INTERACTION);
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
    }

    /**
     * This class handles motion events when the event dispatcher has
     * determined that the user is performing a single-finger drag of the
     * magnification viewport.
     */
    private final class StateViewportDraggingHandler implements MotionEventHandler {

        private boolean mLastMoveOutsideMagnifiedRegion;

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    throw new IllegalArgumentException("Unexpected event type: ACTION_DOWN");
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    clear();
                    transitionToState(STATE_MAGNIFIED_INTERACTION);
                }
                break;
                case MotionEvent.ACTION_MOVE: {
                    if (event.getPointerCount() != 1) {
                        throw new IllegalStateException("Should have one pointer down.");
                    }
                    final float eventX = event.getX();
                    final float eventY = event.getY();
                    if (mMagnificationController.magnificationRegionContains(eventX, eventY)) {
                        if (mLastMoveOutsideMagnifiedRegion) {
                            mLastMoveOutsideMagnifiedRegion = false;
                            mMagnificationController.setCenter(eventX, eventY, true,
                                    AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
                        } else {
                            mMagnificationController.setCenter(eventX, eventY, false,
                                    AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
                        }
                    } else {
                        mLastMoveOutsideMagnifiedRegion = true;
                    }
                }
                break;
                case MotionEvent.ACTION_UP: {
                    if (!mTranslationEnabledBeforePan) {
                        mMagnificationController.reset(true);
                    }
                    clear();
                    transitionToState(STATE_DETECTING);
                }
                break;
                case MotionEvent.ACTION_POINTER_UP: {
                    throw new IllegalArgumentException(
                            "Unexpected event type: ACTION_POINTER_UP");
                }
            }
        }

        @Override
        public void clear() {
            mLastMoveOutsideMagnifiedRegion = false;
        }
    }

    /**
     * This class handles motion events when the event dispatch has not yet
     * determined what the user is doing. It watches for various tap events.
     */
    private final class DetectingStateHandler implements MotionEventHandler {

        private static final int MESSAGE_ON_ACTION_TAP_AND_HOLD = 1;

        private static final int MESSAGE_TRANSITION_TO_DELEGATING_STATE = 2;

        private static final int ACTION_TAP_COUNT = 3;

        private final int mTapTimeSlop = ViewConfiguration.getJumpTapTimeout();

        private final int mMultiTapTimeSlop;

        private final int mTapDistanceSlop;

        private final int mMultiTapDistanceSlop;

        private MotionEventInfo mDelayedEventQueue;

        private MotionEvent mLastDownEvent;

        private MotionEvent mLastTapUpEvent;

        private int mTapCount;

        public DetectingStateHandler(Context context) {
            mMultiTapTimeSlop = ViewConfiguration.getDoubleTapTimeout()
                    + context.getResources().getInteger(
                    com.android.internal.R.integer.config_screen_magnification_multi_tap_adjustment);
            mTapDistanceSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            mMultiTapDistanceSlop = ViewConfiguration.get(context).getScaledDoubleTapSlop();
        }

        private final Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                final int type = message.what;
                switch (type) {
                    case MESSAGE_ON_ACTION_TAP_AND_HOLD: {
                        MotionEvent event = (MotionEvent) message.obj;
                        final int policyFlags = message.arg1;
                        onActionTapAndHold(event, policyFlags);
                    }
                    break;
                    case MESSAGE_TRANSITION_TO_DELEGATING_STATE: {
                        transitionToState(STATE_DELEGATING);
                        sendDelayedMotionEvents();
                        clear();
                    }
                    break;
                    default: {
                        throw new IllegalArgumentException("Unknown message type: " + type);
                    }
                }
            }
        };

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            cacheDelayedMotionEvent(event, rawEvent, policyFlags);
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    mHandler.removeMessages(MESSAGE_TRANSITION_TO_DELEGATING_STATE);
                    if (!mMagnificationController.magnificationRegionContains(
                            event.getX(), event.getY())) {
                        transitionToDelegatingStateAndClear();
                        return;
                    }
                    if (mTapCount == ACTION_TAP_COUNT - 1 && mLastDownEvent != null
                            && GestureUtils.isMultiTap(mLastDownEvent, event,
                            mMultiTapTimeSlop, mMultiTapDistanceSlop, 0)) {
                        Message message = mHandler.obtainMessage(MESSAGE_ON_ACTION_TAP_AND_HOLD,
                                policyFlags, 0, event);
                        mHandler.sendMessageDelayed(message,
                                ViewConfiguration.getLongPressTimeout());
                    } else if (mTapCount < ACTION_TAP_COUNT) {
                        Message message = mHandler.obtainMessage(
                                MESSAGE_TRANSITION_TO_DELEGATING_STATE);
                        mHandler.sendMessageDelayed(message, mMultiTapTimeSlop);
                    }
                    clearLastDownEvent();
                    mLastDownEvent = MotionEvent.obtain(event);
                }
                break;
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (mMagnificationController.isMagnifying()) {
                        transitionToState(STATE_MAGNIFIED_INTERACTION);
                        clear();
                    } else {
                        transitionToDelegatingStateAndClear();
                    }
                }
                break;
                case MotionEvent.ACTION_MOVE: {
                    if (mLastDownEvent != null && mTapCount < ACTION_TAP_COUNT - 1) {
                        final double distance = GestureUtils.computeDistance(mLastDownEvent,
                                event, 0);
                        if (Math.abs(distance) > mTapDistanceSlop) {
                            transitionToDelegatingStateAndClear();
                        }
                    }
                }
                break;
                case MotionEvent.ACTION_UP: {
                    if (mLastDownEvent == null) {
                        return;
                    }
                    mHandler.removeMessages(MESSAGE_ON_ACTION_TAP_AND_HOLD);
                    if (!mMagnificationController.magnificationRegionContains(
                            event.getX(), event.getY())) {
                        transitionToDelegatingStateAndClear();
                        return;
                    }
                    if (!GestureUtils.isTap(mLastDownEvent, event, mTapTimeSlop,
                            mTapDistanceSlop, 0)) {
                        transitionToDelegatingStateAndClear();
                        return;
                    }
                    if (mLastTapUpEvent != null && !GestureUtils.isMultiTap(mLastTapUpEvent,
                            event, mMultiTapTimeSlop, mMultiTapDistanceSlop, 0)) {
                        transitionToDelegatingStateAndClear();
                        return;
                    }
                    mTapCount++;
                    if (DEBUG_DETECTING) {
                        Slog.i(LOG_TAG, "Tap count:" + mTapCount);
                    }
                    if (mTapCount == ACTION_TAP_COUNT) {
                        clear();
                        onActionTap(event, policyFlags);
                        return;
                    }
                    clearLastTapUpEvent();
                    mLastTapUpEvent = MotionEvent.obtain(event);
                }
                break;
                case MotionEvent.ACTION_POINTER_UP: {
                    /* do nothing */
                }
                break;
            }
        }

        @Override
        public void clear() {
            mHandler.removeMessages(MESSAGE_ON_ACTION_TAP_AND_HOLD);
            mHandler.removeMessages(MESSAGE_TRANSITION_TO_DELEGATING_STATE);
            clearTapDetectionState();
            clearDelayedMotionEvents();
        }

        private void clearTapDetectionState() {
            mTapCount = 0;
            clearLastTapUpEvent();
            clearLastDownEvent();
        }

        private void clearLastTapUpEvent() {
            if (mLastTapUpEvent != null) {
                mLastTapUpEvent.recycle();
                mLastTapUpEvent = null;
            }
        }

        private void clearLastDownEvent() {
            if (mLastDownEvent != null) {
                mLastDownEvent.recycle();
                mLastDownEvent = null;
            }
        }

        private void cacheDelayedMotionEvent(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
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
                MagnificationGestureHandler.this.onMotionEvent(info.mEvent, info.mRawEvent,
                        info.mPolicyFlags);
                info.recycle();
            }
        }

        private void clearDelayedMotionEvents() {
            while (mDelayedEventQueue != null) {
                MotionEventInfo info = mDelayedEventQueue;
                mDelayedEventQueue = info.mNext;
                info.recycle();
            }
        }

        private void transitionToDelegatingStateAndClear() {
            transitionToState(STATE_DELEGATING);
            sendDelayedMotionEvents();
            clear();
        }

        private void onActionTap(MotionEvent up, int policyFlags) {
            if (DEBUG_DETECTING) {
                Slog.i(LOG_TAG, "onActionTap()");
            }

            if (!mMagnificationController.isMagnifying()) {
                final float targetScale = mMagnificationController.getPersistedScale();
                final float scale = MathUtils.constrain(targetScale, MIN_SCALE, MAX_SCALE);
                mMagnificationController.setScaleAndCenter(scale, up.getX(), up.getY(), true,
                        AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
            } else {
                mMagnificationController.reset(true);
            }
        }

        private void onActionTapAndHold(MotionEvent down, int policyFlags) {
            if (DEBUG_DETECTING) {
                Slog.i(LOG_TAG, "onActionTapAndHold()");
            }

            clear();
            mTranslationEnabledBeforePan = mMagnificationController.isMagnifying();

            final float targetScale = mMagnificationController.getPersistedScale();
            final float scale = MathUtils.constrain(targetScale, MIN_SCALE, MAX_SCALE);
            mMagnificationController.setScaleAndCenter(scale, down.getX(), down.getY(), true,
                    AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);

            transitionToState(STATE_VIEWPORT_DRAGGING);
        }
    }

    private static final class MotionEventInfo {

        private static final int MAX_POOL_SIZE = 10;

        private static final Object sLock = new Object();

        private static MotionEventInfo sPool;

        private static int sPoolSize;

        private MotionEventInfo mNext;

        private boolean mInPool;

        public MotionEvent mEvent;

        public MotionEvent mRawEvent;

        public int mPolicyFlags;

        public static MotionEventInfo obtain(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
            synchronized (sLock) {
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
                info.initialize(event, rawEvent, policyFlags);
                return info;
            }
        }

        private void initialize(MotionEvent event, MotionEvent rawEvent,
                int policyFlags) {
            mEvent = MotionEvent.obtain(event);
            mRawEvent = MotionEvent.obtain(rawEvent);
            mPolicyFlags = policyFlags;
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
            mEvent.recycle();
            mEvent = null;
            mRawEvent.recycle();
            mRawEvent = null;
            mPolicyFlags = 0;
        }
    }
}
