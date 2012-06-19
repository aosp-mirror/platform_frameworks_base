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

import com.android.internal.R;
import com.android.server.input.InputFilter;

import java.util.ArrayList;
import java.util.Arrays;

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
 *   <li>5. Pointers that have not moved more than a specified distance after they
 *          went down are considered inactive.</li>
 *   <li>6. Two fingers moving in different directions are considered a multi-finger gesture.</li>
 *   <li>7. Double tapping clicks on the on the last touch explored location of it was in
 *          a window that does not take focus, otherwise the click is within the accessibility
 *          focused rectangle.</li>
 *   <li>7. Tapping and holding for a while performs a long press in a similar fashion
 *          as the click above.</li>
 * <ol>
 *
 * @hide
 */
public class TouchExplorer {

    private static final boolean DEBUG = false;

    // Tag for logging received events.
    private static final String LOG_TAG = "TouchExplorer";

    // States this explorer can be in.
    private static final int STATE_TOUCH_EXPLORING = 0x00000001;
    private static final int STATE_DRAGGING = 0x00000002;
    private static final int STATE_DELEGATING = 0x00000004;
    private static final int STATE_GESTURE_DETECTING = 0x00000005;

    // The minimum of the cosine between the vectors of two moving
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

    // Temporary array for storing pointer IDs.
    private final int[] mTempPointerIds = new int[MAX_POINTER_COUNT];

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

    // The InputFilter this tracker is associated with i.e. the filter
    // which delegates event processing to this touch explorer.
    private final InputFilter mInputFilter;

    // The current state of the touch explorer.
    private int mCurrentState = STATE_TOUCH_EXPLORING;

    // The ID of the pointer used for dragging.
    private int mDraggingPointerId;

    // Handler for performing asynchronous operations.
    private final Handler mHandler;

    // Command for delayed sending of a hover enter event.
    private final SendHoverDelayed mSendHoverEnterDelayed;

    // Command for delayed sending of a hover exit event.
    private final SendHoverDelayed mSendHoverExitDelayed;

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

    // Helper to track gesture velocity.
    private VelocityTracker mVelocityTracker;

    // Helper class to track received pointers.
    private final ReceivedPointerTracker mReceivedPointerTracker;

    // Helper class to track injected pointers.
    private final InjectedPointerTracker mInjectedPointerTracker;

    // Handle to the accessibility manager service.
    private final AccessibilityManagerService mAms;

    // Temporary rectangle to avoid instantiation.
    private final Rect mTempRect = new Rect();

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
    private int mLongPressingPointerId;

    // The long pressing pointer X if coordinate remapping is needed.
    private int mLongPressingPointerDeltaX;

    // The long pressing pointer Y if coordinate remapping is needed.
    private int mLongPressingPointerDeltaY;

    // The id of the last touch explored window.
    private int mLastTouchedWindowId;

