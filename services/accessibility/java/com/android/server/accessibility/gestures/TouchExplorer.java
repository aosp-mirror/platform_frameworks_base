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

package com.android.server.accessibility.gestures;

import static android.view.MotionEvent.INVALID_POINTER_ID;

import static com.android.server.accessibility.gestures.TouchState.ALL_POINTER_ID_BITS;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.util.Slog;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accessibility.BaseEventStreamTransformation;
import com.android.server.policy.WindowManagerPolicy;

import java.util.ArrayList;
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
 *   <li>6. Double tapping performs a click action on the accessibility
 *          focused rectangle.</li>
 *   <li>7. Tapping and holding for a while performs a long press in a similar fashion
 *          as the click above.</li>
 * <ol>
 *
 * @hide
 */
public class TouchExplorer extends BaseEventStreamTransformation
        implements AccessibilityGestureDetector.Listener {

    private static final boolean DEBUG = false;

    // Tag for logging received events.
    private static final String LOG_TAG = "TouchExplorer";

    // The maximum of the cosine between the vectors of two moving
    // pointers so they can be considered moving in the same direction.
    private static final float MAX_DRAGGING_ANGLE_COS = 0.525321989f; // cos(pi/4)

    // The timeout after which we are no longer trying to detect a gesture.
    private static final int EXIT_GESTURE_DETECTION_TIMEOUT = 2000;

    // Timeout before trying to decide what the user is trying to do.
    private final int mDetermineUserIntentTimeout;

    // Slop between the first and second tap to be a double tap.
    private final int mDoubleTapSlop;

    // The current state of the touch explorer.
    private TouchState mState;

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

    // Command for exiting gesture detection mode after a timeout.
    private final ExitGestureDetectionModeDelayed mExitGestureDetectionModeDelayed;

    // Helper to detect gestures.
    private final AccessibilityGestureDetector mGestureDetector;

    // Helper class to track received pointers.
    private final TouchState.ReceivedPointerTracker mReceivedPointerTracker;

    // Helper class to track injected pointers.
    private final TouchState.InjectedPointerTracker mInjectedPointerTracker;

    // Handle to the accessibility manager service.
    private final AccessibilityManagerService mAms;

    // Temporary point to avoid instantiation.
    private final Point mTempPoint = new Point();

    // Context in which this explorer operates.
    private final Context mContext;


/**
     * Creates a new instance.
     *
     * @param context A context handle for accessing resources.
     * @param service The service to notify touch interaction and gesture completed and to perform
     *                action.
     */
    public TouchExplorer(Context context, AccessibilityManagerService service) {
        this(context, service, null);
    }

    /**
     * Creates a new instance.
     *
     * @param context A context handle for accessing resources.
     * @param service The service to notify touch interaction and gesture completed and to perform
     *                action.
     * @param detector The gesture detector to handle accessibility touch event. If null the default
     *                one created in place, or for testing purpose.
     */
    public TouchExplorer(Context context, AccessibilityManagerService service,
            AccessibilityGestureDetector detector) {
        mContext = context;
        mAms = service;
        mState = new TouchState();
        mReceivedPointerTracker = mState.getReceivedPointerTracker();
        mInjectedPointerTracker = mState.getInjectedPointerTracker();
        mDetermineUserIntentTimeout = ViewConfiguration.getDoubleTapTimeout();
        mDoubleTapSlop = ViewConfiguration.get(context).getScaledDoubleTapSlop();
        mHandler = new Handler(context.getMainLooper());
        mExitGestureDetectionModeDelayed = new ExitGestureDetectionModeDelayed();
        mSendHoverEnterAndMoveDelayed = new SendHoverEnterAndMoveDelayed();
        mSendHoverExitDelayed = new SendHoverExitDelayed();
        mSendTouchExplorationEndDelayed = new SendAccessibilityEventDelayed(
                AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END,
                mDetermineUserIntentTimeout);
        mSendTouchInteractionEndDelayed = new SendAccessibilityEventDelayed(
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
                mDetermineUserIntentTimeout);
        if (detector == null) {
            mGestureDetector = new AccessibilityGestureDetector(context, this);
        } else {
            mGestureDetector = detector;
        }
    }

    @Override
    public void clearEvents(int inputSource) {
        if (inputSource == InputDevice.SOURCE_TOUCHSCREEN) {
            clear();
        }
        super.clearEvents(inputSource);
    }

    @Override
    public void onDestroy() {
        clear();
    }

    private void clear() {
        // If we have not received an event then we are in initial
        // state. Therefore, there is not need to clean anything.
        MotionEvent event = mReceivedPointerTracker.getLastReceivedEvent();
        if (event != null) {
            clear(mReceivedPointerTracker.getLastReceivedEvent(), WindowManagerPolicy.FLAG_TRUSTED);
        }
    }

    private void clear(MotionEvent event, int policyFlags) {
        if (mState.isTouchExploring()) {
            // If a touch exploration gesture is in progress send events for its end.
            sendHoverExitAndTouchExplorationGestureEndIfNeeded(policyFlags);
        }  else if (mState.isDragging()) {
            mDraggingPointerId = INVALID_POINTER_ID;
            // Send exit to all pointers that we have delivered.
            sendUpForInjectedDownPointers(event, policyFlags);
        } else if (mState.isDelegating()) {
            // Send exit to all pointers that we have delivered.
            sendUpForInjectedDownPointers(event, policyFlags);
        } else if (mState.isGestureDetecting()) {
            // No state specific cleanup required.
        }
        // Remove all pending callbacks.
        mSendHoverEnterAndMoveDelayed.cancel();
        mSendHoverExitDelayed.cancel();
        mExitGestureDetectionModeDelayed.cancel();
        mSendTouchExplorationEndDelayed.cancel();
        mSendTouchInteractionEndDelayed.cancel();
        // Clear the gesture detector
        mGestureDetector.clear();
        // Go to initial state.
        mState.clear();
        mAms.onTouchInteractionEnd();
    }

    @Override
    public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            super.onMotionEvent(event, rawEvent, policyFlags);
            return;
        }

        if (DEBUG) {
            Slog.d(LOG_TAG, "Received event: " + event + ", policyFlags=0x"
                    + Integer.toHexString(policyFlags));
            Slog.d(LOG_TAG, mState.toString());
        }

        mReceivedPointerTracker.onMotionEvent(rawEvent);

        if (mGestureDetector.onMotionEvent(event, rawEvent, policyFlags)) {
            // Event was handled by the gesture detector.
            return;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            clear(event, policyFlags);
            return;
        }

        // TODO: extract the below functions into separate handlers for each state.
        // Right now the number of functions and number of states make the code messy.
        if (mState.isClear()) {
            handleMotionEventStateClear(event, rawEvent, policyFlags);
        } else if (mState.isTouchInteracting()) {
            handleMotionEventStateTouchInteracting(event, rawEvent, policyFlags);
        } else if (mState.isTouchExploring()) {
            handleMotionEventStateTouchExploring(event, rawEvent, policyFlags);
        } else if (mState.isDragging()) {
            handleMotionEventStateDragging(event, policyFlags);
        } else if (mState.isDelegating()) {
            handleMotionEventStateDelegating(event, policyFlags);
        } else if (mState.isGestureDetecting()) {
            // Already handled.
        } else {
            Slog.e(LOG_TAG, "Illegal state: " + mState);
                clear(event, policyFlags);
        }
    }

    @Override
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
        super.onAccessibilityEvent(event);
    }

    @Override
    public void onDoubleTapAndHold(MotionEvent event, int policyFlags) {
        // Ignore the event if we aren't touch interacting.
        if (!mState.isTouchInteracting()) {
            return;
        }

        // Pointers should not be zero when running this command.
        if (mReceivedPointerTracker.getLastReceivedEvent().getPointerCount() == 0) {
            return;
        }
        // Try to use the standard accessibility API to long click
        if (!mAms.performActionOnAccessibilityFocusedItem(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)) {
            Slog.e(LOG_TAG, "ACTION_LONG_CLICK failed.");
        }
    }

    @Override
    public boolean onDoubleTap(MotionEvent event, int policyFlags) {
        if (!mState.isTouchInteracting()) {
            return false;
        }

        mAms.onTouchInteractionEnd();
        // Remove pending event deliveries.
        mSendHoverEnterAndMoveDelayed.cancel();
        mSendHoverExitDelayed.cancel();

        if (mSendTouchExplorationEndDelayed.isPending()) {
            mSendTouchExplorationEndDelayed.forceSendAndRemove();
        }

        // Announce the end of a new touch interaction.
        sendAccessibilityEvent(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END);

        // Try to use the standard accessibility API to click
        if (!mAms.performActionOnAccessibilityFocusedItem(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) {
            Slog.e(LOG_TAG, "ACTION_CLICK failed.");
        }
        return true;
    }

    @Override
    public boolean onGestureStarted() {
        // We have to perform gesture detection, so
        // clear the current state and try to detect.
        mState.startGestureDetecting();
        mSendHoverEnterAndMoveDelayed.cancel();
        mSendHoverExitDelayed.cancel();
        mExitGestureDetectionModeDelayed.post();
        // Send accessibility event to announce the start
        // of gesture recognition.
        sendAccessibilityEvent(AccessibilityEvent.TYPE_GESTURE_DETECTION_START);
        return false;
    }

    @Override
    public boolean onGestureCompleted(AccessibilityGestureEvent gestureEvent) {
        if (!mState.isGestureDetecting()) {
            return false;
        }

        endGestureDetection(true);

        mAms.onGesture(gestureEvent);

        return true;
    }

    @Override
    public boolean onGestureCancelled(MotionEvent event, int policyFlags) {
        if (mState.isGestureDetecting()) {
            endGestureDetection(event.getActionMasked() == MotionEvent.ACTION_UP);
            return true;
        } else if (mState.isTouchExploring()) {
            // If the finger is still moving, pass the event on.
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                final int pointerId = mReceivedPointerTracker.getPrimaryPointerId();
                final int pointerIdBits = (1 << pointerId);

                // We have just decided that the user is touch,
                // exploring so start sending events.
                mSendHoverEnterAndMoveDelayed.addEvent(event);
                mSendHoverEnterAndMoveDelayed.forceSendAndRemove();
                mSendHoverExitDelayed.cancel();
                sendMotionEvent(event, MotionEvent.ACTION_HOVER_MOVE, pointerIdBits, policyFlags);
                return true;
            }
        }
        return false;
    }

    /**
     * Handles a motion event in the clear state i.e. no fingers are touching the screen.
     */
    private void handleMotionEventStateClear(
            MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        switch (event.getActionMasked()) {
            // The only way to leave the clear state is for a pointer to go down.
            case MotionEvent.ACTION_DOWN:
                handleActionDown(event, policyFlags);
                break;
            default:
                // Some other nonsensical event.
                break;
        }
    }

    /**
     * Handles ACTION_DOWN while in the clear or touch interacting states. This event represents the
     * first finger touching the screen.
     */
    private void handleActionDown(MotionEvent event, int policyFlags) {
        mAms.onTouchInteractionStart();

        // If we still have not notified the user for the last
        // touch, we figure out what to do. If were waiting
        // we resent the delayed callback and wait again.
        mSendHoverEnterAndMoveDelayed.cancel();
        mSendHoverExitDelayed.cancel();

        // If a touch exploration gesture is in progress send events for its end.
        if (mState.isTouchExploring()) {
            sendHoverExitAndTouchExplorationGestureEndIfNeeded(policyFlags);
        }

        // Avoid duplicated TYPE_TOUCH_INTERACTION_START event when 2nd tap of double
        // tap.
        if (!mGestureDetector.firstTapDetected() && mState.isClear()) {
            mSendTouchExplorationEndDelayed.forceSendAndRemove();
            mSendTouchInteractionEndDelayed.forceSendAndRemove();
            sendAccessibilityEvent(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START);
        } else {
            // Let gesture to handle to avoid duplicated TYPE_TOUCH_INTERACTION_END event.
            mSendTouchInteractionEndDelayed.cancel();
        }

        if (!mGestureDetector.firstTapDetected() && !mState.isTouchExploring()) {
            if (!mSendHoverEnterAndMoveDelayed.isPending()) {
                // Queue a delayed transition to STATE_TOUCH_EXPLORING.
                // If we do not detect that this is a gesture, delegation or drag the transition
                // will fire by default.
                // The idea is to avoid getting stuck in STATE_TOUCH_INTERACTING
                final int pointerId = mReceivedPointerTracker.getPrimaryPointerId();
                final int pointerIdBits = (1 << pointerId);
                mSendHoverEnterAndMoveDelayed.post(event, pointerIdBits, policyFlags);
            } else {
                // Cache the event until we discern exploration from gesturing.
                mSendHoverEnterAndMoveDelayed.addEvent(event);
            }
        }
    }

    /**
     * Handles a motion event in touch interacting state.
     *
     * @param event The event to be handled.
     * @param rawEvent The raw (unmodified) motion event.
     * @param policyFlags The policy flags associated with the event.
     */
    private void handleMotionEventStateTouchInteracting(
            MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Continue the previous interaction.
                mSendTouchInteractionEndDelayed.cancel();
                handleActionDown(event, policyFlags);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                handleActionPointerDown();
                break;
            case MotionEvent.ACTION_MOVE:
                handleActionMoveStateTouchInteracting(event, rawEvent, policyFlags);
                break;
            case MotionEvent.ACTION_UP:
                handleActionUp(event, policyFlags);
                break;
        }
    }

    /**
     * Handles a motion event in touch exploring state.
     *
     * @param event The event to be handled.
     * @param rawEvent The raw (unmodified) motion event.
     * @param policyFlags The policy flags associated with the event.
     */
    private void handleMotionEventStateTouchExploring(
            MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // We should have already received ACTION_DOWN. Ignore.
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                handleActionPointerDown();
                break;
            case MotionEvent.ACTION_MOVE:
                handleActionMoveStateTouchExploring(event, rawEvent, policyFlags);
                break;
            case MotionEvent.ACTION_UP:
                handleActionUp(event, policyFlags);
                break;
            default:
                break;
        }
    }

    /**
     * Handles ACTION_POINTER_DOWN when in the touch exploring state. This event represents an
     * additional finger touching the screen.
     */
    private void handleActionPointerDown() {
        // Another finger down means that if we have not started to deliver
        // hover events, we will not have to. The code for ACTION_MOVE will
        // decide what we will actually do next.
        mSendHoverEnterAndMoveDelayed.cancel();
        mSendHoverExitDelayed.cancel();
    }
    /**
     * Handles ACTION_MOVE while in the touch interacting state. This is where transitions to
     * delegating and dragging states are handled.
     */
    private void handleActionMoveStateTouchInteracting(
            MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        final int pointerId = mReceivedPointerTracker.getPrimaryPointerId();
        final int pointerIndex = event.findPointerIndex(pointerId);
        final int pointerIdBits = (1 << pointerId);
        switch (event.getPointerCount()) {
            case 1:
                // We have not started sending events since we try to
                // figure out what the user is doing.
                if (mSendHoverEnterAndMoveDelayed.isPending()) {
                    // Cache the event until we discern exploration from gesturing.
                    mSendHoverEnterAndMoveDelayed.addEvent(event);
                }
                break;
            case 2:
                // Make sure we don't have any pending transitions to touch exploration
                mSendHoverEnterAndMoveDelayed.cancel();
                mSendHoverExitDelayed.cancel();
                // More than one pointer so the user is not touch exploring
                // and now we have to decide whether to delegate or drag.
                // Remove move history before send injected non-move events
                event = MotionEvent.obtainNoHistory(event);
                if (isDraggingGesture(event)) {
                    // Two pointers moving in the same direction within
                    // a given distance perform a drag.
                    mState.startDragging();
                    mDraggingPointerId = pointerId;
                    event.setEdgeFlags(mReceivedPointerTracker.getLastReceivedDownEdgeFlags());
                    sendMotionEvent(event, MotionEvent.ACTION_DOWN, pointerIdBits, policyFlags);
                } else {
                    // Two pointers moving arbitrary are delegated to the view hierarchy.
                    mState.startDelegating();
                    sendDownForAllNotInjectedPointers(event, policyFlags);
                }
                break;
            default:
                // More than two pointers are delegated to the view hierarchy.
                mState.startDelegating();
                event = MotionEvent.obtainNoHistory(event);
                sendDownForAllNotInjectedPointers(event, policyFlags);
                break;
        }
    }

    /**
     * Handles ACTION_UP while in the touch interacting state. This event represents all fingers
     * being lifted from the screen.
     */
    private void handleActionUp(MotionEvent event, int policyFlags) {
        mAms.onTouchInteractionEnd();
        final int pointerId = event.getPointerId(event.getActionIndex());
        final int pointerIdBits = (1 << pointerId);
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
    }

    /**
     * Handles move events while touch exploring. this is also where we drag or delegate based on
     * the number of fingers moving on the screen.
     */
    private void handleActionMoveStateTouchExploring(
            MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        final int pointerId = mReceivedPointerTracker.getPrimaryPointerId();
        final int pointerIdBits = (1 << pointerId);
        final int pointerIndex = event.findPointerIndex(pointerId);
        switch (event.getPointerCount()) {
            case 1:
            // Touch exploration.
                sendTouchExplorationGestureStartAndHoverEnterIfNeeded(policyFlags);
                sendMotionEvent(event, MotionEvent.ACTION_HOVER_MOVE, pointerIdBits, policyFlags);
                break;
            case 2:
                if (mSendHoverEnterAndMoveDelayed.isPending()) {
                    // We have not started sending events so cancel
                    // scheduled sending events.
                    mSendHoverEnterAndMoveDelayed.cancel();
                    mSendHoverExitDelayed.cancel();
                }
                // If the user is touch exploring the second pointer may be
                // performing a double tap to activate an item without need
                // for the user to lift his exploring finger.
                // It is *important* to use the distance traveled by the pointers
                // on the screen which may or may not be magnified.
                final float deltaX =
                        mReceivedPointerTracker.getReceivedPointerDownX(pointerId)
                                - rawEvent.getX(pointerIndex);
                final float deltaY =
                        mReceivedPointerTracker.getReceivedPointerDownY(pointerId)
                                - rawEvent.getY(pointerIndex);
                final double moveDelta = Math.hypot(deltaX, deltaY);
                if (moveDelta > mDoubleTapSlop) {
                    // The user is trying to either delegate or drag.
                    handleActionMoveStateTouchInteracting(event, rawEvent, policyFlags);
                } else {
                    // Otherwise the double tap will be handled by the gesture detector.
                    sendHoverExitAndTouchExplorationGestureEndIfNeeded(policyFlags);
                }
                break;
            default:
                // Three or more fingers is  something other than touch exploration.
                if (mSendHoverEnterAndMoveDelayed.isPending()) {
                    // We have not started sending events so cancel
                    // scheduled sending events.
                    mSendHoverEnterAndMoveDelayed.cancel();
                    mSendHoverExitDelayed.cancel();
                } else {
                    sendHoverExitAndTouchExplorationGestureEndIfNeeded(policyFlags);
                }
                handleActionMoveStateTouchInteracting(event, rawEvent, policyFlags);
                break;
        }
    }

    /**
     * Handles a motion event in dragging state.
     *
     * @param event The event to be handled.
     * @param policyFlags The policy flags associated with the event.
     */
    private void handleMotionEventStateDragging(MotionEvent event, int policyFlags) {
        int pointerIdBits = 0;
        // Clear the dragging pointer id if it's no longer valid.
        if (event.findPointerIndex(mDraggingPointerId) == -1) {
            Slog.e(LOG_TAG, "mDraggingPointerId doesn't match any pointers on current event. " +
                    "mDraggingPointerId: " + Integer.toString(mDraggingPointerId) +
                    ", Event: " + event);
            mDraggingPointerId = INVALID_POINTER_ID;
        } else {
            pointerIdBits = (1 << mDraggingPointerId);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                Slog.e(LOG_TAG, "Dragging state can be reached only if two "
                        + "pointers are already down");
                clear(event, policyFlags);
                return;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // We are in dragging state so we have two pointers and another one
                // goes down => delegate the three pointers to the view hierarchy
                mState.startDelegating();
                if (mDraggingPointerId != INVALID_POINTER_ID) {
                    sendMotionEvent(event, MotionEvent.ACTION_UP, pointerIdBits, policyFlags);
                }
                sendDownForAllNotInjectedPointers(event, policyFlags);
            } break;
            case MotionEvent.ACTION_MOVE: {
                if (mDraggingPointerId == INVALID_POINTER_ID) {
                    break;
                }
                switch (event.getPointerCount()) {
                    case 1: {
                        // do nothing
                    } break;
                    case 2: {
                        if (isDraggingGesture(event)) {
                            // Adjust event location to the middle location of the two pointers.
                            final float firstPtrX = event.getX(0);
                            final float firstPtrY = event.getY(0);
                            final float secondPtrX = event.getX(1);
                            final float secondPtrY = event.getY(1);
                            final int pointerIndex = event.findPointerIndex(mDraggingPointerId);
                            final float deltaX =
                                    (pointerIndex == 0) ? (secondPtrX - firstPtrX)
                                            : (firstPtrX - secondPtrX);
                            final float deltaY =
                                    (pointerIndex == 0) ? (secondPtrY - firstPtrY)
                                            : (firstPtrY - secondPtrY);
                            event.offsetLocation(deltaX / 2, deltaY / 2);
                            // If still dragging send a drag event.
                            sendMotionEvent(event, MotionEvent.ACTION_MOVE, pointerIdBits,
                                    policyFlags);
                        } else {
                            // The two pointers are moving either in different directions or
                            // no close enough => delegate the gesture to the view hierarchy.
                            mState.startDelegating();
                            // Remove move history before send injected non-move events
                            event = MotionEvent.obtainNoHistory(event);
                            // Send an event to the end of the drag gesture.
                            sendMotionEvent(event, MotionEvent.ACTION_UP, pointerIdBits,
                                    policyFlags);
                            // Deliver all pointers to the view hierarchy.
                            sendDownForAllNotInjectedPointers(event, policyFlags);
                        }
                    } break;
                    default: {
                        mState.startDelegating();
                        event = MotionEvent.obtainNoHistory(event);
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
                Slog.e(LOG_TAG, "Delegating state can only be reached if "
                        + "there is at least one pointer down!");
                clear(event, policyFlags);
                return;
            }
            case MotionEvent.ACTION_UP: {
                // Deliver the event.
                sendMotionEvent(event, event.getAction(), ALL_POINTER_ID_BITS, policyFlags);

                // Announce the end of a the touch interaction.
                mAms.onTouchInteractionEnd();
                sendAccessibilityEvent(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END);

            } break;
            default: {
                // Deliver the event.
                sendMotionEvent(event, event.getAction(), ALL_POINTER_ID_BITS, policyFlags);
            }
        }
    }

    private void endGestureDetection(boolean interactionEnd) {
        mAms.onTouchInteractionEnd();

        // Announce the end of the gesture recognition.
        sendAccessibilityEvent(AccessibilityEvent.TYPE_GESTURE_DETECTION_END);
        // Don't announce the end of a the touch interaction if users didn't lift their fingers.
        if (interactionEnd) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END);
        }

        mExitGestureDetectionModeDelayed.cancel();
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
            if (DEBUG) {
                Slog.d(
                        LOG_TAG,
                        "Sending accessibility event" + AccessibilityEvent.eventTypeToString(type));
            }
        }
        mState.onInjectedAccessibilityEvent(type);
    }

    /**
     * Sends down events to the view hierarchy for all pointers which are
     * not already being delivered i.e. pointers that are not yet injected.
     *
     * @param prototype The prototype from which to create the injected events.
     * @param policyFlags The policy flags associated with the event.
     */
    private void sendDownForAllNotInjectedPointers(MotionEvent prototype, int policyFlags) {

        // Inject the injected pointers.
        int pointerIdBits = 0;
        final int pointerCount = prototype.getPointerCount();
        for (int i = 0; i < pointerCount; i++) {
            final int pointerId = prototype.getPointerId(i);
            // Do not send event for already delivered pointers.
            if (!mInjectedPointerTracker.isInjectedPointerDown(pointerId)) {
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
        int pointerIdBits = 0;
        final int pointerCount = prototype.getPointerCount();
        for (int i = 0; i < pointerCount; i++) {
            final int pointerId = prototype.getPointerId(i);
            // Skip non injected down pointers.
            if (!mInjectedPointerTracker.isInjectedPointerDown(pointerId)) {
                continue;
            }
            pointerIdBits |= (1 << pointerId);
            final int action = computeInjectionAction(MotionEvent.ACTION_UP, i);
            sendMotionEvent(prototype, action, pointerIdBits, policyFlags);
        }
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
            try {
                event = prototype.split(pointerIdBits);
            } catch (IllegalArgumentException e) {
                Slog.e(LOG_TAG, "sendMotionEvent: Failed to split motion event: " + e);
                return;
            }
        }
        if (action == MotionEvent.ACTION_DOWN) {
            event.setDownTime(event.getEventTime());
        } else {
            event.setDownTime(mInjectedPointerTracker.getLastInjectedDownEventTime());
        }
        if (DEBUG) {
            Slog.d(LOG_TAG, "Injecting event: " + event + ", policyFlags=0x"
                    + Integer.toHexString(policyFlags));
        }

        // Make sure that the user will see the event.
        policyFlags |= WindowManagerPolicy.FLAG_PASS_TO_USER;
        // TODO: For now pass null for the raw event since the touch
        //       explorer is the last event transformation and it does
        //       not care about the raw event.
        super.onMotionEvent(event, null, policyFlags);

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
                // Compute the action based on how many down pointers are injected.
                if (mInjectedPointerTracker.getInjectedPointerDownCount() == 0) {
                    return MotionEvent.ACTION_DOWN;
                } else {
                    return (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        | MotionEvent.ACTION_POINTER_DOWN;
                }
            }
            case MotionEvent.ACTION_POINTER_UP: {
                // Compute the action based on how many down pointers are injected.
                if (mInjectedPointerTracker.getInjectedPointerDownCount() == 1) {
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

    /**
     * Determines whether a two pointer gesture is a dragging one.
     *
     * @param event The event with the pointer data.
     * @return True if the gesture is a dragging one.
     */
    private boolean isDraggingGesture(MotionEvent event) {

        final float firstPtrX = event.getX(0);
        final float firstPtrY = event.getY(0);
        final float secondPtrX = event.getX(1);
        final float secondPtrY = event.getY(1);

        final float firstPtrDownX = mReceivedPointerTracker.getReceivedPointerDownX(0);
        final float firstPtrDownY = mReceivedPointerTracker.getReceivedPointerDownY(0);
        final float secondPtrDownX = mReceivedPointerTracker.getReceivedPointerDownX(1);
        final float secondPtrDownY = mReceivedPointerTracker.getReceivedPointerDownY(1);

        return GestureUtils.isDraggingGesture(firstPtrDownX, firstPtrDownY, secondPtrDownX,
                secondPtrDownY, firstPtrX, firstPtrY, secondPtrX, secondPtrY,
                MAX_DRAGGING_ANGLE_COS);
    }

    public TouchState getState() {
        return mState;
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
            clear();
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

        public void post(MotionEvent event, int pointerIdBits, int policyFlags) {
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
        return "TouchExplorer { "
                + "mTouchState: " + mState
                + ", mDetermineUserIntentTimeout: " + mDetermineUserIntentTimeout
                + ", mDoubleTapSlop: " + mDoubleTapSlop
                + ", mDraggingPointerId: " + mDraggingPointerId
                + ", mTempPoint: " + mTempPoint
                + " }";
    }

}
