/*
 ** Copyright 2011, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.android.server.accessibility;

import android.content.Context;
import android.gesture.Gesture;
import android.gesture.GestureLibraries;
import android.gesture.GestureLibrary;
import android.gesture.GesturePoint;
import android.gesture.GestureStore;
import android.gesture.GestureStroke;
import android.gesture.Prediction;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.WindowManagerPolicy;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This class is a strategy for performing touch exploration. It
 * transforms the motion event stream by modifying, adding, replacing,
 * and consuming certain events. The interaction model is:
 *
 * <ol>
 *   <li>1. One finger moving slow around performs touch exploration.</li>
 *   <li>2. One finger moving fast around performs gestures.</li>
 *   <li>3. Two close fingers moving in the same direction perform a drag.</li>
 *   <li>4. Multi-finger gestures are delivered to view hierarchy.</li>
 *   <li>5. Two fingers moving in different directions are considered a multi-finger gesture.</li>
 *   <li>7. Double tapping clicks on the on the last touch explored location if it was in
 *          a window that does not take focus, otherwise the click is within the accessibility
 *          focused rectangle.</li>
 *   <li>7. Tapping and holding for a while performs a long press in a similar fashion
 *          as the click above.</li>
 * <ol>
 *
 * @hide
 */
class TouchExplorer implements EventStreamTransformation {

    private static final boolean DEBUG = false;

    // Tag for logging received events.
    private static final String LOG_TAG = "TouchExplorer";

    // States this explorer can be in.
    private static final int STATE_TOUCH_EXPLORING = 0x00000001;
    private static final int STATE_DRAGGING = 0x00000002;
    private static final int STATE_DELEGATING = 0x00000004;
    private static final int STATE_GESTURE_DETECTING = 0x00000005;

    private static final int CLICK_LOCATION_NONE = 0;
    private static final int CLICK_LOCATION_ACCESSIBILITY_FOCUS = 1;
    private static final int CLICK_LOCATION_LAST_TOUCH_EXPLORED = 2;

    // The maximum of the cosine between the vectors of two moving
    // pointers so they can be considered moving in the same direction.
    private static final float MAX_DRAGGING_ANGLE_COS = 0.525321989f; // cos(pi/4)

    // Constant referring to the ids bits of all pointers.
    private static final int ALL_POINTER_ID_BITS = 0xFFFFFFFF;

    // This constant captures the current implementation detail that
    // pointer IDs are between 0 and 31 inclusive (subject to change).
    // (See MAX_POINTER_ID in frameworks/base/include/ui/Input.h)
    private static final int MAX_POINTER_COUNT = 32;

    // Invalid pointer ID.
    private static final int INVALID_POINTER_ID = -1;

    // The velocity above which we detect gestures.
    private static final int GESTURE_DETECTION_VELOCITY_DIP = 1000;

    // The minimal distance before we take the middle of the distance between
    // the two dragging pointers as opposed to use the location of the primary one.
    private static final int MIN_POINTER_DISTANCE_TO_USE_MIDDLE_LOCATION_DIP = 200;

    // The timeout after which we are no longer trying to detect a gesture.
    private static final int EXIT_GESTURE_DETECTION_TIMEOUT = 2000;

    // Timeout before trying to decide what the user is trying to do.
    private final int mDetermineUserIntentTimeout;

    // Timeout within which we try to detect a tap.
    private final int mTapTimeout;

    // Timeout within which we try to detect a double tap.
    private final int mDoubleTapTimeout;

    // Slop between the down and up tap to be a tap.
    private final int mTouchSlop;

    // Slop between the first and second tap to be a double tap.
    private final int mDoubleTapSlop;

    // The current state of the touch explorer.
    private int mCurrentState = STATE_TOUCH_EXPLORING;

    // The ID of the pointer used for dragging.
    private int mDraggingPointerId;

    // Handler for performing asynchronous operations.
    private final Handler mHandler;

    // Command for delayed sending of a hover enter and move event.
    private final SendHoverEnterAndMoveDelayed mSendHoverEnterAndMoveDelayed;

    // Command for delayed sending of a hover exit event.
    private final SendHoverExitDelayed mSendHoverExitDelayed;

    // Command for delayed sending of touch exploration end events.
    private final SendAccessibilityEventDelayed mSendTouchExplorationEndDelayed;

    // Command for delayed sending of touch interaction end events.
    private final SendAccessibilityEventDelayed mSendTouchInteractionEndDelayed;

    // Command for delayed sending of a long press.
    private final PerformLongPressDelayed mPerformLongPressDelayed;

    // Command for exiting gesture detection mode after a timeout.
    private final ExitGestureDetectionModeDelayed mExitGestureDetectionModeDelayed;

    // Helper to detect and react to double tap in touch explore mode.
    private final DoubleTapDetector mDoubleTapDetector;

    // The scaled minimal distance before we take the middle of the distance between
    // the two dragging pointers as opposed to use the location of the primary one.
    private final int mScaledMinPointerDistanceToUseMiddleLocation;

    // The scaled velocity above which we detect gestures.
    private final int mScaledGestureDetectionVelocity;

    // The handler to which to delegate events.
    private EventStreamTransformation mNext;

    // Helper to track gesture velocity.
    private final VelocityTracker mVelocityTracker = VelocityTracker.obtain();

    // Helper class to track received pointers.
    private final ReceivedPointerTracker mReceivedPointerTracker;

    // Helper class to track injected pointers.
    private final InjectedPointerTracker mInjectedPointerTracker;

    // Handle to the accessibility manager service.
    private final AccessibilityManagerService mAms;

    // Temporary rectangle to avoid instantiation.
    private final Rect mTempRect = new Rect();

    // Temporary point to avoid instantiation.
    private final Point mTempPoint = new Point();

    // Context in which this explorer operates.
    private final Context mContext;

    // The X of the previous event.
    private float mPreviousX;

    // The Y of the previous event.
    private float mPreviousY;

    // Buffer for storing points for gesture detection.
    private final ArrayList<GesturePoint> mStrokeBuffer = new ArrayList<GesturePoint>(100);

    // The minimal delta between moves to add a gesture point.
    private static final int TOUCH_TOLERANCE = 3;

    // The minimal score for accepting a predicted gesture.
    private static final float MIN_PREDICTION_SCORE = 2.0f;

    // The library for gesture detection.
    private GestureLibrary mGestureLibrary;

    // The long pressing pointer id if coordinate remapping is needed.
    private int mLongPressingPointerId = -1;

    // The long pressing pointer X if coordinate remapping is needed.
    private int mLongPressingPointerDeltaX;

    // The long pressing pointer Y if coordinate remapping is needed.
    private int mLongPressingPointerDeltaY;

    // The id of the last touch explored window.
    private int mLastTouchedWindowId;

    // Whether touch exploration is in progress.
    private boolean mTouchExplorationInProgress;

    /**
     * Creates a new instance.
     *
     * @param inputFilter The input filter associated with this explorer.
     * @param context A context handle for accessing resources.
     */
    public TouchExplorer(Context context, AccessibilityManagerService service) {
        mContext = context;
        mAms = service;
        mReceivedPointerTracker = new ReceivedPointerTracker();
        mInjectedPointerTracker = new InjectedPointerTracker();
        mTapTimeout = ViewConfiguration.getTapTimeout();
        mDetermineUserIntentTimeout = ViewConfiguration.getDoubleTapTimeout();
        mDoubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mDoubleTapSlop = ViewConfiguration.get(context).getScaledDoubleTapSlop();
        mHandler = new Handler(context.getMainLooper());
        mPerformLongPressDelayed = new PerformLongPressDelayed();
        mExitGestureDetectionModeDelayed = new ExitGestureDetectionModeDelayed();
        mGestureLibrary = GestureLibraries.fromRawResource(context, R.raw.accessibility_gestures);
        mGestureLibrary.setOrientationStyle(8);
        mGestureLibrary.setSequenceType(GestureStore.SEQUENCE_SENSITIVE);
        mGestureLibrary.load();
        mSendHoverEnterAndMoveDelayed = new SendHoverEnterAndMoveDelayed();
        mSendHoverExitDelayed = new SendHoverExitDelayed();
        mSendTouchExplorationEndDelayed = new SendAccessibilityEventDelayed(
                AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END,
                mDetermineUserIntentTimeout);
        mSendTouchInteractionEndDelayed = new SendAccessibilityEventDelayed(
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
                mDetermineUserIntentTimeout);
        mDoubleTapDetector = new DoubleTapDetector();
        final float density = context.getResources().getDisplayMetrics().density;
        mScaledMinPointerDistanceToUseMiddleLocation =
            (int) (MIN_POINTER_DISTANCE_TO_USE_MIDDLE_LOCATION_DIP * density);
        mScaledGestureDetectionVelocity = (int) (GESTURE_DETECTION_VELOCITY_DIP * density);
    }