    /**
     * Creates a new instance.
     *
     * @param inputFilter The input filter associated with this explorer.
     * @param context A context handle for accessing resources.
     */
    public TouchExplorer(InputFilter inputFilter, Context context,
            AccessibilityManagerService service) {
        mAms = service;
        mReceivedPointerTracker = new ReceivedPointerTracker(context);
        mInjectedPointerTracker = new InjectedPointerTracker();
        mInputFilter = inputFilter;
        mTapTimeout = ViewConfiguration.getTapTimeout();
        mDetermineUserIntentTimeout = (int) (mTapTimeout * 1.5f);
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
        mSendHoverEnterDelayed = new SendHoverDelayed(MotionEvent.ACTION_HOVER_ENTER, true);
        mSendHoverExitDelayed = new SendHoverDelayed(MotionEvent.ACTION_HOVER_EXIT, false);
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

    public void clear(MotionEvent event, int policyFlags) {
        switch (mCurrentState) {
            case STATE_TOUCH_EXPLORING: {
                // If a touch exploration gesture is in progress send events for its end.
                sendExitEventsIfNeeded(policyFlags);
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
        mSendHoverEnterDelayed.remove();
        mSendHoverExitDelayed.remove();
        mPerformLongPressDelayed.remove();
        mExitGestureDetectionModeDelayed.remove();
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
    }

    public void onMotionEvent(MotionEvent event, int policyFlags) {
        if (DEBUG) {
            Slog.d(LOG_TAG, "Received event: " + event + ", policyFlags=0x"
                    + Integer.toHexString(policyFlags));
            Slog.d(LOG_TAG, getStateSymbolicName(mCurrentState));
        }

        mReceivedPointerTracker.onMotionEvent(event);

        switch(mCurrentState) {
            case STATE_TOUCH_EXPLORING: {
                handleMotionEventStateTouchExploring(event, policyFlags);
            } break;
            case STATE_DRAGGING: {
                handleMotionEventStateDragging(event, policyFlags);
            } break;
            case STATE_DELEGATING: {
                handleMotionEventStateDelegating(event, policyFlags);
            } break;
            case STATE_GESTURE_DETECTING: {
                handleMotionEventGestureDetecting(event, policyFlags);
            } break;
            default:
                throw new IllegalStateException("Illegal state: " + mCurrentState);
        }
    }

    public void onAccessibilityEvent(AccessibilityEvent event) {
        // If a new window opens or the accessibility focus moves we no longer
        // want to click/long press on the last touch explored location.
        final int eventType = event.getEventType();
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
    }

    /**
     * Handles a motion event in touch exploring state.
     *
     * @param event The event to be handled.
     * @param policyFlags The policy flags associated with the event.
     */
    private void handleMotionEventStateTouchExploring(MotionEvent event, int policyFlags) {
        ReceivedPointerTracker receivedTracker = mReceivedPointerTracker;
        final int activePointerCount = receivedTracker.getActivePointerCount();

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);

        mDoubleTapDetector.onMotionEvent(event, policyFlags);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Pre-feed the motion events to the gesture detector since we
                // have a distance slop before getting into gesture detection
                // mode and not using the points within this slop significantly
                // decreases the quality of gesture recognition.
                handleMotionEventGestureDetecting(event, policyFlags);
                //$FALL-THROUGH$
            case MotionEvent.ACTION_POINTER_DOWN: {
                switch (activePointerCount) {
                    case 0: {
                        throw new IllegalStateException("The must always be one active pointer in"
                                + "touch exploring state!");
                    }
                    case 1: {
                        // If we still have not notified the user for the last
                        // touch, we figure out what to do. If were waiting
                        // we resent the delayed callback and wait again.
                        if (mSendHoverEnterDelayed.isPending()) {
                            mSendHoverEnterDelayed.remove();
                            mSendHoverExitDelayed.remove();
                        }

                        mPerformLongPressDelayed.remove();

                        // If we have the first tap schedule a long press and break
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
                        // Deliver hover enter with a delay to have a chance
                        // to detect what the user is trying to do.
                        final int pointerId = receivedTracker.getPrimaryActivePointerId();
                        final int pointerIdBits = (1 << pointerId);
                        mSendHoverEnterDelayed.post(event, pointerIdBits, policyFlags);
                    } break;
                    default: {
                        /* do nothing - let the code for ACTION_MOVE decide what to do */
                    } break;
                }
            } break;
            case MotionEvent.ACTION_MOVE: {
                final int pointerId = receivedTracker.getPrimaryActivePointerId();
                final int pointerIndex = event.findPointerIndex(pointerId);
                final int pointerIdBits = (1 << pointerId);
                switch (activePointerCount) {
                    case 0: {
                        /* do nothing - no active pointers so we swallow the event */
                    } break;
                    case 1: {
                        // We have not started sending events since we try to
                        // figure out what the user is doing.
                        if (mSendHoverEnterDelayed.isPending()) {
                            // Pre-feed the motion events to the gesture detector since we
                            // have a distance slop before getting into gesture detection
                            // mode and not using the points within this slop significantly
                            // decreases the quality of gesture recognition.
                            handleMotionEventGestureDetecting(event, policyFlags);

                            final float deltaX = receivedTracker.getReceivedPointerDownX(pointerId)
                                - event.getX(pointerIndex);
                            final float deltaY = receivedTracker.getReceivedPointerDownY(pointerId)
                                - event.getY(pointerIndex);
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
                                    mSendHoverEnterDelayed.remove();
                                    mSendHoverExitDelayed.remove();
                                    mPerformLongPressDelayed.remove();
                                    mExitGestureDetectionModeDelayed.post();
                                } else {
                                    // We have just decided that the user is touch,
                                    // exploring so start sending events.
                                    mSendHoverEnterDelayed.forceSendAndRemove();
                                    mSendHoverExitDelayed.remove();
                                    mPerformLongPressDelayed.remove();
                                    sendMotionEvent(event, MotionEvent.ACTION_HOVER_MOVE,
                                            pointerIdBits, policyFlags);
                                }
                                break;
                            }
                        } else {
                            // The user is wither double tapping or performing long
                            // press so do not send move events yet.
                            if (mDoubleTapDetector.firstTapDetected()) {
                                break;
                            }
                            sendEnterEventsIfNeeded(policyFlags);
                            sendMotionEvent(event, MotionEvent.ACTION_HOVER_MOVE, pointerIdBits,
                                    policyFlags);
                        }
                    } break;
                    case 2: {
                        // More than one pointer so the user is not touch exploring
                        // and now we have to decide whether to delegate or drag.
                        if (mSendHoverEnterDelayed.isPending()) {
                            // We have not started sending events so cancel
                            // scheduled sending events.
                            mSendHoverEnterDelayed.remove();
                            mSendHoverExitDelayed.remove();
                            mPerformLongPressDelayed.remove();
                        } else {
                            mPerformLongPressDelayed.remove();
                            // If the user is touch exploring the second pointer may be
                            // performing a double tap to activate an item without need
                            // for the user to lift his exploring finger.
                            final float deltaX = receivedTracker.getReceivedPointerDownX(pointerId)
                                    - event.getX(pointerIndex);
                            final float deltaY = receivedTracker.getReceivedPointerDownY(pointerId)
                                    - event.getY(pointerIndex);
                            final double moveDelta = Math.hypot(deltaX, deltaY);
                            if (moveDelta < mDoubleTapSlop) {
                                break;
                            }
                            // We are sending events so send exit and gesture
                            // end since we transition to another state.
                            sendExitEventsIfNeeded(policyFlags);
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
                            sendMotionEvent(event, MotionEvent.ACTION_DOWN, pointerIdBits,
                                    policyFlags);
                        } else {
                            // Two pointers moving arbitrary are delegated to the view hierarchy.
                            mCurrentState = STATE_DELEGATING;
                            sendDownForAllActiveNotInjectedPointers(event, policyFlags);
                        }
                    } break;
                    default: {
                        // More than one pointer so the user is not touch exploring
                        // and now we have to decide whether to delegate or drag.
                        if (mSendHoverEnterDelayed.isPending()) {
                            // We have not started sending events so cancel
                            // scheduled sending events.
                            mSendHoverEnterDelayed.remove();
                            mSendHoverExitDelayed.remove();
                            mPerformLongPressDelayed.remove();
                        } else {
                            mPerformLongPressDelayed.remove();
                            // We are sending events so send exit and gesture
                            // end since we transition to another state.
                            sendExitEventsIfNeeded(policyFlags);
                        }

                        // More than two pointers are delegated to the view hierarchy.
                        mCurrentState = STATE_DELEGATING;
                        sendDownForAllActiveNotInjectedPointers(event, policyFlags);
                    }
                }
            } break;
            case MotionEvent.ACTION_UP:
                // We know that we do not need the pre-fed gesture points are not
                // needed anymore since the last pointer just went up.
                mStrokeBuffer.clear();
                //$FALL-THROUGH$
            case MotionEvent.ACTION_POINTER_UP: {
                final int pointerId = receivedTracker.getLastReceivedUpPointerId();
                final int pointerIdBits = (1 << pointerId);
                switch (activePointerCount) {
                    case 0: {
                        // If the pointer that went up was not active we have nothing to do.
                        if (!receivedTracker.wasLastReceivedUpPointerActive()) {
                            break;
                        }

                        mPerformLongPressDelayed.remove();

                        // If we have not delivered the enter schedule exit.
                        if (mSendHoverEnterDelayed.isPending()) {
                            mSendHoverExitDelayed.post(event, pointerIdBits, policyFlags);
                        } else {
                            // The user is touch exploring so we send events for end.
                            sendExitEventsIfNeeded(policyFlags);
                        }
                    } break;
                }
                if (mVelocityTracker != null) {
                    mVelocityTracker.clear();
                    mVelocityTracker = null;
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
                sendMotionEvent(event, MotionEvent.ACTION_UP, pointerIdBits, policyFlags);
                sendDownForAllActiveNotInjectedPointers(event, policyFlags);
            } break;
            case MotionEvent.ACTION_MOVE: {
                final int activePointerCount = mReceivedPointerTracker.getActivePointerCount();
                switch (activePointerCount) {
                    case 1: {
                        // do nothing
                    } break;
                    case 2: {
                        if (isDraggingGesture(event)) {
                            // If the dragging pointer are closer that a given distance we
                            // use the location of the primary one. Otherwise, we take the
                            // middle between the pointers.
                            int[] pointerIds = mTempPointerIds;
                            mReceivedPointerTracker.populateActivePointerIds(pointerIds);

                            final int firstPtrIndex = event.findPointerIndex(pointerIds[0]);
                            final int secondPtrIndex = event.findPointerIndex(pointerIds[1]);

                            final float firstPtrX = event.getX(firstPtrIndex);
                            final float firstPtrY = event.getY(firstPtrIndex);
                            final float secondPtrX = event.getX(secondPtrIndex);
                            final float secondPtrY = event.getY(secondPtrIndex);

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
                            // Deliver all active pointers to the view hierarchy.
                            sendDownForAllActiveNotInjectedPointers(event, policyFlags);
                        }
                    } break;
                    default: {
                        mCurrentState = STATE_DELEGATING;
                        // Send an event to the end of the drag gesture.
                        sendMotionEvent(event, MotionEvent.ACTION_UP, pointerIdBits,
                                policyFlags);
                        // Deliver all active pointers to the view hierarchy.
                        sendDownForAllActiveNotInjectedPointers(event, policyFlags);
                    }
                }
            } break;
            case MotionEvent.ACTION_POINTER_UP: {
                final int activePointerCount = mReceivedPointerTracker.getActivePointerCount();
                switch (activePointerCount) {
                    case 1: {
                        // Send an event to the end of the drag gesture.
                        sendMotionEvent(event, MotionEvent.ACTION_UP, pointerIdBits, policyFlags);
                    } break;
                    default: {
                        mCurrentState = STATE_TOUCH_EXPLORING;
                    }
                }
             } break;
            case MotionEvent.ACTION_UP: {
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
            case MotionEvent.ACTION_MOVE: {
                // Check  whether some other pointer became active because they have moved
                // a given distance and if such exist send them to the view hierarchy
                final int notInjectedCount = getNotInjectedActivePointerCount(
                        mReceivedPointerTracker, mInjectedPointerTracker);
                if (notInjectedCount > 0) {
                    MotionEvent prototype = MotionEvent.obtain(event);
                    sendDownForAllActiveNotInjectedPointers(prototype, policyFlags);
                }
            } break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                mLongPressingPointerId = -1;
                mLongPressingPointerDeltaX = 0;
                mLongPressingPointerDeltaY = 0;
                // No active pointers => go to initial state.
                if (mReceivedPointerTracker.getActivePointerCount() == 0) {
                    mCurrentState = STATE_TOUCH_EXPLORING;
                }
            } break;
            case MotionEvent.ACTION_CANCEL: {
                clear(event, policyFlags);
            } break;
        }
        // Deliver the event striping out inactive pointers.
        sendMotionEventStripInactivePointers(event, policyFlags);
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
                mExitGestureDetectionModeDelayed.remove();
                mCurrentState = STATE_TOUCH_EXPLORING;
            } break;
            case MotionEvent.ACTION_CANCEL: {
                clear(event, policyFlags);
            } break;
        }
    }

    /**
     * Sends down events to the view hierarchy for all active pointers which are
     * not already being delivered i.e. pointers that are not yet injected.
     *
     * @param prototype The prototype from which to create the injected events.
     * @param policyFlags The policy flags associated with the event.
     */
    private void sendDownForAllActiveNotInjectedPointers(MotionEvent prototype, int policyFlags) {
        ReceivedPointerTracker receivedPointers = mReceivedPointerTracker;
        InjectedPointerTracker injectedPointers = mInjectedPointerTracker;
        int pointerIdBits = 0;
        final int pointerCount = prototype.getPointerCount();

        // Find which pointers are already injected.
        for (int i = 0; i < pointerCount; i++) {
            final int pointerId = prototype.getPointerId(i);
            if (injectedPointers.isInjectedPointerDown(pointerId)) {
                pointerIdBits |= (1 << pointerId);
            }
        }

        // Inject the active and not injected pointers.
        for (int i = 0; i < pointerCount; i++) {
            final int pointerId = prototype.getPointerId(i);
            // Skip inactive pointers.
            if (!receivedPointers.isActivePointer(pointerId)) {
                continue;
            }
            // Do not send event for already delivered pointers.
            if (injectedPointers.isInjectedPointerDown(pointerId)) {
                continue;
            }
            pointerIdBits |= (1 << pointerId);
            final int action = computeInjectionAction(MotionEvent.ACTION_DOWN, i);
            sendMotionEvent(prototype, action, pointerIdBits, policyFlags);
        }
    }

    /**
     * Sends the exit events if needed. Such events are hover exit and touch explore
     * gesture end.
     *
     * @param policyFlags The policy flags associated with the event.
     */
    private void sendExitEventsIfNeeded(int policyFlags) {
        MotionEvent event = mInjectedPointerTracker.getLastInjectedHoverEvent();
        if (event != null && event.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT) {
            final int pointerIdBits = event.getPointerIdBits();
            mAms.touchExplorationGestureEnded();
            sendMotionEvent(event, MotionEvent.ACTION_HOVER_EXIT, pointerIdBits, policyFlags);
        }
    }

    /**
     * Sends the enter events if needed. Such events are hover enter and touch explore
     * gesture start.
     *
     * @param policyFlags The policy flags associated with the event.
     */
    private void sendEnterEventsIfNeeded(int policyFlags) {
        MotionEvent event = mInjectedPointerTracker.getLastInjectedHoverEvent();
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
            final int pointerIdBits = event.getPointerIdBits();
            mAms.touchExplorationGestureStarted();
            sendMotionEvent(event, MotionEvent.ACTION_HOVER_ENTER, pointerIdBits, policyFlags);
        }
    }

    /**
     * Sends up events to the view hierarchy for all active pointers which are
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
     * Sends a motion event by first stripping the inactive pointers.
     *
     * @param prototype The prototype from which to create the injected event.
     * @param policyFlags The policy flags associated with the event.
     */
    private void sendMotionEventStripInactivePointers(MotionEvent prototype, int policyFlags) {
        ReceivedPointerTracker receivedTracker = mReceivedPointerTracker;

        // All pointers active therefore we just inject the event as is.
        if (prototype.getPointerCount() == receivedTracker.getActivePointerCount()) {
            sendMotionEvent(prototype, prototype.getAction(), ALL_POINTER_ID_BITS, policyFlags);
            return;
        }

        // No active pointers and the one that just went up was not
        // active, therefore we have nothing to do.
        if (receivedTracker.getActivePointerCount() == 0
                && !receivedTracker.wasLastReceivedUpPointerActive()) {
            return;
        }

        // If the action pointer going up/down is not active we have nothing to do.
        // However, for moves we keep going to report moves of active pointers.
        final int actionMasked = prototype.getActionMasked();
        final int actionPointerId = prototype.getPointerId(prototype.getActionIndex());
        if (actionMasked != MotionEvent.ACTION_MOVE) {
            if (!receivedTracker.isActiveOrWasLastActiveUpPointer(actionPointerId)) {
                return;
            }
        }

        // If the pointer is active or the pointer that just went up
        // was active we keep the pointer data in the event.
        int pointerIdBits = 0;
        final int pointerCount = prototype.getPointerCount();
        for (int pointerIndex = 0; pointerIndex < pointerCount; pointerIndex++) {
            final int pointerId = prototype.getPointerId(pointerIndex);
            if (receivedTracker.isActiveOrWasLastActiveUpPointer(pointerId)) {
                pointerIdBits |= (1 << pointerId);
            }
        }
        sendMotionEvent(prototype, prototype.getAction(), pointerIdBits, policyFlags);
    }

    /**
     * Sends an up and down events.
     *
     * @param prototype The prototype from which to create the injected events.
     * @param policyFlags The policy flags associated with the event.
     */
    private void sendActionDownAndUp(MotionEvent prototype, int policyFlags) {
        // Tap with the pointer that last explored - we may have inactive pointers.
        final int pointerId = prototype.getPointerId(prototype.getActionIndex());
        final int pointerIdBits = (1 << pointerId);
        sendMotionEvent(prototype, MotionEvent.ACTION_DOWN, pointerIdBits, policyFlags);
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
            final int remappedIndex = event.findPointerIndex(mLongPressingPointerId);
            final int pointerCount = event.getPointerCount();
            PointerProperties[] props = PointerProperties.createArray(pointerCount);
            PointerCoords[] coords = PointerCoords.createArray(pointerCount);
            for (int i = 0; i < pointerCount; i++) {
                event.getPointerProperties(i, props[i]);
                event.getPointerCoords(i, coords[i]);
                if (i == remappedIndex) {
                    coords[i].x -= mLongPressingPointerDeltaX;
                    coords[i].y -= mLongPressingPointerDeltaY;
                }
            }
            MotionEvent remapped = MotionEvent.obtain(event.getDownTime(),
                    event.getEventTime(), event.getAction(), event.getPointerCount(),
                    props, coords, event.getMetaState(), event.getButtonState(),
                    1.0f, 1.0f, event.getDeviceId(), event.getEdgeFlags(),
                    event.getSource(), event.getFlags());
            if (event != prototype) {
                event.recycle();
            }
            event = remapped;
        }

        if (DEBUG) {
            Slog.d(LOG_TAG, "Injecting event: " + event + ", policyFlags=0x"
                    + Integer.toHexString(policyFlags));
        }

        // Make sure that the user will see the event.
        policyFlags |= WindowManagerPolicy.FLAG_PASS_TO_USER;
        mInputFilter.sendInputEvent(event, policyFlags);

        mInjectedPointerTracker.onMotionEvent(event);

        if (event != prototype) {
            event.recycle();
        }
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
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (mFirstTapEvent != null && !isSamePointerContext(mFirstTapEvent, event)) {
                        clear();
                    }
                    mDownEvent = MotionEvent.obtain(event);
                } break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    if (mDownEvent == null) {
                        return;
                    }
                    if (!isSamePointerContext(mDownEvent, event)) {
                        clear();
                        return;
                    }
                    if (isTap(mDownEvent, event)) {
                        if (mFirstTapEvent == null || isTimedOut(mFirstTapEvent, event,
                                mDoubleTapTimeout)) {
                            mFirstTapEvent = MotionEvent.obtain(event);
                            mDownEvent.recycle();
                            mDownEvent = null;
                            return;
                        }
                        if (isDoubleTap(mFirstTapEvent, event)) {
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
            mSendHoverEnterDelayed.remove();
            mSendHoverExitDelayed.remove();
            mPerformLongPressDelayed.remove();

            // This is a tap so do not send hover events since
            // this events will result in firing the corresponding
            // accessibility events confusing the user about what
            // is actually clicked.
            sendExitEventsIfNeeded(policyFlags);

            int clickLocationX;
            int clickLocationY;

            final int pointerId = secondTapUp.getPointerId(secondTapUp.getActionIndex());
            final int pointerIndex = secondTapUp.findPointerIndex(pointerId);

            MotionEvent lastExploreEvent =
                mInjectedPointerTracker.getLastInjectedHoverEventForClick();
            if (lastExploreEvent == null) {
                // No last touch explored event but there is accessibility focus in
                // the active window. We click in the middle of the focus bounds.
                Rect focusBounds = mTempRect;
                if (mAms.getAccessibilityFocusBoundsInActiveWindow(focusBounds)) {
                    clickLocationX = focusBounds.centerX();
                    clickLocationY = focusBounds.centerY();
                } else {
                    // Out of luck - do nothing.
                    return;
                }
            } else {
                // If the click is within the active window but not within the
                // accessibility focus bounds we click in the focus center.
                final int lastExplorePointerIndex = lastExploreEvent.getActionIndex();
                clickLocationX = (int) lastExploreEvent.getX(lastExplorePointerIndex);
                clickLocationY = (int) lastExploreEvent.getY(lastExplorePointerIndex);
                Rect activeWindowBounds = mTempRect;
                if (mLastTouchedWindowId == mAms.getActiveWindowId()) {
                    mAms.getActiveWindowBounds(activeWindowBounds);
                    if (activeWindowBounds.contains(clickLocationX, clickLocationY)) {
                        Rect focusBounds = mTempRect;
                        if (mAms.getAccessibilityFocusBoundsInActiveWindow(focusBounds)) {
                            if (!focusBounds.contains(clickLocationX, clickLocationY)) {
                                clickLocationX = focusBounds.centerX();
                                clickLocationY = focusBounds.centerY();
                            }
                        }
                    }
                }
            }

            // Do the click.
            PointerProperties[] properties = new PointerProperties[1];
            properties[0] = new PointerProperties();
            secondTapUp.getPointerProperties(pointerIndex, properties[0]);
            PointerCoords[] coords = new PointerCoords[1];
            coords[0] = new PointerCoords();
            coords[0].x = clickLocationX;
            coords[0].y = clickLocationY;
            MotionEvent event = MotionEvent.obtain(secondTapUp.getDownTime(),
                    secondTapUp.getEventTime(), MotionEvent.ACTION_DOWN, 1, properties,
                    coords, 0, 0, 1.0f, 1.0f, secondTapUp.getDeviceId(), 0,
                    secondTapUp.getSource(), secondTapUp.getFlags());
            sendActionDownAndUp(event, policyFlags);
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

        public boolean isTap(MotionEvent down, MotionEvent up) {
            return eventsWithinTimeoutAndDistance(down, up, mTapTimeout, mTouchSlop);
        }

        private boolean isDoubleTap(MotionEvent firstUp, MotionEvent secondUp) {
            return eventsWithinTimeoutAndDistance(firstUp, secondUp, mDoubleTapTimeout,
                    mDoubleTapSlop);
        }

        private boolean eventsWithinTimeoutAndDistance(MotionEvent first, MotionEvent second,
                int timeout, int distance) {
            if (isTimedOut(first, second, timeout)) {
                return false;
            }
            final int downPtrIndex = first.getActionIndex();
            final int upPtrIndex = second.getActionIndex();
            final float deltaX = second.getX(upPtrIndex) - first.getX(downPtrIndex);
            final float deltaY = second.getY(upPtrIndex) - first.getY(downPtrIndex);
            final double deltaMove = Math.hypot(deltaX, deltaY);
            if (deltaMove >= distance) {
                return false;
            }
            return true;
        }

        private boolean isTimedOut(MotionEvent firstUp, MotionEvent secondUp, int timeout) {
            final long deltaTime = secondUp.getEventTime() - firstUp.getEventTime();
            return (deltaTime >= timeout);
        }

        private boolean isSamePointerContext(MotionEvent first, MotionEvent second) {
            return (first.getPointerIdBits() == second.getPointerIdBits()
                    && first.getPointerId(first.getActionIndex())
                            == second.getPointerId(second.getActionIndex()));
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
        int[] pointerIds = mTempPointerIds;
        receivedTracker.populateActivePointerIds(pointerIds);

        final int firstPtrIndex = event.findPointerIndex(pointerIds[0]);
        final int secondPtrIndex = event.findPointerIndex(pointerIds[1]);

        final float firstPtrX = event.getX(firstPtrIndex);
        final float firstPtrY = event.getY(firstPtrIndex);
        final float secondPtrX = event.getX(secondPtrIndex);
        final float secondPtrY = event.getY(secondPtrIndex);

        // Check if the pointers are moving in the same direction.
        final float firstDeltaX =
            firstPtrX - receivedTracker.getReceivedPointerDownX(firstPtrIndex);
        final float firstDeltaY =
            firstPtrY - receivedTracker.getReceivedPointerDownY(firstPtrIndex);

        if (firstDeltaX == 0 && firstDeltaY == 0) {
            return true;
        }

        final float firstMagnitude =
            (float) Math.sqrt(firstDeltaX * firstDeltaX + firstDeltaY * firstDeltaY);
        final float firstXNormalized =
            (firstMagnitude > 0) ? firstDeltaX / firstMagnitude : firstDeltaX;
        final float firstYNormalized =
            (firstMagnitude > 0) ? firstDeltaY / firstMagnitude : firstDeltaY;

        final float secondDeltaX =
            secondPtrX - receivedTracker.getReceivedPointerDownX(secondPtrIndex);
        final float secondDeltaY =
            secondPtrY - receivedTracker.getReceivedPointerDownY(secondPtrIndex);

        if (secondDeltaX == 0 && secondDeltaY == 0) {
            return true;
        }

        final float secondMagnitude =
            (float) Math.sqrt(secondDeltaX * secondDeltaX + secondDeltaY * secondDeltaY);
        final float secondXNormalized =
            (secondMagnitude > 0) ? secondDeltaX / secondMagnitude : secondDeltaX;
        final float secondYNormalized =
            (secondMagnitude > 0) ? secondDeltaY / secondMagnitude : secondDeltaY;

        final float angleCos =
            firstXNormalized * secondXNormalized + firstYNormalized * secondYNormalized;

        if (angleCos < MAX_DRAGGING_ANGLE_COS) {
            return false;
        }

        return true;
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
     * @return The number of non injected active pointers.
     */
    private int getNotInjectedActivePointerCount(ReceivedPointerTracker receivedTracker,
            InjectedPointerTracker injectedTracker) {
        final int pointerState = receivedTracker.getActivePointers()
                & ~injectedTracker.getInjectedPointersDown();
        return Integer.bitCount(pointerState);
    }

    /**
     * Class for delayed exiting from gesture detecting mode.
     */
    private final class ExitGestureDetectionModeDelayed implements Runnable {

        public void post() {
            mHandler.postDelayed(this, EXIT_GESTURE_DETECTION_TIMEOUT);
        }

        public void remove() {
            mHandler.removeCallbacks(this);
        }

        @Override
        public void run() {
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

        public void remove() {
            if (isPenidng()) {
                mHandler.removeCallbacks(this);
                clear();
            }
        }

        private boolean isPenidng() {
            return (mEvent != null);
        }

        @Override
        public void run() {
            // Active pointers should not be zero when running this command.
            if (mReceivedPointerTracker.getActivePointerCount() == 0) {
                return;
            }

            int clickLocationX;
            int clickLocationY;

            final int pointerId = mEvent.getPointerId(mEvent.getActionIndex());
            final int pointerIndex = mEvent.findPointerIndex(pointerId);

            MotionEvent lastExploreEvent =
                mInjectedPointerTracker.getLastInjectedHoverEventForClick();
            if (lastExploreEvent == null) {
                // No last touch explored event but there is accessibility focus in
                // the active window. We click in the middle of the focus bounds.
                Rect focusBounds = mTempRect;
                if (mAms.getAccessibilityFocusBoundsInActiveWindow(focusBounds)) {
                    clickLocationX = focusBounds.centerX();
                    clickLocationY = focusBounds.centerY();
                } else {
                    // Out of luck - do nothing.
                    return;
                }
            } else {
                // If the click is within the active window but not within the
                // accessibility focus bounds we click in the focus center.
                final int lastExplorePointerIndex = lastExploreEvent.getActionIndex();
                clickLocationX = (int) lastExploreEvent.getX(lastExplorePointerIndex);
                clickLocationY = (int) lastExploreEvent.getY(lastExplorePointerIndex);
                Rect activeWindowBounds = mTempRect;
                if (mLastTouchedWindowId == mAms.getActiveWindowId()) {
                    mAms.getActiveWindowBounds(activeWindowBounds);
                    if (activeWindowBounds.contains(clickLocationX, clickLocationY)) {
                        Rect focusBounds = mTempRect;
                        if (mAms.getAccessibilityFocusBoundsInActiveWindow(focusBounds)) {
                            if (!focusBounds.contains(clickLocationX, clickLocationY)) {
                                clickLocationX = focusBounds.centerX();
                                clickLocationY = focusBounds.centerY();
                            }
                        }
                    }
                }
            }

            mLongPressingPointerId = pointerId;
            mLongPressingPointerDeltaX = (int) mEvent.getX(pointerIndex) - clickLocationX;
            mLongPressingPointerDeltaY = (int) mEvent.getY(pointerIndex) - clickLocationY;

            sendExitEventsIfNeeded(mPolicyFlags);

            mCurrentState = STATE_DELEGATING;
            sendDownForAllActiveNotInjectedPointers(mEvent, mPolicyFlags);
            clear();
        }

        private void clear() {
            if (!isPenidng()) {
                return;
            }
            mEvent.recycle();
            mEvent = null;
            mPolicyFlags = 0;
        }
    }

    /**
     * Class for delayed sending of hover events.
     */
    class SendHoverDelayed implements Runnable {
        private final String LOG_TAG_SEND_HOVER_DELAYED = SendHoverDelayed.class.getName();

        private final int mHoverAction;
        private final boolean mGestureStarted;

        private MotionEvent mPrototype;
        private int mPointerIdBits;
        private int mPolicyFlags;

        public SendHoverDelayed(int hoverAction, boolean gestureStarted) {
            mHoverAction = hoverAction;
            mGestureStarted = gestureStarted;
        }

        public void post(MotionEvent prototype, int pointerIdBits, int policyFlags) {
            remove();
            mPrototype = MotionEvent.obtain(prototype);
            mPointerIdBits = pointerIdBits;
            mPolicyFlags = policyFlags;
            mHandler.postDelayed(this, mDetermineUserIntentTimeout);
        }

        public float getX() {
            if (isPending()) {
                return mPrototype.getX();
            }
            return 0;
        }

        public float getY() {
            if (isPending()) {
                return mPrototype.getY();
            }
            return 0;
        }

        public void remove() {
            mHandler.removeCallbacks(this);
            clear();
        }

        private boolean isPending() {
            return (mPrototype != null);
        }

        private void clear() {
            if (!isPending()) {
                return;
            }
            mPrototype.recycle();
            mPrototype = null;
            mPointerIdBits = -1;
            mPolicyFlags = 0;
        }

        public void forceSendAndRemove() {
            if (isPending()) {
                run();
                remove();
            }
        }

        public void run() {
            if (DEBUG) {
                Slog.d(LOG_TAG_SEND_HOVER_DELAYED, "Injecting motion event: "
                        + MotionEvent.actionToString(mHoverAction));
                Slog.d(LOG_TAG_SEND_HOVER_DELAYED, mGestureStarted ?
                        "touchExplorationGestureStarted" : "touchExplorationGestureEnded");
            }
            if (mGestureStarted) {
                mAms.touchExplorationGestureStarted();
            } else {
                mAms.touchExplorationGestureEnded();
            }
            sendMotionEvent(mPrototype, mHoverAction, mPointerIdBits, mPolicyFlags);
            clear();
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

        // The coefficient by which to multiply
        // ViewConfiguration.#getScaledTouchSlop()
        // to compute #mThresholdActivePointer.
        private static final int COEFFICIENT_ACTIVE_POINTER = 2;

        // Pointers that moved less than mThresholdActivePointer
        // are considered active i.e. are ignored.
        private final double mThresholdActivePointer;

        // Keep track of where and when a pointer went down.
        private final float[] mReceivedPointerDownX = new float[MAX_POINTER_COUNT];
        private final float[] mReceivedPointerDownY = new float[MAX_POINTER_COUNT];
        private final long[] mReceivedPointerDownTime = new long[MAX_POINTER_COUNT];

        // Which pointers are down.
        private int mReceivedPointersDown;

        // Which down pointers are active.
        private int mActivePointers;

        // Primary active pointer which is either the first that went down
        // or if it goes up the next active that most recently went down.
        private int mPrimaryActivePointerId;

        // Flag indicating that there is at least one active pointer moving.
        private boolean mHasMovingActivePointer;

        // Keep track of the last up pointer data.
        private long mLastReceivedUpPointerDownTime;
        private int mLastReceivedUpPointerId;
        private boolean mLastReceivedUpPointerActive;
        private float mLastReceivedUpPointerDownX;
        private float mLastReceivedUpPointerDownY;

        private MotionEvent mLastReceivedEvent;

        /**
         * Creates a new instance.
         *
         * @param context Context for looking up resources.
         */
        public ReceivedPointerTracker(Context context) {
            mThresholdActivePointer =
                ViewConfiguration.get(context).getScaledTouchSlop() * COEFFICIENT_ACTIVE_POINTER;//Heie govna
        }

        /**
         * Clears the internals state.
         */
        public void clear() {
            Arrays.fill(mReceivedPointerDownX, 0);
            Arrays.fill(mReceivedPointerDownY, 0);
            Arrays.fill(mReceivedPointerDownTime, 0);
            mReceivedPointersDown = 0;
            mActivePointers = 0;
            mPrimaryActivePointerId = 0;
            mHasMovingActivePointer = false;
            mLastReceivedUpPointerDownTime = 0;
            mLastReceivedUpPointerId = 0;
            mLastReceivedUpPointerActive = false;
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
                case MotionEvent.ACTION_MOVE: {
                    handleReceivedPointerMove(event);
                } break;
                case MotionEvent.ACTION_UP: {
                    handleReceivedPointerUp(event.getActionIndex(), event);
                } break;
                case MotionEvent.ACTION_POINTER_UP: {
                    handleReceivedPointerUp(event.getActionIndex(), event);
                } break;
            }
            if (DEBUG) {
                Slog.i(LOG_TAG_RECEIVED_POINTER_TRACKER, "Received pointer: " + toString());
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
         * @return The bits of the pointers that are active.
         */
        public int getActivePointers() {
            return mActivePointers;
        }

        /**
         * @return The number of down input  pointers that are active.
         */
        public int getActivePointerCount() {
            return Integer.bitCount(mActivePointers);
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
         * Whether an input pointer is active.
         *
         * @param pointerId The unique pointer id.
         * @return True if the pointer is active.
         */
        public boolean isActivePointer(int pointerId) {
            final int pointerFlag = (1 << pointerId);
            return (mActivePointers & pointerFlag) != 0;
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
        public int getPrimaryActivePointerId() {
            if (mPrimaryActivePointerId == INVALID_POINTER_ID) {
                mPrimaryActivePointerId = findPrimaryActivePointer();
            }
            return mPrimaryActivePointerId;
        }

        /**
         * @return The time when the last up received pointer went down.
         */
        public long getLastReceivedUpPointerDownTime() {
            return mLastReceivedUpPointerDownTime;
        }

        /**
         * @return The id of the last received pointer that went up.
         */
        public int getLastReceivedUpPointerId() {
            return mLastReceivedUpPointerId;
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
         * @return Whether the last received pointer that went up was active.
         */
        public boolean wasLastReceivedUpPointerActive() {
            return mLastReceivedUpPointerActive;
        }
        /**
         * Populates the active pointer IDs to the given array.
         * <p>
         * Note: The client is responsible for providing large enough array.
         *
         * @param outPointerIds The array to which to write the active pointers.
         */
        public void populateActivePointerIds(int[] outPointerIds) {
            int index = 0;
            for (int idBits = mActivePointers; idBits != 0; ) {
                final int id = Integer.numberOfTrailingZeros(idBits);
                idBits &= ~(1 << id);
                outPointerIds[index] = id;
                index++;
            }
        }

        /**
         * @param pointerId The unique pointer id.
         * @return Whether the pointer is active or was the last active than went up.
         */
        public boolean isActiveOrWasLastActiveUpPointer(int pointerId) {
            return (isActivePointer(pointerId)
                    || (mLastReceivedUpPointerId == pointerId
                            && mLastReceivedUpPointerActive));
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

            mLastReceivedUpPointerId = 0;
            mLastReceivedUpPointerDownTime = 0;
            mLastReceivedUpPointerActive = false;
            mLastReceivedUpPointerDownX = 0;
            mLastReceivedUpPointerDownX = 0;

            mReceivedPointersDown |= pointerFlag;
            mReceivedPointerDownX[pointerId] = event.getX(pointerIndex);
            mReceivedPointerDownY[pointerId] = event.getY(pointerIndex);
            mReceivedPointerDownTime[pointerId] = event.getEventTime();

            if (!mHasMovingActivePointer) {
                // If still no moving active pointers every
                // down pointer is the only active one.
                mActivePointers = pointerFlag;
                mPrimaryActivePointerId = pointerId;
            } else {
                // If at least one moving active pointer every
                // subsequent down pointer is active.
                mActivePointers |= pointerFlag;
            }
        }

        /**
         * Handles a received pointer move event.
         *
         * @param event The event to be handled.
         */
        private void handleReceivedPointerMove(MotionEvent event) {
            detectActivePointers(event);
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

            mLastReceivedUpPointerId = pointerId;
            mLastReceivedUpPointerDownTime = getReceivedPointerDownTime(pointerId);
            mLastReceivedUpPointerActive = isActivePointer(pointerId);
            mLastReceivedUpPointerDownX = mReceivedPointerDownX[pointerId];
            mLastReceivedUpPointerDownY = mReceivedPointerDownY[pointerId];

            mReceivedPointersDown &= ~pointerFlag;
            mActivePointers &= ~pointerFlag;
            mReceivedPointerDownX[pointerId] = 0;
            mReceivedPointerDownY[pointerId] = 0;
            mReceivedPointerDownTime[pointerId] = 0;

            if (mActivePointers == 0) {
                mHasMovingActivePointer = false;
            }
            if (mPrimaryActivePointerId == pointerId) {
                mPrimaryActivePointerId = INVALID_POINTER_ID;
            }
        }

        /**
         * Detects the active pointers in an event.
         *
         * @param event The event to examine.
         */
        private void detectActivePointers(MotionEvent event) {
            for (int i = 0, count = event.getPointerCount(); i < count; i++) {
                final int pointerId = event.getPointerId(i);
                if (mHasMovingActivePointer) {
                    // If already active => nothing to do.
                    if (isActivePointer(pointerId)) {
                        continue;
                    }
                }
                // Active pointers are ones that moved more than a given threshold.
                final float pointerDeltaMove = computePointerDeltaMove(i, event);
                if (pointerDeltaMove > mThresholdActivePointer) {
                    final int pointerFlag = (1 << pointerId);
                    mActivePointers |= pointerFlag;
                    mHasMovingActivePointer = true;
                }
            }
        }

        /**
         * @return The primary active pointer.
         */
        private int findPrimaryActivePointer() {
            int primaryActivePointerId = INVALID_POINTER_ID;
            long minDownTime = Long.MAX_VALUE;
            // Find the active pointer that went down first.
            for (int i = 0, count = mReceivedPointerDownTime.length; i < count; i++) {
                if (isActivePointer(i)) {
                    final long downPointerTime = mReceivedPointerDownTime[i];
                    if (downPointerTime < minDownTime) {
                        minDownTime = downPointerTime;
                        primaryActivePointerId = i;
                    }
                }
            }
            return primaryActivePointerId;
        }

        /**
         * Computes the move for a given action pointer index since the
         * corresponding pointer went down.
         *
         * @param pointerIndex The action pointer index.
         * @param event The event to examine.
         * @return The distance the pointer has moved.
         */
        private float computePointerDeltaMove(int pointerIndex, MotionEvent event) {
            final int pointerId = event.getPointerId(pointerIndex);
            final float deltaX = event.getX(pointerIndex) - mReceivedPointerDownX[pointerId];
            final float deltaY = event.getY(pointerIndex) - mReceivedPointerDownY[pointerId];
            return (float) Math.hypot(deltaX, deltaY);
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
            builder.append("\nActive pointers #");
            builder.append(getActivePointerCount());
            builder.append(" [ ");
            for (int i = 0; i < MAX_POINTER_COUNT; i++) {
                if (isActivePointer(i)) {
                    builder.append(i);
                    builder.append(" ");
                }
            }
            builder.append("]");
            builder.append("\nPrimary active pointer id [ ");
            builder.append(getPrimaryActivePointerId());
            builder.append(" ]");
            builder.append("\n=========================");
            return builder.toString();
        }
    }
}
