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

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_HOVER_ENTER;
import static android.view.MotionEvent.ACTION_HOVER_EXIT;
import static android.view.MotionEvent.ACTION_HOVER_MOVE;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_UP;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.MotionEvent.INVALID_POINTER_ID;
import static android.view.accessibility.AccessibilityEvent.TYPE_GESTURE_DETECTION_END;
import static android.view.accessibility.AccessibilityEvent.TYPE_GESTURE_DETECTION_START;
import static android.view.accessibility.AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END;
import static android.view.accessibility.AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START;
import static android.view.accessibility.AccessibilityEvent.TYPE_TOUCH_INTERACTION_END;
import static android.view.accessibility.AccessibilityEvent.TYPE_TOUCH_INTERACTION_START;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_HOVER_EXIT;

import static com.android.server.accessibility.gestures.TouchState.ALL_POINTER_ID_BITS;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Region;
import android.os.Handler;
import android.util.Slog;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accessibility.BaseEventStreamTransformation;
import com.android.server.accessibility.EventStreamTransformation;
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
        implements GestureManifold.Listener {

    static final boolean DEBUG = false;

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
    private final GestureManifold  mGestureDetector;

    // Helper class to track received pointers.
    private final TouchState.ReceivedPointerTracker mReceivedPointerTracker;

    private final EventDispatcher mDispatcher;

    // Handle to the accessibility manager service.
    private final AccessibilityManagerService mAms;

    // Temporary point to avoid instantiation.
    private final Point mTempPoint = new Point();

    // Context in which this explorer operates.
    private final Context mContext;

    private Region mGestureDetectionPassthroughRegion;
    private Region mTouchExplorationPassthroughRegion;

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
     *     action.
     * @param detector The gesture detector to handle accessibility touch event. If null the default
     *     one created in place, or for testing purpose.
     */
    public TouchExplorer(
            Context context, AccessibilityManagerService service, GestureManifold detector) {
        this(context, service, detector, new Handler(context.getMainLooper()));
    }

    @VisibleForTesting
    TouchExplorer(Context context, AccessibilityManagerService service, GestureManifold detector,
            @NonNull Handler mainHandler) {
        mContext = context;
        mAms = service;
        mState = new TouchState();
        mReceivedPointerTracker = mState.getReceivedPointerTracker();
        mDispatcher = new EventDispatcher(context, mAms, super.getNext(), mState);
        mDetermineUserIntentTimeout = ViewConfiguration.getDoubleTapTimeout();
        mDoubleTapSlop = ViewConfiguration.get(context).getScaledDoubleTapSlop();
        mHandler = mainHandler;
        mExitGestureDetectionModeDelayed = new ExitGestureDetectionModeDelayed();
        mSendHoverEnterAndMoveDelayed = new SendHoverEnterAndMoveDelayed();
        mSendHoverExitDelayed = new SendHoverExitDelayed();
        mSendTouchExplorationEndDelayed =
                new SendAccessibilityEventDelayed(
                        TYPE_TOUCH_EXPLORATION_GESTURE_END, mDetermineUserIntentTimeout);
        mSendTouchInteractionEndDelayed =
                new SendAccessibilityEventDelayed(
                        TYPE_TOUCH_INTERACTION_END, mDetermineUserIntentTimeout);
        if (detector == null) {
            mGestureDetector = new GestureManifold(context, this, mState);
        } else {
            mGestureDetector = detector;
        }
        mGestureDetectionPassthroughRegion = new Region();
        mTouchExplorationPassthroughRegion = new Region();
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
        MotionEvent event = mState.getLastReceivedEvent();
        if (event != null) {
            clear(event, WindowManagerPolicy.FLAG_TRUSTED);
        }
    }

    private void clear(MotionEvent event, int policyFlags) {
        if (mState.isTouchExploring()) {
            // If a touch exploration gesture is in progress send events for its end.
            sendHoverExitAndTouchExplorationGestureEndIfNeeded(policyFlags);
        }  else if (mState.isDragging()) {
            mDraggingPointerId = INVALID_POINTER_ID;
            // Send exit to all pointers that we have delivered.
            mDispatcher.sendUpForInjectedDownPointers(event, policyFlags);
        } else if (mState.isDelegating()) {
            // Send exit to all pointers that we have delivered.
            mDispatcher.sendUpForInjectedDownPointers(event, policyFlags);
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

        mState.onReceivedMotionEvent(rawEvent);
        if (shouldPerformGestureDetection(event)) {
            if (mGestureDetector.onMotionEvent(event, rawEvent, policyFlags)) {
                // Event was handled by the gesture detector.
                return;
            }
        }

        if (event.getActionMasked() == ACTION_CANCEL) {
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
            handleMotionEventStateDragging(event, rawEvent, policyFlags);
        } else if (mState.isDelegating()) {
            handleMotionEventStateDelegating(event, rawEvent, policyFlags);
        } else if (mState.isGestureDetecting()) {
            // Make sure we don't prematurely get TOUCH_INTERACTION_END
            // It will be delivered on gesture completion or cancelation.
            // Note that the delay for sending GESTURE_DETECTION_END remains in place.
            mSendTouchInteractionEndDelayed.cancel();
        } else {
            Slog.e(LOG_TAG, "Illegal state: " + mState);
                clear(event, policyFlags);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        final int eventType = event.getEventType();

        if (eventType == TYPE_VIEW_HOVER_EXIT) {
            sendsPendingA11yEventsIfNeeded();
        }
        super.onAccessibilityEvent(event);
    }

    /*
     * Sends pending {@link AccessibilityEvent#TYPE_TOUCH_EXPLORATION_GESTURE_END} or {@{@link
     * AccessibilityEvent#TYPE_TOUCH_EXPLORATION_GESTURE_END}} after receiving last hover exit
     * event.
     */
    private void sendsPendingA11yEventsIfNeeded() {
        // The last hover exit A11y event should be sent by view after receiving hover exit motion
        // event. In some view hierarchy, the ViewGroup transforms hover move motion event to hover
        // exit motion event and than dispatch to itself. It causes unexpected A11y exit events.
        if (mSendHoverExitDelayed.isPending()) {
            return;
        }
        // The event for gesture end should be strictly after the
        // last hover exit event.
        if (mSendTouchExplorationEndDelayed.isPending()) {
            mSendTouchExplorationEndDelayed.cancel();
            mDispatcher.sendAccessibilityEvent(TYPE_TOUCH_EXPLORATION_GESTURE_END);
        }

        // The event for touch interaction end should be strictly after the
        // last hover exit and the touch exploration gesture end events.
        if (mSendTouchInteractionEndDelayed.isPending()) {
            mSendTouchInteractionEndDelayed.cancel();
            mDispatcher.sendAccessibilityEvent(TYPE_TOUCH_INTERACTION_END);
        }
    }

    @Override
    public void onDoubleTapAndHold() {
        // Try to use the standard accessibility API to long click
        if (!mAms.performActionOnAccessibilityFocusedItem(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)) {
            Slog.e(LOG_TAG, "ACTION_LONG_CLICK failed.");
        }
    }

    @Override
    public boolean onDoubleTap() {
        mAms.onTouchInteractionEnd();
        // Remove pending event deliveries.
        mSendHoverEnterAndMoveDelayed.cancel();
        mSendHoverExitDelayed.cancel();

        if (mSendTouchExplorationEndDelayed.isPending()) {
            mSendTouchExplorationEndDelayed.forceSendAndRemove();
        }

        // Announce the end of a new touch interaction.
        mDispatcher.sendAccessibilityEvent(TYPE_TOUCH_INTERACTION_END);
        mSendTouchInteractionEndDelayed.cancel();
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
        mDispatcher.sendAccessibilityEvent(TYPE_GESTURE_DETECTION_START);
        return false;
    }

    @Override
    public boolean onGestureCompleted(AccessibilityGestureEvent gestureEvent) {
        endGestureDetection(true);
        mSendTouchInteractionEndDelayed.cancel();
        mAms.onGesture(gestureEvent);

        return true;
    }

    @Override
    public boolean onGestureCancelled(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (mState.isGestureDetecting()) {
            endGestureDetection(event.getActionMasked() == ACTION_UP);
            return true;
        } else if (mState.isTouchExploring()) {
            // If the finger is still moving, pass the event on.
            if (event.getActionMasked() == ACTION_MOVE) {
                final int pointerId = mReceivedPointerTracker.getPrimaryPointerId();
                final int pointerIdBits = (1 << pointerId);

                // We have just decided that the user is touch,
                // exploring so start sending events.
                mSendHoverEnterAndMoveDelayed.addEvent(event, mState.getLastReceivedEvent());
                mSendHoverEnterAndMoveDelayed.forceSendAndRemove();
                mSendHoverExitDelayed.cancel();
                mDispatcher.sendMotionEvent(
                        event,
                        ACTION_HOVER_MOVE,
                        mState.getLastReceivedEvent(),
                        pointerIdBits,
                        policyFlags);
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
            case ACTION_DOWN:
                handleActionDown(event, rawEvent, policyFlags);
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
    private void handleActionDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
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

        if (mState.isClear()) {
            if (!mSendHoverEnterAndMoveDelayed.isPending()) {
                // Queue a delayed transition to STATE_TOUCH_EXPLORING.
                // If we do not detect that this is a gesture, delegation or drag the transition
                // will fire by default.
                // The idea is to avoid getting stuck in STATE_TOUCH_INTERACTING
                final int pointerId = mReceivedPointerTracker.getPrimaryPointerId();
                final int pointerIdBits = (1 << pointerId);
                mSendHoverEnterAndMoveDelayed.post(event, rawEvent, pointerIdBits, policyFlags);
            } else {
                // Cache the event until we discern exploration from gesturing.
                mSendHoverEnterAndMoveDelayed.addEvent(event, rawEvent);
            }
            mSendTouchExplorationEndDelayed.forceSendAndRemove();
            mSendTouchInteractionEndDelayed.forceSendAndRemove();
            mDispatcher.sendAccessibilityEvent(TYPE_TOUCH_INTERACTION_START);
            if (mTouchExplorationPassthroughRegion.contains(
                    (int) event.getX(), (int) event.getY())) {
                // The touch exploration passthrough overrides the gesture detection passthrough in
                // the event they overlap.
                // Pass this entire gesture through to the system as-is.
                mState.startDelegating();
                event = MotionEvent.obtainNoHistory(event);
                mDispatcher.sendMotionEvent(
                        event, event.getAction(), rawEvent, ALL_POINTER_ID_BITS, policyFlags);
                mSendHoverEnterAndMoveDelayed.cancel();
            } else if (mGestureDetectionPassthroughRegion.contains(
                    (int) event.getX(), (int) event.getY())) {
                // Jump straight to touch exploration.
                mSendHoverEnterAndMoveDelayed.forceSendAndRemove();
            }
        } else {
            // Avoid duplicated TYPE_TOUCH_INTERACTION_START event when 2nd tap of double tap.
            mSendTouchInteractionEndDelayed.cancel();
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
            case ACTION_DOWN:
                // Continue the previous interaction.
                mSendTouchInteractionEndDelayed.cancel();
                handleActionDown(event, rawEvent, policyFlags);
                break;
            case ACTION_POINTER_DOWN:
                handleActionPointerDown(event, rawEvent, policyFlags);
                break;
            case ACTION_MOVE:
                handleActionMoveStateTouchInteracting(event, rawEvent, policyFlags);
                break;
            case ACTION_UP:
                handleActionUp(event, rawEvent, policyFlags);
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
            case ACTION_DOWN:
                // We should have already received ACTION_DOWN. Ignore.
                break;
            case ACTION_POINTER_DOWN:
                handleActionPointerDown(event, rawEvent, policyFlags);
                break;
            case ACTION_MOVE:
                handleActionMoveStateTouchExploring(event, rawEvent, policyFlags);
                break;
            case ACTION_UP:
                handleActionUp(event, rawEvent, policyFlags);
                break;
            default:
                break;
        }
    }

    /**
     * Handles ACTION_POINTER_DOWN when in the touch exploring state. This event represents an
     * additional finger touching the screen.
     */
    private void handleActionPointerDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // Another finger down means that if we have not started to deliver
        // hover events, we will not have to. The code for ACTION_MOVE will
        // decide what we will actually do next.

        if (mSendHoverEnterAndMoveDelayed.isPending()) {
            mSendHoverEnterAndMoveDelayed.cancel();
            mSendHoverExitDelayed.cancel();
        } else {
            // We have already delivered at least one hover event, so send hover exit to keep the
            // stream consistent.
            sendHoverExitAndTouchExplorationGestureEndIfNeeded(policyFlags);
        }
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
                    mSendHoverEnterAndMoveDelayed.addEvent(event, rawEvent);
                }
                break;
            case 2:
                if (mGestureDetector.isMultiFingerGesturesEnabled()) {
                    return;
                }
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
                    mDispatcher.sendMotionEvent(
                            event, ACTION_DOWN, rawEvent, pointerIdBits, policyFlags);
                } else {
                    // Two pointers moving arbitrary are delegated to the view hierarchy.
                    mState.startDelegating();
                    mDispatcher.sendDownForAllNotInjectedPointers(event, policyFlags);
                }
                break;
            default:
                if (mGestureDetector.isMultiFingerGesturesEnabled()) {
                    return;
                }
                // More than two pointers are delegated to the view hierarchy.
                mState.startDelegating();
                event = MotionEvent.obtainNoHistory(event);
                mDispatcher.sendDownForAllNotInjectedPointers(event, policyFlags);
                break;
        }
    }

    /**
     * Handles ACTION_UP while in the touch interacting state. This event represents all fingers
     * being lifted from the screen.
     */
    private void handleActionUp(MotionEvent event,  MotionEvent rawEvent, int policyFlags) {
        mAms.onTouchInteractionEnd();
        final int pointerId = event.getPointerId(event.getActionIndex());
        final int pointerIdBits = (1 << pointerId);
        if (mSendHoverEnterAndMoveDelayed.isPending()) {
            // If we have not delivered the enter schedule an exit.
            mSendHoverExitDelayed.post(event, rawEvent, pointerIdBits, policyFlags);
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
                mDispatcher.sendMotionEvent(
                        event, ACTION_HOVER_MOVE, rawEvent, pointerIdBits, policyFlags);
                break;
            case 2:
                if (mGestureDetector.isMultiFingerGesturesEnabled()) {
                    return;
                }
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
                if (mGestureDetector.isMultiFingerGesturesEnabled()) {
                    return;
                }
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
    private void handleMotionEventStateDragging(
            MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (mGestureDetector.isMultiFingerGesturesEnabled()) {
            // Multi-finger gestures conflict with this functionality.
            return;
        }
        int pointerIdBits = 0;
        // Clear the dragging pointer id if it's no longer valid.
        if (event.findPointerIndex(mDraggingPointerId) == -1) {
            Slog.e(
                    LOG_TAG,
                    "mDraggingPointerId doesn't match any pointers on current event. "
                            + "mDraggingPointerId: "
                            + Integer.toString(mDraggingPointerId)
                            + ", Event: "
                            + event);
            mDraggingPointerId = INVALID_POINTER_ID;
        } else {
            pointerIdBits = (1 << mDraggingPointerId);
        }
        switch (event.getActionMasked()) {
            case ACTION_DOWN:
                Slog.e(
                        LOG_TAG,
                        "Dragging state can be reached only if two " + "pointers are already down");
                clear(event, policyFlags);
                return;
            case ACTION_POINTER_DOWN:
                // We are in dragging state so we have two pointers and another one
                // goes down => delegate the three pointers to the view hierarchy
                mState.startDelegating();
                if (mDraggingPointerId != INVALID_POINTER_ID) {
                    mDispatcher.sendMotionEvent(
                            event, ACTION_UP, rawEvent, pointerIdBits, policyFlags);
                }
                mDispatcher.sendDownForAllNotInjectedPointers(event, policyFlags);
                break;
            case ACTION_MOVE:
                if (mDraggingPointerId == INVALID_POINTER_ID) {
                    break;
                }
                switch (event.getPointerCount()) {
                    case 1:
                        // do nothing
                        break;
                    case 2:
                        if (isDraggingGesture(event)) {
                            // If still dragging send a drag event.
                            adjustEventLocationForDrag(event);
                            mDispatcher.sendMotionEvent(
                                    event, ACTION_MOVE, rawEvent, pointerIdBits, policyFlags);
                        } else {
                            // The two pointers are moving either in different directions or
                            // no close enough => delegate the gesture to the view hierarchy.
                            mState.startDelegating();
                            // Remove move history before send injected non-move events
                            event = MotionEvent.obtainNoHistory(event);
                            // Send an event to the end of the drag gesture.
                            mDispatcher.sendMotionEvent(
                                    event, ACTION_UP, rawEvent, pointerIdBits, policyFlags);
                            // Deliver all pointers to the view hierarchy.
                            mDispatcher.sendDownForAllNotInjectedPointers(event, policyFlags);
                        }
                        break;
                    default:
                        mState.startDelegating();
                        event = MotionEvent.obtainNoHistory(event);
                        // Send an event to the end of the drag gesture.
                        mDispatcher.sendMotionEvent(
                                event, ACTION_UP, rawEvent, pointerIdBits, policyFlags);
                        // Deliver all pointers to the view hierarchy.
                        mDispatcher.sendDownForAllNotInjectedPointers(event, policyFlags);
                }
                break;
            case ACTION_POINTER_UP:
                if (event.getPointerId(GestureUtils.getActionIndex(event)) == mDraggingPointerId) {
                    mDraggingPointerId = INVALID_POINTER_ID;
                    // Send an event to the end of the drag gesture.
                    mDispatcher.sendMotionEvent(
                            event, ACTION_UP, rawEvent, pointerIdBits, policyFlags);
                }
                break;
            case ACTION_UP:
                mAms.onTouchInteractionEnd();
                // Announce the end of a new touch interaction.
                mDispatcher.sendAccessibilityEvent(TYPE_TOUCH_INTERACTION_END);
                if (event.getPointerId(GestureUtils.getActionIndex(event)) == mDraggingPointerId) {
                    mDraggingPointerId = INVALID_POINTER_ID;
                    // Send an event to the end of the drag gesture.
                    mDispatcher.sendMotionEvent(
                            event, ACTION_UP, rawEvent, pointerIdBits, policyFlags);
                }
                break;
        }
    }

    /**
     * Handles a motion event in delegating state.
     *
     * @param event The event to be handled.
     * @param policyFlags The policy flags associated with the event.
     */
    private void handleMotionEventStateDelegating(
            MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (mGestureDetector.isMultiFingerGesturesEnabled()) {
            // Multi-finger gestures conflict with this functionality.
            return;
        }
        switch (event.getActionMasked()) {
            case ACTION_DOWN: {
                Slog.e(LOG_TAG, "Delegating state can only be reached if "
                        + "there is at least one pointer down!");
                clear(event, policyFlags);
                return;
            }
            case ACTION_UP: {
                // Deliver the event.
                mDispatcher.sendMotionEvent(
                        event, event.getAction(), rawEvent, ALL_POINTER_ID_BITS, policyFlags);

                // Announce the end of a the touch interaction.
                mAms.onTouchInteractionEnd();
                mDispatcher.sendAccessibilityEvent(TYPE_TOUCH_INTERACTION_END);

            } break;
            default: {
                    // Deliver the event.
                mDispatcher.sendMotionEvent(
                        event, event.getAction(), rawEvent, ALL_POINTER_ID_BITS, policyFlags);
            }
        }
    }

    private void endGestureDetection(boolean interactionEnd) {
        mAms.onTouchInteractionEnd();

        // Announce the end of the gesture recognition.
        mDispatcher.sendAccessibilityEvent(TYPE_GESTURE_DETECTION_END);
        // Don't announce the end of a the touch interaction if users didn't lift their fingers.
        if (interactionEnd) {
            mDispatcher.sendAccessibilityEvent(TYPE_TOUCH_INTERACTION_END);
        }

        mExitGestureDetectionModeDelayed.cancel();
    }


    /**
     * Sends the exit events if needed. Such events are hover exit and touch explore
     * gesture end.
     *
     * @param policyFlags The policy flags associated with the event.
     */
    private void sendHoverExitAndTouchExplorationGestureEndIfNeeded(int policyFlags) {
        MotionEvent event = mDispatcher.getLastInjectedHoverEvent();
        if (event != null && event.getActionMasked() != ACTION_HOVER_EXIT) {
            final int pointerIdBits = event.getPointerIdBits();
            if (!mSendTouchExplorationEndDelayed.isPending()) {
                mSendTouchExplorationEndDelayed.post();
            }
            mDispatcher.sendMotionEvent(
                    event,
                    ACTION_HOVER_EXIT,
                    mState.getLastReceivedEvent(),
                    pointerIdBits,
                    policyFlags);
        }
    }

    /**
     * Sends the enter events if needed. Such events are hover enter and touch explore
     * gesture start.
     *
     * @param policyFlags The policy flags associated with the event.
     */
    private void sendTouchExplorationGestureStartAndHoverEnterIfNeeded(int policyFlags) {
        MotionEvent event = mDispatcher.getLastInjectedHoverEvent();
        if (event != null && event.getActionMasked() == ACTION_HOVER_EXIT) {
            final int pointerIdBits = event.getPointerIdBits();
            mDispatcher.sendMotionEvent(
                    event,
                    ACTION_HOVER_ENTER,
                    mState.getLastReceivedEvent(),
                    pointerIdBits,
                    policyFlags);
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

    /**
     * Adjust the location of an injected event when performing a drag The new location will be in
     * between the two fingers touching the screen.
     */
    private void adjustEventLocationForDrag(MotionEvent event) {

        final float firstPtrX = event.getX(0);
        final float firstPtrY = event.getY(0);
        final float secondPtrX = event.getX(1);
        final float secondPtrY = event.getY(1);
        final int pointerIndex = event.findPointerIndex(mDraggingPointerId);
        final float deltaX =
                (pointerIndex == 0) ? (secondPtrX - firstPtrX) : (firstPtrX - secondPtrX);
        final float deltaY =
                (pointerIndex == 0) ? (secondPtrY - firstPtrY) : (firstPtrY - secondPtrY);
        event.offsetLocation(deltaX / 2, deltaY / 2);
    }

    public TouchState getState() {
        return mState;
    }

    @Override
    public void setNext(EventStreamTransformation next) {
        mDispatcher.setReceiver(next);
        super.setNext(next);
    }

    /**
     * Whether to dispatch double tap and double tap and hold to the service rather than handle them
     * in the framework.
     */
    public void setServiceHandlesDoubleTap(boolean mode) {
        mGestureDetector.setServiceHandlesDoubleTap(mode);
    }

    /**
     * This function turns on and off multi-finger gestures. When enabled, multi-finger gestures
     * will disable delegating and dragging functionality.
     */
    public void setMultiFingerGesturesEnabled(boolean enabled) {
        mGestureDetector.setMultiFingerGesturesEnabled(enabled);
    }

    public void setGestureDetectionPassthroughRegion(Region region) {
        mGestureDetectionPassthroughRegion = region;
    }

    public void setTouchExplorationPassthroughRegion(Region region) {
        mTouchExplorationPassthroughRegion = region;
    }

    private boolean shouldPerformGestureDetection(MotionEvent event) {
        if (mState.isDelegating()) {
            return false;
        }
        if (event.getActionMasked() == ACTION_DOWN) {
            final int x = (int) event.getX();
            final int y = (int) event.getY();
            if (mTouchExplorationPassthroughRegion.contains(x, y)
                    || mGestureDetectionPassthroughRegion.contains(x, y)) {
                return false;
            }
        }
        return true;
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
            mDispatcher.sendAccessibilityEvent(TYPE_GESTURE_DETECTION_END);
            clear();
        }
    }

    /**
     * Class for delayed sending of hover enter and move events.
     */
    class SendHoverEnterAndMoveDelayed implements Runnable {
        private final String LOG_TAG_SEND_HOVER_DELAYED = "SendHoverEnterAndMoveDelayed";

        private final List<MotionEvent> mEvents = new ArrayList<MotionEvent>();
        private final List<MotionEvent> mRawEvents = new ArrayList<MotionEvent>();

        private int mPointerIdBits;
        private int mPolicyFlags;

        public void post(
                MotionEvent event, MotionEvent rawEvent, int pointerIdBits, int policyFlags) {
            cancel();
            addEvent(event, rawEvent);
            mPointerIdBits = pointerIdBits;
            mPolicyFlags = policyFlags;
            mHandler.postDelayed(this, mDetermineUserIntentTimeout);
        }

        public void addEvent(MotionEvent event, MotionEvent rawEvent) {
            mEvents.add(MotionEvent.obtain(event));
            mRawEvents.add(MotionEvent.obtain(rawEvent));
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
            final int rawEventcount = mRawEvents.size();
            for (int i = rawEventcount - 1; i >= 0; i--) {
                mRawEvents.remove(i).recycle();
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
            mDispatcher.sendAccessibilityEvent(TYPE_TOUCH_EXPLORATION_GESTURE_START);

            if (!mEvents.isEmpty() && !mRawEvents.isEmpty()) {
                // Deliver a down event.
                mDispatcher.sendMotionEvent(mEvents.get(0), ACTION_HOVER_ENTER,
                        mRawEvents.get(0), mPointerIdBits, mPolicyFlags);
                if (DEBUG) {
                    Slog.d(LOG_TAG_SEND_HOVER_DELAYED,
                            "Injecting motion event: ACTION_HOVER_ENTER");
                }

                // Deliver move events.
                final int eventCount = mEvents.size();
                for (int i = 1; i < eventCount; i++) {
                    mDispatcher.sendMotionEvent(mEvents.get(i), ACTION_HOVER_MOVE,
                            mRawEvents.get(i), mPointerIdBits, mPolicyFlags);
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
        private MotionEvent mRawEvent;
        private int mPointerIdBits;
        private int mPolicyFlags;

        public void post(
                MotionEvent prototype, MotionEvent rawEvent, int pointerIdBits, int policyFlags) {
            cancel();
            mPrototype = MotionEvent.obtain(prototype);
            mRawEvent = MotionEvent.obtain(rawEvent);
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
            if (mPrototype != null) {
                mPrototype.recycle();
            }
            if (mRawEvent != null) {
                mRawEvent.recycle();
            }
            mPrototype = null;
            mRawEvent = null;
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
            mDispatcher.sendMotionEvent(
                    mPrototype,
                    ACTION_HOVER_EXIT,
                    mRawEvent,
                    mPointerIdBits,
                    mPolicyFlags);
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
            mDispatcher.sendAccessibilityEvent(mEventType);
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