    public void clear() {
        // If we have not received an event then we are in initial
        // state. Therefore, there is not need to clean anything.
        MotionEvent event = mReceivedPointerTracker.getLastReceivedEvent();
        if (event != null) {
            clear(mReceivedPointerTracker.getLastReceivedEvent(), WindowManagerPolicy.FLAG_TRUSTED);
        }
    }

    public void onDestroy() {
        // TODO: Implement
    }

    private void clear(MotionEvent event, int policyFlags) {
        switch (mCurrentState) {
            case STATE_TOUCH_EXPLORING: {
                // If a touch exploration gesture is in progress send events for its end.
                sendHoverExitAndTouchExplorationGestureEndIfNeeded(policyFlags);
            } break;
            case STATE_DRAGGING: {
                mDraggingPointerId = INVALID_POINTER_ID;
                // Send exit to all pointers that we have delivered.
                sendUpForInjectedDownPointers(event, policyFlags);
            } break;
            case STATE_DELEGATING: {
                // Send exit to all pointers that we have delivered.
                sendUpForInjectedDownPointers(event, policyFlags);
            } break;
            case STATE_GESTURE_DETECTING: {
                // Clear the current stroke.
                mStrokeBuffer.clear();
            } break;
        }
        // Remove all pending callbacks.
        mSendHoverEnterAndMoveDelayed.cancel();
        mSendHoverExitDelayed.cancel();
        mPerformLongPressDelayed.cancel();
        mExitGestureDetectionModeDelayed.cancel();
        mSendTouchExplorationEndDelayed.cancel();
        mSendTouchInteractionEndDelayed.cancel();
        // Reset the pointer trackers.
        mReceivedPointerTracker.clear();
        mInjectedPointerTracker.clear();
        // Clear the double tap detector
        mDoubleTapDetector.clear();
        // Go to initial state.
        // Clear the long pressing pointer remap data.
        mLongPressingPointerId = -1;
        mLongPressingPointerDeltaX = 0;
        mLongPressingPointerDeltaY = 0;
        mCurrentState = STATE_TOUCH_EXPLORING;
        if (mNext != null) {
            mNext.clear();
        }
        mTouchExplorationInProgress = false;
        mAms.onTouchInteractionEnd();
    }

    @Override
    public void setNext(EventStreamTransformation next) {
        mNext = next;
    }

    @Override
    public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (DEBUG) {
            Slog.d(LOG_TAG, "Received event: " + event + ", policyFlags=0x"
                    + Integer.toHexString(policyFlags));
            Slog.d(LOG_TAG, getStateSymbolicName(mCurrentState));
        }

        mReceivedPointerTracker.onMotionEvent(rawEvent);

        switch(mCurrentState) {
            case STATE_TOUCH_EXPLORING: {
                handleMotionEventStateTouchExploring(event, rawEvent, policyFlags);
            } break;
            case STATE_DRAGGING: {
                handleMotionEventStateDragging(event, policyFlags);
            } break;
            case STATE_DELEGATING: {
                handleMotionEventStateDelegating(event, policyFlags);
            } break;
            case STATE_GESTURE_DETECTING: {
                handleMotionEventGestureDetecting(rawEvent, policyFlags);
            } break;
            default:
                throw new IllegalStateException("Illegal state: " + mCurrentState);
        }
    }

    public void onAccessibilityEvent(AccessibilityEvent event) {
        final int eventType = event.getEventType();

        // The event for gesture end should be strictly after the
        // last hover exit event.
        if (mSendTouchExplorationEndDelayed.isPending()
                && eventType == AccessibilityEvent.TYPE_VIEW_HOVER_EXIT) {
                    mSendTouchExplorationEndDelayed.cancel();
            sendAccessibilityEvent(AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END);
        }

        // The event for touch interaction end should be strictly after the
        // last hover exit and the touch exploration gesture end events.
        if (mSendTouchInteractionEndDelayed.isPending()
                && eventType == AccessibilityEvent.TYPE_VIEW_HOVER_EXIT) {
            mSendTouchInteractionEndDelayed.cancel();
            sendAccessibilityEvent(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END);
        }

        // If a new window opens or the accessibility focus moves we no longer
        // want to click/long press on the last touch explored location.
        switch (eventType) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: {
                if (mInjectedPointerTracker.mLastInjectedHoverEventForClick != null) {
                    mInjectedPointerTracker.mLastInjectedHoverEventForClick.recycle();
                    mInjectedPointerTracker.mLastInjectedHoverEventForClick = null;
                }
                mLastTouchedWindowId = -1;
            } break;
            case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER:
            case AccessibilityEvent.TYPE_VIEW_HOVER_EXIT: {
                mLastTouchedWindowId = event.getWindowId();
            } break;
        }
        if (mNext != null) {
            mNext.onAccessibilityEvent(event);
        }
    }

    /**
     * Handles a motion event in touch exploring state.
     *
     * @param event The event to be handled.
     * @param rawEvent The raw (unmodified) motion event.
     * @param policyFlags The policy flags associated with the event.
     */
    private void handleMotionEventStateTouchExploring(MotionEvent event, MotionEvent rawEvent,
            int policyFlags) {
        ReceivedPointerTracker receivedTracker = mReceivedPointerTracker;

        mVelocityTracker.addMovement(rawEvent);

        mDoubleTapDetector.onMotionEvent(event, policyFlags);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mAms.onTouchInteractionStart();

                // Pre-feed the motion events to the gesture detector since we
                // have a distance slop before getting into gesture detection
                // mode and not using the points within this slop significantly
                // decreases the quality of gesture recognition.
                handleMotionEventGestureDetecting(rawEvent, policyFlags);
                sendAccessibilityEvent(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START);

                // If we still have not notified the user for the last
                // touch, we figure out what to do. If were waiting
                // we resent the delayed callback and wait again.
                mSendHoverEnterAndMoveDelayed.cancel();
                mSendHoverExitDelayed.cancel();
                mPerformLongPressDelayed.cancel();

                if (mSendTouchExplorationEndDelayed.isPending()) {
                    mSendTouchExplorationEndDelayed.forceSendAndRemove();
                }

                if (mSendTouchInteractionEndDelayed.isPending()) {
                    mSendTouchInteractionEndDelayed.forceSendAndRemove();
                }

                // If we have the first tap, schedule a long press and break
                // since we do not want to schedule hover enter because
                // the delayed callback will kick in before the long click.
                // This would lead to a state transition resulting in long
                // pressing the item below the double taped area which is
                // not necessary where accessibility focus is.
                if (mDoubleTapDetector.firstTapDetected()) {
                    // We got a tap now post a long press action.
                    mPerformLongPressDelayed.post(event, policyFlags);
                    break;
                }
                if (!mTouchExplorationInProgress) {
                    if (!mSendHoverEnterAndMoveDelayed.isPending()) {
                        // Deliver hover enter with a delay to have a chance
                        // to detect what the user is trying to do.
                        final int pointerId = receivedTracker.getPrimaryPointerId();
                        final int pointerIdBits = (1 << pointerId);
                        mSendHoverEnterAndMoveDelayed.post(event, true, pointerIdBits,
                                policyFlags);
                    } else {
                        // Cache the event until we discern exploration from gesturing.
                        mSendHoverEnterAndMoveDelayed.addEvent(event);
                    }
                }
            } break;
            case MotionEvent.ACTION_POINTER_DOWN: {
                // Another finger down means that if we have not started to deliver
                // hover events, we will not have to. The code for ACTION_MOVE will
                // decide what we will actually do next.
                mSendHoverEnterAndMoveDelayed.cancel();
                mSendHoverExitDelayed.cancel();
                mPerformLongPressDelayed.cancel();
            } break;
            case MotionEvent.ACTION_MOVE: {
                final int pointerId = receivedTracker.getPrimaryPointerId();
                final int pointerIndex = event.findPointerIndex(pointerId);
                final int pointerIdBits = (1 << pointerId);
                switch (event.getPointerCount()) {
                    case 1: {
                        // We have not started sending events since we try to
                        // figure out what the user is doing.
                        if (mSendHoverEnterAndMoveDelayed.isPending()) {
                            // Pre-feed the motion events to the gesture detector since we
                            // have a distance slop before getting into gesture detection
                            // mode and not using the points within this slop significantly
                            // decreases the quality of gesture recognition.
                            handleMotionEventGestureDetecting(rawEvent, policyFlags);

                            // Cache the event until we discern exploration from gesturing.
                            mSendHoverEnterAndMoveDelayed.addEvent(event);

                            // It is *important* to use the distance traveled by the pointers
                            // on the screen which may or may not be magnified.
                            final float deltaX = receivedTracker.getReceivedPointerDownX(pointerId)
                                - rawEvent.getX(pointerIndex);
                            final float deltaY = receivedTracker.getReceivedPointerDownY(pointerId)
                                - rawEvent.getY(pointerIndex);
                            final double moveDelta = Math.hypot(deltaX, deltaY);
                            // The user has moved enough for us to decide.
                            if (moveDelta > mDoubleTapSlop) {
                                // Check whether the user is performing a gesture. We
                                // detect gestures if the pointer is moving above a
                                // given velocity.
                                mVelocityTracker.computeCurrentVelocity(1000);
                                final float maxAbsVelocity = Math.max(
                                        Math.abs(mVelocityTracker.getXVelocity(pointerId)),
                                        Math.abs(mVelocityTracker.getYVelocity(pointerId)));
                                if (maxAbsVelocity > mScaledGestureDetectionVelocity) {
                                    // We have to perform gesture detection, so
                                    // clear the current state and try to detect.
                                    mCurrentState = STATE_GESTURE_DETECTING;
                                    mVelocityTracker.clear();
                                    mSendHoverEnterAndMoveDelayed.cancel();
                                    mSendHoverExitDelayed.cancel();
                                    mPerformLongPressDelayed.cancel();
                                    mExitGestureDetectionModeDelayed.post();
                                    // Send accessibility event to announce the start
                                    // of gesture recognition.
                                    sendAccessibilityEvent(
                                            AccessibilityEvent.TYPE_GESTURE_DETECTION_START);
                                } else {
                                    // We have just decided that the user is touch,
                                    // exploring so start sending events.
                                    mSendHoverEnterAndMoveDelayed.forceSendAndRemove();
                                    mSendHoverExitDelayed.cancel();
                                    mPerformLongPressDelayed.cancel();
                                    sendMotionEvent(event, MotionEvent.ACTION_HOVER_MOVE,
                                            pointerIdBits, policyFlags);
                                }
                                break;
                            }
                        } else {
                            // Cancel the long press if pending and the user
                            // moved more than the slop.
                            if (mPerformLongPressDelayed.isPending()) {
                                final float deltaX =
                                        receivedTracker.getReceivedPointerDownX(pointerId)
                                        - rawEvent.getX(pointerIndex);
                                final float deltaY =
                                        receivedTracker.getReceivedPointerDownY(pointerId)
                                        - rawEvent.getY(pointerIndex);
                                final double moveDelta = Math.hypot(deltaX, deltaY);
                                // The user has moved enough for us to decide.
                                if (moveDelta > mTouchSlop) {
                                    mPerformLongPressDelayed.cancel();
                                }
                            }
                            if (mTouchExplorationInProgress) {
                                sendTouchExplorationGestureStartAndHoverEnterIfNeeded(policyFlags);
                                sendMotionEvent(event, MotionEvent.ACTION_HOVER_MOVE, pointerIdBits,
                                        policyFlags);
                            }
                        }
                    } break;
                    case 2: {
                        // More than one pointer so the user is not touch exploring
                        // and now we have to decide whether to delegate or drag.
                        if (mSendHoverEnterAndMoveDelayed.isPending()) {
                            // We have not started sending events so cancel
                            // scheduled sending events.
                            mSendHoverEnterAndMoveDelayed.cancel();
                            mSendHoverExitDelayed.cancel();
                            mPerformLongPressDelayed.cancel();
                        } else {
                            mPerformLongPressDelayed.cancel();
                            if (mTouchExplorationInProgress) {
                                // If the user is touch exploring the second pointer may be
                                // performing a double tap to activate an item without need
                                // for the user to lift his exploring finger.
                                // It is *important* to use the distance traveled by the pointers
                                // on the screen which may or may not be magnified.
                                final float deltaX = receivedTracker.getReceivedPointerDownX(
                                        pointerId) - rawEvent.getX(pointerIndex);
                                final float deltaY = receivedTracker.getReceivedPointerDownY(
                                        pointerId) - rawEvent.getY(pointerIndex);
                                final double moveDelta = Math.hypot(deltaX, deltaY);
                                if (moveDelta < mDoubleTapSlop) {
                                    break;
                                }
                                // We are sending events so send exit and gesture
                                // end since we transition to another state.
                                sendHoverExitAndTouchExplorationGestureEndIfNeeded(policyFlags);
                            }
                        }

                        // We know that a new state transition is to happen and the
                        // new state will not be gesture recognition, so clear the
                        // stashed gesture strokes.
                        mStrokeBuffer.clear();

                        if (isDraggingGesture(event)) {
                            // Two pointers moving in the same direction within
                            // a given distance perform a drag.
                            mCurrentState = STATE_DRAGGING;
                            mDraggingPointerId = pointerId;
                            event.setEdgeFlags(receivedTracker.getLastReceivedDownEdgeFlags());
                            sendMotionEvent(event, MotionEvent.ACTION_DOWN, pointerIdBits,
                                    policyFlags);
                        } else {
                            // Two pointers moving arbitrary are delegated to the view hierarchy.
                            mCurrentState = STATE_DELEGATING;
                            sendDownForAllNotInjectedPointers(event, policyFlags);
                        }
                        mVelocityTracker.clear();
                    } break;
                    default: {
                        // More than one pointer so the user is not touch exploring
                        // and now we have to decide whether to delegate or drag.
                        if (mSendHoverEnterAndMoveDelayed.isPending()) {
                            // We have not started sending events so cancel
                            // scheduled sending events.
                            mSendHoverEnterAndMoveDelayed.cancel();
                            mSendHoverExitDelayed.cancel();
                            mPerformLongPressDelayed.cancel();
                        } else {
                            mPerformLongPressDelayed.cancel();
                            // We are sending events so send exit and gesture
                            // end since we transition to another state.
                            sendHoverExitAndTouchExplorationGestureEndIfNeeded(policyFlags);
                        }

                        // More than two pointers are delegated to the view hierarchy.
                        mCurrentState = STATE_DELEGATING;
                        sendDownForAllNotInjectedPointers(event, policyFlags);
                        mVelocityTracker.clear();
                    }
                }
            } break;
            case MotionEvent.ACTION_UP: {
                mAms.onTouchInteractionEnd();
                // We know that we do not need the pre-fed gesture points are not
                // needed anymore since the last pointer just went up.
                mStrokeBuffer.clear();
                final int pointerId = event.getPointerId(event.getActionIndex());
                final int pointerIdBits = (1 << pointerId);

                mPerformLongPressDelayed.cancel();
                mVelocityTracker.clear();

                if (mSendHoverEnterAndMoveDelayed.isPending()) {
                    // If we have not delivered the enter schedule an exit.
                    mSendHoverExitDelayed.post(event, pointerIdBits, policyFlags);
                } else {
                    // The user is touch exploring so we send events for end.
                    sendHoverExitAndTouchExplorationGestureEndIfNeeded(policyFlags);
                }

                if (!mSendTouchInteractionEndDelayed.isPending()) {
                    mSendTouchInteractionEndDelayed.post();
                }

            } break;
            case MotionEvent.ACTION_CANCEL: {
                clear(event, policyFlags);
            } break;
        }
    }

    /**
     * Handles a motion event in dragging state.
     *
     * @param event The event to be handled.
     * @param policyFlags The policy flags associated with the event.
     */
    private void handleMotionEventStateDragging(MotionEvent event, int policyFlags) {
        final int pointerIdBits = (1 << mDraggingPointerId);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                throw new IllegalStateException("Dragging state can be reached only if two "
                        + "pointers are already down");
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // We are in dragging state so we have two pointers and another one
                // goes down => delegate the three pointers to the view hierarchy
                mCurrentState = STATE_DELEGATING;
                if (mDraggingPointerId != INVALID_POINTER_ID) {
                    sendMotionEvent(event, MotionEvent.ACTION_UP, pointerIdBits, policyFlags);
                }
                sendDownForAllNotInjectedPointers(event, policyFlags);
            } break;
            case MotionEvent.ACTION_MOVE: {
                switch (event.getPointerCount()) {
                    case 1: {
                        // do nothing
                    } break;
                    case 2: {
                        if (isDraggingGesture(event)) {
                            final float firstPtrX = event.getX(0);
                            final float firstPtrY = event.getY(0);
                            final float secondPtrX = event.getX(1);
                            final float secondPtrY = event.getY(1);

                            final float deltaX = firstPtrX - secondPtrX;
                            final float deltaY = firstPtrY - secondPtrY;
                            final double distance = Math.hypot(deltaX, deltaY);

                            if (distance > mScaledMinPointerDistanceToUseMiddleLocation) {
                                event.setLocation(deltaX / 2, deltaY / 2);
                            }

                            // If still dragging send a drag event.
                            sendMotionEvent(event, MotionEvent.ACTION_MOVE, pointerIdBits,
                                    policyFlags);
                        } else {
                            // The two pointers are moving either in different directions or
                            // no close enough => delegate the gesture to the view hierarchy.
                            mCurrentState = STATE_DELEGATING;
                            // Send an event to the end of the drag gesture.
                            sendMotionEvent(event, MotionEvent.ACTION_UP, pointerIdBits,
                                    policyFlags);
                            // Deliver all pointers to the view hierarchy.
                            sendDownForAllNotInjectedPointers(event, policyFlags);
                        }
                    } break;
                    default: {
                        mCurrentState = STATE_DELEGATING;
                        // Send an event to the end of the drag gesture.
                        sendMotionEvent(event, MotionEvent.ACTION_UP, pointerIdBits,
                                policyFlags);
                        // Deliver all pointers to the view hierarchy.
                        sendDownForAllNotInjectedPointers(event, policyFlags);
                    }
                }
            } break;
            case MotionEvent.ACTION_POINTER_UP: {
                 final int pointerId = event.getPointerId(event.getActionIndex());
                 if (pointerId == mDraggingPointerId) {
                     mDraggingPointerId = INVALID_POINTER_ID;
                     // Send an event to the end of the drag gesture.
                     sendMotionEvent(event, MotionEvent.ACTION_UP, pointerIdBits, policyFlags);
                 }
            } break;
            case MotionEvent.ACTION_UP: {
                mAms.onTouchInteractionEnd();
                // Announce the end of a new touch interaction.
                sendAccessibilityEvent(
                        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END);
                final int pointerId = event.getPointerId(event.getActionIndex());
                if (pointerId == mDraggingPointerId) {
                    mDraggingPointerId = INVALID_POINTER_ID;
                    // Send an event to the end of the drag gesture.
                    sendMotionEvent(event, MotionEvent.ACTION_UP, pointerIdBits, policyFlags);
                }
                mCurrentState = STATE_TOUCH_EXPLORING;
            } break;
            case MotionEvent.ACTION_CANCEL: {
                clear(event, policyFlags);
            } break;
        }
    }

    /**
     * Handles a motion event in delegating state.
     *
     * @param event The event to be handled.
     * @param policyFlags The policy flags associated with the event.
     */
    private void handleMotionEventStateDelegating(MotionEvent event, int policyFlags) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                throw new IllegalStateException("Delegating state can only be reached if "
                        + "there is at least one pointer down!");
            }
            case MotionEvent.ACTION_UP: {
                // Offset the event if we are doing a long press as the
                // target is not necessarily under the user's finger.
                if (mLongPressingPointerId >= 0) {
                    event = offsetEvent(event, - mLongPressingPointerDeltaX,
                            - mLongPressingPointerDeltaY);
                    // Clear the long press state.
                    mLongPressingPointerId = -1;
                    mLongPressingPointerDeltaX = 0;
                    mLongPressingPointerDeltaY = 0;
                }

                // Deliver the event.
                sendMotionEvent(event, event.getAction(), ALL_POINTER_ID_BITS, policyFlags);

                // Announce the end of a the touch interaction.
                mAms.onTouchInteractionEnd();
                sendAccessibilityEvent(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END);

                mCurrentState = STATE_TOUCH_EXPLORING;
            } break;
            case MotionEvent.ACTION_CANCEL: {
                clear(event, policyFlags);
            } break;
            default: {
                // Deliver the event.
                sendMotionEvent(event, event.getAction(), ALL_POINTER_ID_BITS, policyFlags);
            }
        }
    }

    private void handleMotionEventGestureDetecting(MotionEvent event, int policyFlags) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                final float x = event.getX();
                final float y = event.getY();
                mPreviousX = x;
                mPreviousY = y;
                mStrokeBuffer.add(new GesturePoint(x, y, event.getEventTime()));
            } break;
            case MotionEvent.ACTION_MOVE: {
                final float x = event.getX();
                final float y = event.getY();
                final float dX = Math.abs(x - mPreviousX);
                final float dY = Math.abs(y - mPreviousY);
                if (dX >= TOUCH_TOLERANCE || dY >= TOUCH_TOLERANCE) {
                    mPreviousX = x;
                    mPreviousY = y;
                    mStrokeBuffer.add(new GesturePoint(x, y, event.getEventTime()));
                }
            } break;
            case MotionEvent.ACTION_UP: {
                mAms.onTouchInteractionEnd();
                // Announce the end of the gesture recognition.
                sendAccessibilityEvent(AccessibilityEvent.TYPE_GESTURE_DETECTION_END);
                // Announce the end of a the touch interaction.
                sendAccessibilityEvent(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END);

                float x = event.getX();
                float y = event.getY();
                mStrokeBuffer.add(new GesturePoint(x, y, event.getEventTime()));

                Gesture gesture = new Gesture();
                gesture.addStroke(new GestureStroke(mStrokeBuffer));

                ArrayList<Prediction> predictions = mGestureLibrary.recognize(gesture);
                if (!predictions.isEmpty()) {
                    Prediction bestPrediction = predictions.get(0);
                    if (bestPrediction.score >= MIN_PREDICTION_SCORE) {
                        if (DEBUG) {
                            Slog.i(LOG_TAG, "gesture: " + bestPrediction.name + " score: "
                                    + bestPrediction.score);
                        }
                        try {
                            final int gestureId = Integer.parseInt(bestPrediction.name);
                            mAms.onGesture(gestureId);
                        } catch (NumberFormatException nfe) {
                            Slog.w(LOG_TAG, "Non numeric gesture id:" + bestPrediction.name);
                        }
                    }
                }

                mStrokeBuffer.clear();
                mExitGestureDetectionModeDelayed.cancel();
                mCurrentState = STATE_TOUCH_EXPLORING;
            } break;
            case MotionEvent.ACTION_CANCEL: {
                clear(event, policyFlags);
            } break;
        }
    }

    /**
     * Sends an accessibility event of the given type.
     *
     * @param type The event type.
     */
    private void sendAccessibilityEvent(int type) {
        AccessibilityManager accessibilityManager = AccessibilityManager.getInstance(mContext);
        if (accessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(type);
            event.setWindowId(mAms.getActiveWindowId());
            accessibilityManager.sendAccessibilityEvent(event);
            switch (type) {
                case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START: {
                    mTouchExplorationInProgress = true;
                } break;
                case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END: {
                    mTouchExplorationInProgress = false;
                } break;
            }
        }
    }

    /**
     * Sends down events to the view hierarchy for all pointers which are
     * not already being delivered i.e. pointers that are not yet injected.
     *
     * @param prototype The prototype from which to create the injected events.
     * @param policyFlags The policy flags associated with the event.
     */
    private void sendDownForAllNotInjectedPointers(MotionEvent prototype, int policyFlags) {
        InjectedPointerTracker injectedPointers = mInjectedPointerTracker;

        // Inject the injected pointers.
        int pointerIdBits = 0;
        final int pointerCount = prototype.getPointerCount();
        for (int i = 0; i < pointerCount; i++) {
            final int pointerId = prototype.getPointerId(i);
            // Do not send event for already delivered pointers.
            if (!injectedPointers.isInjectedPointerDown(pointerId)) {
                pointerIdBits |= (1 << pointerId);
                final int action = computeInjectionAction(MotionEvent.ACTION_DOWN, i);
                sendMotionEvent(prototype, action, pointerIdBits, policyFlags);
            }
        }
    }

    /**
     * Sends the exit events if needed. Such events are hover exit and touch explore
     * gesture end.
     *
     * @param policyFlags The policy flags associated with the event.
     */
    private void sendHoverExitAndTouchExplorationGestureEndIfNeeded(int policyFlags) {
        MotionEvent event = mInjectedPointerTracker.getLastInjectedHoverEvent();
        if (event != null && event.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT) {
            final int pointerIdBits = event.getPointerIdBits();
            if (!mSendTouchExplorationEndDelayed.isPending()) {
                mSendTouchExplorationEndDelayed.post();
            }
            sendMotionEvent(event, MotionEvent.ACTION_HOVER_EXIT, pointerIdBits, policyFlags);
        }
    }

    /**
     * Sends the enter events if needed. Such events are hover enter and touch explore
     * gesture start.
     *
     * @param policyFlags The policy flags associated with the event.
     */
    private void sendTouchExplorationGestureStartAndHoverEnterIfNeeded(int policyFlags) {
        MotionEvent event = mInjectedPointerTracker.getLastInjectedHoverEvent();
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
            final int pointerIdBits = event.getPointerIdBits();
            sendAccessibilityEvent(AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START);
            sendMotionEvent(event, MotionEvent.ACTION_HOVER_ENTER, pointerIdBits, policyFlags);
        }
    }

    /**
     * Sends up events to the view hierarchy for all pointers which are
     * already being delivered i.e. pointers that are injected.
     *
     * @param prototype The prototype from which to create the injected events.
     * @param policyFlags The policy flags associated with the event.
     */
    private void sendUpForInjectedDownPointers(MotionEvent prototype, int policyFlags) {
        final InjectedPointerTracker injectedTracked = mInjectedPointerTracker;
        int pointerIdBits = 0;
        final int pointerCount = prototype.getPointerCount();
        for (int i = 0; i < pointerCount; i++) {
            final int pointerId = prototype.getPointerId(i);
            // Skip non injected down pointers.
            if (!injectedTracked.isInjectedPointerDown(pointerId)) {
                continue;
            }
            pointerIdBits |= (1 << pointerId);
            final int action = computeInjectionAction(MotionEvent.ACTION_UP, i);
            sendMotionEvent(prototype, action, pointerIdBits, policyFlags);
        }
    }

    /**
     * Sends an up and down events.
     *
     * @param prototype The prototype from which to create the injected events.
     * @param policyFlags The policy flags associated with the event.
     * @param targetAccessibilityFocus Whether the event targets the accessibility focus.
     */
    private void sendActionDownAndUp(MotionEvent prototype, int policyFlags,
            boolean targetAccessibilityFocus) {
        // Tap with the pointer that last explored.
        final int pointerId = prototype.getPointerId(prototype.getActionIndex());
        final int pointerIdBits = (1 << pointerId);
        prototype.setTargetAccessibilityFocus(targetAccessibilityFocus);
        sendMotionEvent(prototype, MotionEvent.ACTION_DOWN, pointerIdBits, policyFlags);
        prototype.setTargetAccessibilityFocus(targetAccessibilityFocus);
        sendMotionEvent(prototype, MotionEvent.ACTION_UP, pointerIdBits, policyFlags);
    }

    /**
     * Sends an event.
     *
     * @param prototype The prototype from which to create the injected events.
     * @param action The action of the event.
     * @param pointerIdBits The bits of the pointers to send.
     * @param policyFlags The policy flags associated with the event.
     */
    private void sendMotionEvent(MotionEvent prototype, int action, int pointerIdBits,
            int policyFlags) {
        prototype.setAction(action);

        MotionEvent event = null;
        if (pointerIdBits == ALL_POINTER_ID_BITS) {
            event = prototype;
        } else {
            event = prototype.split(pointerIdBits);
        }
        if (action == MotionEvent.ACTION_DOWN) {
            event.setDownTime(event.getEventTime());
        } else {
            event.setDownTime(mInjectedPointerTracker.getLastInjectedDownEventTime());
        }

        // If the user is long pressing but the long pressing pointer
        // was not exactly over the accessibility focused item we need
        // to remap the location of that pointer so the user does not
        // have to explicitly touch explore something to be able to
        // long press it, or even worse to avoid the user long pressing
        // on the wrong item since click and long press behave differently.
        if (mLongPressingPointerId >= 0) {
            event = offsetEvent(event, - mLongPressingPointerDeltaX,
                    - mLongPressingPointerDeltaY);
        }

        if (DEBUG) {
            Slog.d(LOG_TAG, "Injecting event: " + event + ", policyFlags=0x"
                    + Integer.toHexString(policyFlags));
        }

        // Make sure that the user will see the event.
        policyFlags |= WindowManagerPolicy.FLAG_PASS_TO_USER;
        if (mNext != null) {
            // TODO: For now pass null for the raw event since the touch
            //       explorer is the last event transformation and it does
            //       not care about the raw event.
            mNext.onMotionEvent(event, null, policyFlags);
        }

        mInjectedPointerTracker.onMotionEvent(event);

        if (event != prototype) {
            event.recycle();
        }
    }

    /**
     * Offsets all pointers in the given event by adding the specified X and Y
     * offsets.
     *
     * @param event The event to offset.
     * @param offsetX The X offset.
     * @param offsetY The Y offset.
     * @return An event with the offset pointers or the original event if both
     *         offsets are zero.
     */
    private MotionEvent offsetEvent(MotionEvent event, int offsetX, int offsetY) {
        if (offsetX == 0 && offsetY == 0) {
            return event;
        }
        final int remappedIndex = event.findPointerIndex(mLongPressingPointerId);
        final int pointerCount = event.getPointerCount();
        PointerProperties[] props = PointerProperties.createArray(pointerCount);
        PointerCoords[] coords = PointerCoords.createArray(pointerCount);
        for (int i = 0; i < pointerCount; i++) {
            event.getPointerProperties(i, props[i]);
            event.getPointerCoords(i, coords[i]);
            if (i == remappedIndex) {
                coords[i].x += offsetX;
                coords[i].y += offsetY;
            }
        }
        return MotionEvent.obtain(event.getDownTime(),
                event.getEventTime(), event.getAction(), event.getPointerCount(),
                props, coords, event.getMetaState(), event.getButtonState(),
                1.0f, 1.0f, event.getDeviceId(), event.getEdgeFlags(),
                event.getSource(), event.getFlags());
    }

    /**
     * Computes the action for an injected event based on a masked action
     * and a pointer index.
     *
     * @param actionMasked The masked action.
     * @param pointerIndex The index of the pointer which has changed.
     * @return The action to be used for injection.
     */
    private int computeInjectionAction(int actionMasked, int pointerIndex) {
        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                InjectedPointerTracker injectedTracker = mInjectedPointerTracker;
                // Compute the action based on how many down pointers are injected.
                if (injectedTracker.getInjectedPointerDownCount() == 0) {
                    return MotionEvent.ACTION_DOWN;
                } else {
                    return (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        | MotionEvent.ACTION_POINTER_DOWN;
                }
            }
            case MotionEvent.ACTION_POINTER_UP: {
                InjectedPointerTracker injectedTracker = mInjectedPointerTracker;
                // Compute the action based on how many down pointers are injected.
                if (injectedTracker.getInjectedPointerDownCount() == 1) {
                    return MotionEvent.ACTION_UP;
                } else {
                    return (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        | MotionEvent.ACTION_POINTER_UP;
                }
            }
            default:
                return actionMasked;
        }
    }

    private class DoubleTapDetector {
        private MotionEvent mDownEvent;
        private MotionEvent mFirstTapEvent;

        public void onMotionEvent(MotionEvent event, int policyFlags) {
            final int actionIndex = event.getActionIndex();
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (mFirstTapEvent != null
                            && !GestureUtils.isSamePointerContext(mFirstTapEvent, event)) {
                        clear();
                    }
                    mDownEvent = MotionEvent.obtain(event);
                } break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    if (mDownEvent == null) {
                        return;
                    }
                    if (!GestureUtils.isSamePointerContext(mDownEvent, event)) {
                        clear();
                        return;
                    }
                    if (GestureUtils.isTap(mDownEvent, event, mTapTimeout, mTouchSlop,
                            actionIndex)) {
                        if (mFirstTapEvent == null || GestureUtils.isTimedOut(mFirstTapEvent,
                                event, mDoubleTapTimeout)) {
                            mFirstTapEvent = MotionEvent.obtain(event);
                            mDownEvent.recycle();
                            mDownEvent = null;
                            return;
                        }
                        if (GestureUtils.isMultiTap(mFirstTapEvent, event, mDoubleTapTimeout,
                                mDoubleTapSlop, actionIndex)) {
                            onDoubleTap(event, policyFlags);
                            mFirstTapEvent.recycle();
                            mFirstTapEvent = null;
                            mDownEvent.recycle();
                            mDownEvent = null;
                            return;
                        }
                        mFirstTapEvent.recycle();
                        mFirstTapEvent = null;
                    } else {
                        if (mFirstTapEvent != null) {
                            mFirstTapEvent.recycle();
                            mFirstTapEvent = null;
                        }
                    }
                    mDownEvent.recycle();
                    mDownEvent = null;
                } break;
            }
        }

        public void onDoubleTap(MotionEvent secondTapUp, int policyFlags) {
            // This should never be called when more than two pointers are down.
            if (secondTapUp.getPointerCount() > 2) {
                return;
            }

            // Remove pending event deliveries.
            mSendHoverEnterAndMoveDelayed.cancel();
            mSendHoverExitDelayed.cancel();
            mPerformLongPressDelayed.cancel();

            if (mSendTouchExplorationEndDelayed.isPending()) {
                mSendTouchExplorationEndDelayed.forceSendAndRemove();
            }
            if (mSendTouchInteractionEndDelayed.isPending()) {
                mSendTouchInteractionEndDelayed.forceSendAndRemove();
            }

            final int pointerId = secondTapUp.getPointerId(secondTapUp.getActionIndex());
            final int pointerIndex = secondTapUp.findPointerIndex(pointerId);

            Point clickLocation = mTempPoint;
            final int result = computeClickLocation(clickLocation);
            if (result == CLICK_LOCATION_NONE) {
                return;
            }

            // Do the click.
            PointerProperties[] properties = new PointerProperties[1];
            properties[0] = new PointerProperties();
            secondTapUp.getPointerProperties(pointerIndex, properties[0]);
            PointerCoords[] coords = new PointerCoords[1];
            coords[0] = new PointerCoords();
            coords[0].x = clickLocation.x;
            coords[0].y = clickLocation.y;
            MotionEvent event = MotionEvent.obtain(secondTapUp.getDownTime(),
                    secondTapUp.getEventTime(), MotionEvent.ACTION_DOWN, 1, properties,
                    coords, 0, 0, 1.0f, 1.0f, secondTapUp.getDeviceId(), 0,
                    secondTapUp.getSource(), secondTapUp.getFlags());
            final boolean targetAccessibilityFocus = (result == CLICK_LOCATION_ACCESSIBILITY_FOCUS);
            sendActionDownAndUp(event, policyFlags, targetAccessibilityFocus);
            event.recycle();
        }

        public void clear() {
            if (mDownEvent != null) {
                mDownEvent.recycle();
                mDownEvent = null;
            }
            if (mFirstTapEvent != null) {
                mFirstTapEvent.recycle();
                mFirstTapEvent = null;
            }
        }

        public boolean firstTapDetected() {
            return mFirstTapEvent != null
                && SystemClock.uptimeMillis() - mFirstTapEvent.getEventTime() < mDoubleTapTimeout;
        }
    }

    /**
     * Determines whether a two pointer gesture is a dragging one.
     *
     * @param event The event with the pointer data.
     * @return True if the gesture is a dragging one.
     */
    private boolean isDraggingGesture(MotionEvent event) {
        ReceivedPointerTracker receivedTracker = mReceivedPointerTracker;

        final float firstPtrX = event.getX(0);
        final float firstPtrY = event.getY(0);
        final float secondPtrX = event.getX(1);
        final float secondPtrY = event.getY(1);

        final float firstPtrDownX = receivedTracker.getReceivedPointerDownX(0);
        final float firstPtrDownY = receivedTracker.getReceivedPointerDownY(0);
        final float secondPtrDownX = receivedTracker.getReceivedPointerDownX(1);
        final float secondPtrDownY = receivedTracker.getReceivedPointerDownY(1);

        return GestureUtils.isDraggingGesture(firstPtrDownX, firstPtrDownY, secondPtrDownX,
                secondPtrDownY, firstPtrX, firstPtrY, secondPtrX, secondPtrY,
                MAX_DRAGGING_ANGLE_COS);
    }

    private int computeClickLocation(Point outLocation) {
        MotionEvent lastExploreEvent = mInjectedPointerTracker.getLastInjectedHoverEventForClick();
        if (lastExploreEvent != null) {
            final int lastExplorePointerIndex = lastExploreEvent.getActionIndex();
            outLocation.x = (int) lastExploreEvent.getX(lastExplorePointerIndex);
            outLocation.y = (int) lastExploreEvent.getY(lastExplorePointerIndex);
            if (!mAms.accessibilityFocusOnlyInActiveWindow()
                    || mLastTouchedWindowId == mAms.getActiveWindowId()) {
                if (mAms.getAccessibilityFocusClickPointInScreen(outLocation)) {
                    return CLICK_LOCATION_ACCESSIBILITY_FOCUS;
                } else {
                    return CLICK_LOCATION_LAST_TOUCH_EXPLORED;
                }
            }
        }
        if (mAms.getAccessibilityFocusClickPointInScreen(outLocation)) {
            return CLICK_LOCATION_ACCESSIBILITY_FOCUS;
        }
        return CLICK_LOCATION_NONE;
    }

    /**
     * Gets the symbolic name of a state.
     *
     * @param state A state.
     * @return The state symbolic name.
     */
    private static String getStateSymbolicName(int state) {
        switch (state) {
            case STATE_TOUCH_EXPLORING:
                return "STATE_TOUCH_EXPLORING";
            case STATE_DRAGGING:
                return "STATE_DRAGGING";
            case STATE_DELEGATING:
                return "STATE_DELEGATING";
            case STATE_GESTURE_DETECTING:
                return "STATE_GESTURE_DETECTING";
            default:
                throw new IllegalArgumentException("Unknown state: " + state);
        }
    }

    /**
     * Class for delayed exiting from gesture detecting mode.
     */
    private final class ExitGestureDetectionModeDelayed implements Runnable {

        public void post() {
            mHandler.postDelayed(this, EXIT_GESTURE_DETECTION_TIMEOUT);
        }

        public void cancel() {
            mHandler.removeCallbacks(this);
        }

        @Override
        public void run() {
            // Announce the end of gesture recognition.
            sendAccessibilityEvent(AccessibilityEvent.TYPE_GESTURE_DETECTION_END);
            // Clearing puts is in touch exploration state with a finger already
            // down, so announce the transition to exploration state.
            sendAccessibilityEvent(AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START);
            clear();
        }
    }

    /**
     * Class for delayed sending of long press.
     */
    private final class PerformLongPressDelayed implements Runnable {
        private MotionEvent mEvent;
        private int mPolicyFlags;

        public void post(MotionEvent prototype, int policyFlags) {
            mEvent = MotionEvent.obtain(prototype);
            mPolicyFlags = policyFlags;
            mHandler.postDelayed(this, ViewConfiguration.getLongPressTimeout());
        }

        public void cancel() {
            if (mEvent != null) {
                mHandler.removeCallbacks(this);
                clear();
            }
        }

        private boolean isPending() {
            return mHandler.hasCallbacks(this);
        }

        @Override
        public void run() {
            // Pointers should not be zero when running this command.
            if (mReceivedPointerTracker.getLastReceivedEvent().getPointerCount() == 0) {
                return;
            }

            final int pointerId = mEvent.getPointerId(mEvent.getActionIndex());
            final int pointerIndex = mEvent.findPointerIndex(pointerId);

            Point clickLocation = mTempPoint;
            final int result = computeClickLocation(clickLocation);

            if (result == CLICK_LOCATION_NONE) {
                return;
            }

            mLongPressingPointerId = pointerId;
            mLongPressingPointerDeltaX = (int) mEvent.getX(pointerIndex) - clickLocation.x;
            mLongPressingPointerDeltaY = (int) mEvent.getY(pointerIndex) - clickLocation.y;

            sendHoverExitAndTouchExplorationGestureEndIfNeeded(mPolicyFlags);

            mCurrentState = STATE_DELEGATING;
            sendDownForAllNotInjectedPointers(mEvent, mPolicyFlags);
            clear();
        }

        private void clear() {
            mEvent.recycle();
            mEvent = null;
            mPolicyFlags = 0;
        }
    }

    /**
     * Class for delayed sending of hover enter and move events.
     */
    class SendHoverEnterAndMoveDelayed implements Runnable {
        private final String LOG_TAG_SEND_HOVER_DELAYED = "SendHoverEnterAndMoveDelayed";

        private final List<MotionEvent> mEvents = new ArrayList<MotionEvent>();

        private int mPointerIdBits;
        private int mPolicyFlags;

        public void post(MotionEvent event, boolean touchExplorationInProgress,
                int pointerIdBits, int policyFlags) {
            cancel();
            addEvent(event);
            mPointerIdBits = pointerIdBits;
            mPolicyFlags = policyFlags;
            mHandler.postDelayed(this, mDetermineUserIntentTimeout);
        }

        public void addEvent(MotionEvent event) {
            mEvents.add(MotionEvent.obtain(event));
        }

        public void cancel() {
            if (isPending()) {
                mHandler.removeCallbacks(this);
                clear();
            }
        }

        private boolean isPending() {
            return mHandler.hasCallbacks(this);
        }

        private void clear() {
            mPointerIdBits = -1;
            mPolicyFlags = 0;
            final int eventCount = mEvents.size();
            for (int i = eventCount - 1; i >= 0; i--) {
                mEvents.remove(i).recycle();
            }
        }

        public void forceSendAndRemove() {
            if (isPending()) {
                run();
                cancel();
            }
        }

        public void run() {
            // Send an accessibility event to announce the touch exploration start.
            sendAccessibilityEvent(AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START);

            if (!mEvents.isEmpty()) {
                // Deliver a down event.
                sendMotionEvent(mEvents.get(0), MotionEvent.ACTION_HOVER_ENTER,
                        mPointerIdBits, mPolicyFlags);
                if (DEBUG) {
                    Slog.d(LOG_TAG_SEND_HOVER_DELAYED,
                            "Injecting motion event: ACTION_HOVER_ENTER");
                }

                // Deliver move events.
                final int eventCount = mEvents.size();
                for (int i = 1; i < eventCount; i++) {
                    sendMotionEvent(mEvents.get(i), MotionEvent.ACTION_HOVER_MOVE,
                            mPointerIdBits, mPolicyFlags);
                    if (DEBUG) {
                        Slog.d(LOG_TAG_SEND_HOVER_DELAYED,
                                "Injecting motion event: ACTION_HOVER_MOVE");
                    }
                }
            }
            clear();
        }
    }

    /**
     * Class for delayed sending of hover exit events.
     */
    class SendHoverExitDelayed implements Runnable {
        private final String LOG_TAG_SEND_HOVER_DELAYED = "SendHoverExitDelayed";

        private MotionEvent mPrototype;
        private int mPointerIdBits;
        private int mPolicyFlags;

        public void post(MotionEvent prototype, int pointerIdBits, int policyFlags) {
            cancel();
            mPrototype = MotionEvent.obtain(prototype);
            mPointerIdBits = pointerIdBits;
            mPolicyFlags = policyFlags;
            mHandler.postDelayed(this, mDetermineUserIntentTimeout);
        }

        public void cancel() {
            if (isPending()) {
                mHandler.removeCallbacks(this);
                clear();
            }
        }

        private boolean isPending() {
            return mHandler.hasCallbacks(this);
        }

        private void clear() {
            mPrototype.recycle();
            mPrototype = null;
            mPointerIdBits = -1;
            mPolicyFlags = 0;
        }

        public void forceSendAndRemove() {
            if (isPending()) {
                run();
                cancel();
            }
        }

        public void run() {
            if (DEBUG) {
                Slog.d(LOG_TAG_SEND_HOVER_DELAYED, "Injecting motion event:"
                        + " ACTION_HOVER_EXIT");
            }
            sendMotionEvent(mPrototype, MotionEvent.ACTION_HOVER_EXIT,
                    mPointerIdBits, mPolicyFlags);
            if (!mSendTouchExplorationEndDelayed.isPending()) {
                mSendTouchExplorationEndDelayed.cancel();
                mSendTouchExplorationEndDelayed.post();
            }
            if (mSendTouchInteractionEndDelayed.isPending()) {
                  mSendTouchInteractionEndDelayed.cancel();
                mSendTouchInteractionEndDelayed.post();
            }
            clear();
        }
    }

    private class SendAccessibilityEventDelayed implements Runnable {
        private final int mEventType;
        private final int mDelay;

        public SendAccessibilityEventDelayed(int eventType, int delay) {
            mEventType = eventType;
            mDelay = delay;
        }

        public void cancel() {
            mHandler.removeCallbacks(this);
        }

        public void post() {
            mHandler.postDelayed(this, mDelay);
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
            sendAccessibilityEvent(mEventType);
        }
    }

    @Override
    public String toString() {
        return LOG_TAG;
    }

    class InjectedPointerTracker {
        private static final String LOG_TAG_INJECTED_POINTER_TRACKER = "InjectedPointerTracker";

        // Keep track of which pointers sent to the system are down.
        private int mInjectedPointersDown;

        // The time of the last injected down.
        private long mLastInjectedDownEventTime;

        // The last injected hover event.
        private MotionEvent mLastInjectedHoverEvent;

        // The last injected hover event used for performing clicks.
        private MotionEvent mLastInjectedHoverEventForClick;

        /**
         * Processes an injected {@link MotionEvent} event.
         *
         * @param event The event to process.
         */
        public void onMotionEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    final int pointerId = event.getPointerId(event.getActionIndex());
                    final int pointerFlag = (1 << pointerId);
                    mInjectedPointersDown |= pointerFlag;
                    mLastInjectedDownEventTime = event.getDownTime();
                } break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    final int pointerId = event.getPointerId(event.getActionIndex());
                    final int pointerFlag = (1 << pointerId);
                    mInjectedPointersDown &= ~pointerFlag;
                    if (mInjectedPointersDown == 0) {
                        mLastInjectedDownEventTime = 0;
                    }
                } break;
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE:
                case MotionEvent.ACTION_HOVER_EXIT: {
                    if (mLastInjectedHoverEvent != null) {
                        mLastInjectedHoverEvent.recycle();
                    }
                    mLastInjectedHoverEvent = MotionEvent.obtain(event);
                    if (mLastInjectedHoverEventForClick != null) {
                        mLastInjectedHoverEventForClick.recycle();
                    }
                    mLastInjectedHoverEventForClick = MotionEvent.obtain(event);
                } break;
            }
            if (DEBUG) {
                Slog.i(LOG_TAG_INJECTED_POINTER_TRACKER, "Injected pointer:\n" + toString());
            }
        }

        /**
         * Clears the internals state.
         */
        public void clear() {
            mInjectedPointersDown = 0;
        }

        /**
         * @return The time of the last injected down event.
         */
        public long getLastInjectedDownEventTime() {
            return mLastInjectedDownEventTime;
        }

        /**
         * @return The number of down pointers injected to the view hierarchy.
         */
        public int getInjectedPointerDownCount() {
            return Integer.bitCount(mInjectedPointersDown);
        }

        /**
         * @return The bits of the injected pointers that are down.
         */
        public int getInjectedPointersDown() {
            return mInjectedPointersDown;
        }

        /**
         * Whether an injected pointer is down.
         *
         * @param pointerId The unique pointer id.
         * @return True if the pointer is down.
         */
        public boolean isInjectedPointerDown(int pointerId) {
            final int pointerFlag = (1 << pointerId);
            return (mInjectedPointersDown & pointerFlag) != 0;
        }

        /**
         * @return The the last injected hover event.
         */
        public MotionEvent getLastInjectedHoverEvent() {
            return mLastInjectedHoverEvent;
        }

        /**
         * @return The the last injected hover event.
         */
        public MotionEvent getLastInjectedHoverEventForClick() {
            return mLastInjectedHoverEventForClick;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("=========================");
            builder.append("\nDown pointers #");
            builder.append(Integer.bitCount(mInjectedPointersDown));
            builder.append(" [ ");
            for (int i = 0; i < MAX_POINTER_COUNT; i++) {
                if ((mInjectedPointersDown & i) != 0) {
                    builder.append(i);
                    builder.append(" ");
                }
            }
            builder.append("]");
            builder.append("\n=========================");
            return builder.toString();
        }
    }

    class ReceivedPointerTracker {
        private static final String LOG_TAG_RECEIVED_POINTER_TRACKER = "ReceivedPointerTracker";

        // Keep track of where and when a pointer went down.
        private final float[] mReceivedPointerDownX = new float[MAX_POINTER_COUNT];
        private final float[] mReceivedPointerDownY = new float[MAX_POINTER_COUNT];
        private final long[] mReceivedPointerDownTime = new long[MAX_POINTER_COUNT];

        // Which pointers are down.
        private int mReceivedPointersDown;

        // The edge flags of the last received down event.
        private int mLastReceivedDownEdgeFlags;

        // Primary pointer which is either the first that went down
        // or if it goes up the next one that most recently went down.
        private int mPrimaryPointerId;

        // Keep track of the last up pointer data.
        private long mLastReceivedUpPointerDownTime;
        private float mLastReceivedUpPointerDownX;
        private float mLastReceivedUpPointerDownY;

        private MotionEvent mLastReceivedEvent;

        /**
         * Clears the internals state.
         */
        public void clear() {
            Arrays.fill(mReceivedPointerDownX, 0);
            Arrays.fill(mReceivedPointerDownY, 0);
            Arrays.fill(mReceivedPointerDownTime, 0);
            mReceivedPointersDown = 0;
            mPrimaryPointerId = 0;
            mLastReceivedUpPointerDownTime = 0;
            mLastReceivedUpPointerDownX = 0;
            mLastReceivedUpPointerDownY = 0;
        }

        /**
         * Processes a received {@link MotionEvent} event.
         *
         * @param event The event to process.
         */
        public void onMotionEvent(MotionEvent event) {
            if (mLastReceivedEvent != null) {
                mLastReceivedEvent.recycle();
            }
            mLastReceivedEvent = MotionEvent.obtain(event);

            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    handleReceivedPointerDown(event.getActionIndex(), event);
                } break;
                case MotionEvent.ACTION_POINTER_DOWN: {
                    handleReceivedPointerDown(event.getActionIndex(), event);
                } break;
                case MotionEvent.ACTION_UP: {
                    handleReceivedPointerUp(event.getActionIndex(), event);
                } break;
                case MotionEvent.ACTION_POINTER_UP: {
                    handleReceivedPointerUp(event.getActionIndex(), event);
                } break;
            }
            if (DEBUG) {
                Slog.i(LOG_TAG_RECEIVED_POINTER_TRACKER, "Received pointer:\n" + toString());
            }
        }

        /**
         * @return The last received event.
         */
        public MotionEvent getLastReceivedEvent() {
            return mLastReceivedEvent;
        }

        /**
         * @return The number of received pointers that are down.
         */
        public int getReceivedPointerDownCount() {
            return Integer.bitCount(mReceivedPointersDown);
        }

        /**
         * Whether an received pointer is down.
         *
         * @param pointerId The unique pointer id.
         * @return True if the pointer is down.
         */
        public boolean isReceivedPointerDown(int pointerId) {
            final int pointerFlag = (1 << pointerId);
            return (mReceivedPointersDown & pointerFlag) != 0;
        }

        /**
         * @param pointerId The unique pointer id.
         * @return The X coordinate where the pointer went down.
         */
        public float getReceivedPointerDownX(int pointerId) {
            return mReceivedPointerDownX[pointerId];
        }

        /**
         * @param pointerId The unique pointer id.
         * @return The Y coordinate where the pointer went down.
         */
        public float getReceivedPointerDownY(int pointerId) {
            return mReceivedPointerDownY[pointerId];
        }

        /**
         * @param pointerId The unique pointer id.
         * @return The time when the pointer went down.
         */
        public long getReceivedPointerDownTime(int pointerId) {
            return mReceivedPointerDownTime[pointerId];
        }

        /**
         * @return The id of the primary pointer.
         */
        public int getPrimaryPointerId() {
            if (mPrimaryPointerId == INVALID_POINTER_ID) {
                mPrimaryPointerId = findPrimaryPointerId();
            }
            return mPrimaryPointerId;
        }

        /**
         * @return The time when the last up received pointer went down.
         */
        public long getLastReceivedUpPointerDownTime() {
            return mLastReceivedUpPointerDownTime;
        }

        /**
         * @return The down X of the last received pointer that went up.
         */
        public float getLastReceivedUpPointerDownX() {
            return mLastReceivedUpPointerDownX;
        }

        /**
         * @return The down Y of the last received pointer that went up.
         */
        public float getLastReceivedUpPointerDownY() {
            return mLastReceivedUpPointerDownY;
        }

        /**
         * @return The edge flags of the last received down event.
         */
        public int getLastReceivedDownEdgeFlags() {
            return mLastReceivedDownEdgeFlags;
        }

        /**
         * Handles a received pointer down event.
         *
         * @param pointerIndex The index of the pointer that has changed.
         * @param event The event to be handled.
         */
        private void handleReceivedPointerDown(int pointerIndex, MotionEvent event) {
            final int pointerId = event.getPointerId(pointerIndex);
            final int pointerFlag = (1 << pointerId);

            mLastReceivedUpPointerDownTime = 0;
            mLastReceivedUpPointerDownX = 0;
            mLastReceivedUpPointerDownX = 0;

            mLastReceivedDownEdgeFlags = event.getEdgeFlags();

            mReceivedPointersDown |= pointerFlag;
            mReceivedPointerDownX[pointerId] = event.getX(pointerIndex);
            mReceivedPointerDownY[pointerId] = event.getY(pointerIndex);
            mReceivedPointerDownTime[pointerId] = event.getEventTime();

            mPrimaryPointerId = pointerId;
        }

        /**
         * Handles a received pointer up event.
         *
         * @param pointerIndex The index of the pointer that has changed.
         * @param event The event to be handled.
         */
        private void handleReceivedPointerUp(int pointerIndex, MotionEvent event) {
            final int pointerId = event.getPointerId(pointerIndex);
            final int pointerFlag = (1 << pointerId);

            mLastReceivedUpPointerDownTime = getReceivedPointerDownTime(pointerId);
            mLastReceivedUpPointerDownX = mReceivedPointerDownX[pointerId];
            mLastReceivedUpPointerDownY = mReceivedPointerDownY[pointerId];

            mReceivedPointersDown &= ~pointerFlag;
            mReceivedPointerDownX[pointerId] = 0;
            mReceivedPointerDownY[pointerId] = 0;
            mReceivedPointerDownTime[pointerId] = 0;

            if (mPrimaryPointerId == pointerId) {
                mPrimaryPointerId = INVALID_POINTER_ID;
            }
        }

        /**
         * @return The primary pointer id.
         */
        private int findPrimaryPointerId() {
            int primaryPointerId = INVALID_POINTER_ID;
            long minDownTime = Long.MAX_VALUE;

            // Find the pointer that went down first.
            int pointerIdBits = mReceivedPointersDown;
            while (pointerIdBits > 0) {
                final int pointerId = Integer.numberOfTrailingZeros(pointerIdBits);
                pointerIdBits &= ~(1 << pointerId);
                final long downPointerTime = mReceivedPointerDownTime[pointerId];
                if (downPointerTime < minDownTime) {
                    minDownTime = downPointerTime;
                    primaryPointerId = pointerId;
                }
            }
            return primaryPointerId;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("=========================");
            builder.append("\nDown pointers #");
            builder.append(getReceivedPointerDownCount());
            builder.append(" [ ");
            for (int i = 0; i < MAX_POINTER_COUNT; i++) {
                if (isReceivedPointerDown(i)) {
                    builder.append(i);
                    builder.append(" ");
                }
            }
            builder.append("]");
            builder.append("\nPrimary pointer id [ ");
            builder.append(getPrimaryPointerId());
            builder.append(" ]");
            builder.append("\n=========================");
            return builder.toString();
        }
    }
}
