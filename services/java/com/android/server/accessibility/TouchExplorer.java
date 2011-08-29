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

import static android.view.accessibility.AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END;
import static android.view.accessibility.AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START;

import android.content.Context;
import android.os.Handler;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManagerPolicy;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import com.android.server.accessibility.AccessibilityInputFilter.Explorer;
import com.android.server.wm.InputFilter;

import java.util.Arrays;

/**
 * This class is a strategy for performing touch exploration. It
 * transforms the motion event stream by modifying, adding, replacing,
 * and consuming certain events. The interaction model is:
 *
 * <ol>
 *   <li>1. One finger moving around performs touch exploration.</li>
 *   <li>2. Two close fingers moving in the same direction perform a drag.</li>
 *   <li>3. Multi-finger gestures are delivered to view hierarchy.</li>
 *   <li>4. Pointers that have not moved more than a specified distance after they
 *          went down are considered inactive.</li>
 *   <li>5. Two fingers moving too far from each other or in different directions
 *          are considered a multi-finger gesture.</li>
 *   <li>6. Tapping on the last touch explored location within given time and
 *          distance slop performs a click.</li>
 *   <li>7. Tapping and holding for a while on the last touch explored location within
 *          given time and distance slop performs a long press.</li>
 * <ol>
 *
 * @hide
 */
public class TouchExplorer implements Explorer {
    private static final boolean DEBUG = false;

    // Tag for logging received events.
    private static final String LOG_TAG_RECEIVED = "TouchExplorer-RECEIVED";
    // Tag for logging injected events.
    private static final String LOG_TAG_INJECTED = "TouchExplorer-INJECTED";
    // Tag for logging the current state.
    private static final String LOG_TAG_STATE = "TouchExplorer-STATE";

    // States this explorer can be in.
    private static final int STATE_TOUCH_EXPLORING = 0x00000001;
    private static final int STATE_DRAGGING = 0x00000002;
    private static final int STATE_DELEGATING = 0x00000004;

    // Invalid pointer ID.
    private static final int INVALID_POINTER_ID = -1;

    // The time slop in milliseconds for activating an item after it has
    // been touch explored. Tapping on an item within this slop will perform
    // a click and tapping and holding down a long press.
    private static final long ACTIVATION_TIME_SLOP = 2000;

    // This constant captures the current implementation detail that
    // pointer IDs are between 0 and 31 inclusive (subject to change).
    // (See MAX_POINTER_ID in frameworks/base/include/ui/Input.h)
    private static final int MAX_POINTER_COUNT = 32;

    // The minimum of the cosine between the vectors of two moving
    // pointers so they can be considered moving in the same direction.
    private static final float MIN_ANGLE_COS = 0.866025404f; // cos(pi/6)

    // The delay for sending a hover enter event.
    private static final long DELAY_SEND_HOVER_ENTER = 200;

    // Constant referring to the ids bits of all pointers.
    private static final int ALL_POINTER_ID_BITS = 0xFFFFFFFF;

    // Temporary array for storing pointer IDs.
    private final int[] mTempPointerIds = new int[MAX_POINTER_COUNT];

    // The distance from the last touch explored location tapping within
    // which would perform a click and tapping and holding a long press.
    private final int mTouchExplorationTapSlop;

    // The InputFilter this tracker is associated with i.e. the filter
    // which delegates event processing to this touch explorer.
    private final InputFilter mInputFilter;

    // Helper class for tracking pointers on the screen, for example which
    // pointers are down, which are active, etc.
    private final PointerTracker mPointerTracker;

    // Handle to the accessibility manager for firing accessibility events
    // announcing touch exploration gesture start and end.
    private final AccessibilityManager mAccessibilityManager;

    // The last event that was received while performing touch exploration.
    private MotionEvent mLastTouchExploreEvent;

    // The current state of the touch explorer.
    private int mCurrentState = STATE_TOUCH_EXPLORING;

    // Flag whether a touch exploration gesture is in progress.
    private boolean mTouchExploreGestureInProgress;

    // The ID of the pointer used for dragging.
    private int mDraggingPointerId;

    // Handler for performing asynchronous operations.
    private final Handler mHandler;

    // Command for delayed sending of a hover event.
    private final SendHoverDelayed mSendHoverDelayed;

    // Command for delayed sending of a long press.
    private final PerformLongPressDelayed mPerformLongPressDelayed;

    /**
     * Creates a new instance.
     *
     * @param inputFilter The input filter associated with this explorer.
     * @param context A context handle for accessing resources.
     */
    public TouchExplorer(InputFilter inputFilter, Context context) {
        mInputFilter = inputFilter;
        mTouchExplorationTapSlop =
            ViewConfiguration.get(context).getScaledTouchExplorationTapSlop();
        mPointerTracker = new PointerTracker(context);
        mHandler = new Handler(context.getMainLooper());
        mSendHoverDelayed = new SendHoverDelayed();
        mPerformLongPressDelayed = new PerformLongPressDelayed();
        mAccessibilityManager = AccessibilityManager.getInstance(context);
    }

    public void clear(MotionEvent event, int policyFlags) {
        sendUpForInjectedDownPointers(event, policyFlags);
        clear();
    }

    /**
     * {@inheritDoc}
     */
    public void onMotionEvent(MotionEvent event, int policyFlags) {
        if (DEBUG) {
            Slog.d(LOG_TAG_RECEIVED, "Received event: " + event + ", policyFlags=0x"
                    + Integer.toHexString(policyFlags));
            Slog.d(LOG_TAG_STATE, getStateSymbolicName(mCurrentState));
        }

        // Keep track of the pointers's state.
        mPointerTracker.onReceivedMotionEvent(event);

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
            default: {
                throw new IllegalStateException("Illegal state: " + mCurrentState);
            }
        }
    }

    /**
     * Handles a motion event in touch exploring state.
     *
     * @param event The event to be handled.
     * @param policyFlags The policy flags associated with the event.
     */
    private void handleMotionEventStateTouchExploring(MotionEvent event, int policyFlags) {
        PointerTracker pointerTracker = mPointerTracker;
        final int activePointerCount = pointerTracker.getActivePointerCount();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                switch (activePointerCount) {
                    case 0: {
                        throw new IllegalStateException("The must always be one active pointer in"
                                + "touch exploring state!");
                    }
                    case 1: {
                        mSendHoverDelayed.remove();
                        // Send a hover for every finger down so the user gets feedback.
                        final int pointerId = pointerTracker.getPrimaryActivePointerId();
                        final int pointerIdBits = (1 << pointerId);
                        final int lastAction = pointerTracker.getLastInjectedHoverAction();

                        // Deliver hover enter with a delay to have a change to detect
                        // whether the user actually starts a scrolling gesture.
                        if (lastAction == MotionEvent.ACTION_HOVER_EXIT) {
                            mSendHoverDelayed.post(event, MotionEvent.ACTION_HOVER_ENTER,
                                    pointerIdBits, policyFlags, DELAY_SEND_HOVER_ENTER);
                        } else {
                            sendMotionEvent(event, MotionEvent.ACTION_HOVER_MOVE, pointerIdBits,
                                    policyFlags);
                        }

                        if (mLastTouchExploreEvent == null) {
                            break;
                        }

                        // If more pointers down on the screen since the last touch
                        // exploration we discard the last cached touch explore event.
                        if (event.getPointerCount() != mLastTouchExploreEvent.getPointerCount()) {
                            mLastTouchExploreEvent = null;
                            break;
                        }

                        // If the down is in the time slop => schedule a long press.
                        final long pointerDownTime =
                            pointerTracker.getReceivedPointerDownTime(pointerId);
                        final long lastExploreTime = mLastTouchExploreEvent.getEventTime();
                        final long deltaTimeExplore = pointerDownTime - lastExploreTime;
                        if (deltaTimeExplore <= ACTIVATION_TIME_SLOP) {
                            mPerformLongPressDelayed.post(event, policyFlags,
                                    ViewConfiguration.getLongPressTimeout());
                            break;
                        }
                    } break;
                    default: {
                        /* do nothing - let the code for ACTION_MOVE decide what to do */
                    } break;
                }
            } break;
            case MotionEvent.ACTION_MOVE: {
                final int pointerId = pointerTracker.getPrimaryActivePointerId();
                final int pointerIndex = event.findPointerIndex(pointerId);
                final int pointerIdBits = (1 << pointerId);
                switch (activePointerCount) {
                    case 0: {
                        /* do nothing - no active pointers so we swallow the event */
                    } break;
                    case 1: {
                        // Detect touch exploration gesture start by having one active pointer
                        // that moved more than a given distance.
                        if (!mTouchExploreGestureInProgress) {
                            final float deltaX = pointerTracker.getReceivedPointerDownX(pointerId)
                                - event.getX(pointerIndex);
                            final float deltaY = pointerTracker.getReceivedPointerDownY(pointerId)
                                - event.getY(pointerIndex);
                            final double moveDelta = Math.hypot(deltaX, deltaY);

                            if (moveDelta > mTouchExplorationTapSlop) {
                                mTouchExploreGestureInProgress = true;
                                sendAccessibilityEvent(TYPE_TOUCH_EXPLORATION_GESTURE_START);
                                // Make sure the scheduled down/move event is sent.
                                mSendHoverDelayed.forceSendAndRemove();
                                mPerformLongPressDelayed.remove();
                                // If we have transitioned to exploring state from another one
                                // we need to send a hover enter event here.
                                final int lastAction = mPointerTracker.getLastInjectedHoverAction();
                                if (lastAction == MotionEvent.ACTION_HOVER_EXIT) {
                                    sendMotionEvent(event, MotionEvent.ACTION_HOVER_ENTER,
                                            pointerIdBits, policyFlags);
                                }
                                sendMotionEvent(event, MotionEvent.ACTION_HOVER_MOVE, pointerIdBits,
                                        policyFlags);
                            }
                        } else {
                            // Touch exploration gesture in progress so send a hover event.
                            sendMotionEvent(event, MotionEvent.ACTION_HOVER_MOVE, pointerIdBits,
                                    policyFlags);
                        }

                        // If the exploring pointer moved enough => cancel the long press.
                        if (!mTouchExploreGestureInProgress && mLastTouchExploreEvent != null
                                && mPerformLongPressDelayed.isPenidng()) {

                            // If the pointer moved more than the tap slop => cancel long press.
                            final float deltaX = mLastTouchExploreEvent.getX(pointerIndex)
                                    - event.getX(pointerIndex);
                            final float deltaY = mLastTouchExploreEvent.getY(pointerIndex)
                                    - event.getY(pointerIndex);
                            final float moveDelta = (float) Math.hypot(deltaX, deltaY);
                            if (moveDelta > mTouchExplorationTapSlop) {
                                mLastTouchExploreEvent = null;
                                mPerformLongPressDelayed.remove();
                                break;
                            }
                        }
                    } break;
                    case 2: {
                        mSendHoverDelayed.remove();
                        mPerformLongPressDelayed.remove();
                        // We want to no longer hover over the location so subsequent
                        // touch at the same spot will generate a hover enter.
                        ensureHoverExitSent(event, pointerIdBits, policyFlags);

                        if (isDraggingGesture(event)) {
                            // Two pointers moving in the same direction within
                            // a given distance perform a drag.
                            mCurrentState = STATE_DRAGGING;
                            if (mTouchExploreGestureInProgress) {
                                sendAccessibilityEvent(TYPE_TOUCH_EXPLORATION_GESTURE_END);
                                mTouchExploreGestureInProgress = false;
                            }
                            mLastTouchExploreEvent = null;
                            mDraggingPointerId = pointerId;
                            sendMotionEvent(event, MotionEvent.ACTION_DOWN, pointerIdBits,
                                    policyFlags);
                        } else {
                            // Two pointers moving arbitrary are delegated to the view hierarchy.
                            mCurrentState = STATE_DELEGATING;
                            mSendHoverDelayed.remove();
                            if (mTouchExploreGestureInProgress) {
                                sendAccessibilityEvent(TYPE_TOUCH_EXPLORATION_GESTURE_END);
                                mTouchExploreGestureInProgress = false;
                            }
                            mLastTouchExploreEvent = null;
                            sendDownForAllActiveNotInjectedPointers(event, policyFlags);
                        }
                    } break;
                    default: {
                        mSendHoverDelayed.remove();
                        mPerformLongPressDelayed.remove();
                        // We want to no longer hover over the location so subsequent
                        // touch at the same spot will generate a hover enter.
                        ensureHoverExitSent(event, pointerIdBits, policyFlags);

                        // More than two pointers are delegated to the view hierarchy.
                        mCurrentState = STATE_DELEGATING;
                        mSendHoverDelayed.remove();
                        if (mTouchExploreGestureInProgress) {
                            sendAccessibilityEvent(TYPE_TOUCH_EXPLORATION_GESTURE_END);
                            mTouchExploreGestureInProgress = false;
                        }
                        mLastTouchExploreEvent = null;
                        sendDownForAllActiveNotInjectedPointers(event, policyFlags);
                    }
                }
            } break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                final int pointerId = pointerTracker.getLastReceivedUpPointerId();
                final int pointerIdBits = (1 << pointerId);
                switch (activePointerCount) {
                    case 0: {
                        // If the pointer that went up was not active we have nothing to do.
                        if (!pointerTracker.wasLastReceivedUpPointerActive()) {
                            break;
                        }

                        mPerformLongPressDelayed.remove();
                        mSendHoverDelayed.forceSendAndRemove();
                        ensureHoverExitSent(event, pointerIdBits, policyFlags);

                        // If touch exploring announce the end of the gesture.
                        // Also do not click on the last explored location.
                        if (mTouchExploreGestureInProgress) {
                            mTouchExploreGestureInProgress = false;
                            mLastTouchExploreEvent = MotionEvent.obtain(event);
                            sendAccessibilityEvent(TYPE_TOUCH_EXPLORATION_GESTURE_END);
                            break;
                        }

                        // Detect whether to activate i.e. click on the last explored location.
                        if (mLastTouchExploreEvent != null) {
                            // If the down was not in the time slop => nothing else to do.
                            final long eventTime =
                                pointerTracker.getLastReceivedUpPointerDownTime();
                            final long exploreTime = mLastTouchExploreEvent.getEventTime();
                            final long deltaTime = eventTime - exploreTime;
                            if (deltaTime > ACTIVATION_TIME_SLOP) {
                                mLastTouchExploreEvent = MotionEvent.obtain(event);
                                break;
                            }

                            // If a tap is farther than the tap slop => nothing to do.
                            final int pointerIndex = event.findPointerIndex(pointerId);
                            final float deltaX = mLastTouchExploreEvent.getX(pointerIndex)
                                    - event.getX(pointerIndex);
                            final float deltaY = mLastTouchExploreEvent.getY(pointerIndex)
                                    - event.getY(pointerIndex);
                            final float deltaMove = (float) Math.hypot(deltaX, deltaY);
                            if (deltaMove > mTouchExplorationTapSlop) {
                                mLastTouchExploreEvent = MotionEvent.obtain(event);
                                break;
                            }

                            // All preconditions are met, so click the last explored location.
                            sendActionDownAndUp(mLastTouchExploreEvent, policyFlags);
                            mLastTouchExploreEvent = null;
                        } else {
                            mLastTouchExploreEvent = MotionEvent.obtain(event);
                        }
                    } break;
                }
            } break;
            case MotionEvent.ACTION_CANCEL: {
                mSendHoverDelayed.remove();
                mPerformLongPressDelayed.remove();
                final int pointerId = pointerTracker.getPrimaryActivePointerId();
                final int pointerIdBits = (1 << pointerId);                
                ensureHoverExitSent(event, pointerIdBits, policyFlags);
                clear();
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
                final int activePointerCount = mPointerTracker.getActivePointerCount();
                switch (activePointerCount) {
                    case 2: {
                        if (isDraggingGesture(event)) {
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
                mCurrentState = STATE_TOUCH_EXPLORING;
                // Send an event to the end of the drag gesture.
                sendMotionEvent(event, MotionEvent.ACTION_UP, pointerIdBits, policyFlags);
             } break;
            case MotionEvent.ACTION_CANCEL: {
                clear();
            } break;
        }
    }

    /**
     * Handles a motion event in delegating state.
     *
     * @param event The event to be handled.
     * @param policyFlags The policy flags associated with the event.
     */
    public void handleMotionEventStateDelegating(MotionEvent event, int policyFlags) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                throw new IllegalStateException("Delegating state can only be reached if "
                        + "there is at least one pointer down!");
            }
            case MotionEvent.ACTION_UP: {
                mCurrentState = STATE_TOUCH_EXPLORING;
            } break;
            case MotionEvent.ACTION_MOVE: {
                // Check  whether some other pointer became active because they have moved
                // a given distance and if such exist send them to the view hierarchy
                final int notInjectedCount = mPointerTracker.getNotInjectedActivePointerCount();
                if (notInjectedCount > 0) {
                    MotionEvent prototype = MotionEvent.obtain(event);
                    sendDownForAllActiveNotInjectedPointers(prototype, policyFlags);
                }
            } break;
            case MotionEvent.ACTION_POINTER_UP: {
                // No active pointers => go to initial state.
                if (mPointerTracker.getActivePointerCount() == 0) {
                    mCurrentState = STATE_TOUCH_EXPLORING;
                }
            } break;
            case MotionEvent.ACTION_CANCEL: {
                clear();
            } break;
        }
        // Deliver the event striping out inactive pointers.
        sendMotionEventStripInactivePointers(event, policyFlags);
    }

    /**
     * Sends down events to the view hierarchy for all active pointers which are
     * not already being delivered i.e. pointers that are not yet injected.
     *
     * @param prototype The prototype from which to create the injected events.
     * @param policyFlags The policy flags associated with the event.
     */
    private void sendDownForAllActiveNotInjectedPointers(MotionEvent prototype, int policyFlags) {
        final PointerTracker pointerTracker = mPointerTracker;
        int pointerIdBits = 0;
        final int pointerCount = prototype.getPointerCount();

        // Find which pointers are already injected.
        for (int i = 0; i < pointerCount; i++) {
            final int pointerId = prototype.getPointerId(i);
            if (pointerTracker.isInjectedPointerDown(pointerId)) {
                pointerIdBits |= (1 << pointerId);
            }
        }

        // Inject the active and not injected pointers.
        for (int i = 0; i < pointerCount; i++) {
            final int pointerId = prototype.getPointerId(i);
            // Skip inactive pointers.
            if (!pointerTracker.isActivePointer(pointerId)) {
                continue;
            }
            // Do not send event for already delivered pointers.
            if (pointerTracker.isInjectedPointerDown(pointerId)) {
                continue;
            }
            pointerIdBits |= (1 << pointerId);
            final int action = computeInjectionAction(MotionEvent.ACTION_DOWN, i);
            sendMotionEvent(prototype, action, pointerIdBits, policyFlags);
        }
    }

    /**
     * Ensures that hover exit has been sent.
     *
     * @param prototype The prototype from which to create the injected events.
     * @param pointerIdBits The bits of the pointers to send.
     * @param policyFlags The policy flags associated with the event.
     */
    private void ensureHoverExitSent(MotionEvent prototype, int pointerIdBits, int policyFlags) {
        final int lastAction = mPointerTracker.getLastInjectedHoverAction();
        if (lastAction != MotionEvent.ACTION_HOVER_EXIT) {
            sendMotionEvent(prototype, MotionEvent.ACTION_HOVER_EXIT, pointerIdBits,
                    policyFlags);
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
        final PointerTracker pointerTracker = mPointerTracker;
        int pointerIdBits = 0;
        final int pointerCount = prototype.getPointerCount();
        for (int i = 0; i < pointerCount; i++) {
            final int pointerId = prototype.getPointerId(i);
            // Skip non injected down pointers.
            if (!pointerTracker.isInjectedPointerDown(pointerId)) {
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
        PointerTracker pointerTracker = mPointerTracker;

        // All pointers active therefore we just inject the event as is.
        if (prototype.getPointerCount() == pointerTracker.getActivePointerCount()) {
            sendMotionEvent(prototype, prototype.getAction(), ALL_POINTER_ID_BITS, policyFlags);
            return;
        }

        // No active pointers and the one that just went up was not
        // active, therefore we have nothing to do.
        if (pointerTracker.getActivePointerCount() == 0
                && !pointerTracker.wasLastReceivedUpPointerActive()) {
            return;
        }

        // If the action pointer going up/down is not active we have nothing to do.
        // However, for moves we keep going to report moves of active pointers.
        final int actionMasked = prototype.getActionMasked();
        final int actionPointerId = prototype.getPointerId(prototype.getActionIndex());
        if (actionMasked != MotionEvent.ACTION_MOVE) {
            if (!pointerTracker.isActiveOrWasLastActiveUpPointer(actionPointerId)) {
                return;
            }
        }

        // If the pointer is active or the pointer that just went up
        // was active we keep the pointer data in the event.
        int pointerIdBits = 0;
        final int pointerCount = prototype.getPointerCount();
        for (int pointerIndex = 0; pointerIndex < pointerCount; pointerIndex++) {
            final int pointerId = prototype.getPointerId(pointerIndex);
            if (pointerTracker.isActiveOrWasLastActiveUpPointer(pointerId)) {
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
        // Tap with the pointer that last went up - we may have inactive pointers.
        final int pointerId = mPointerTracker.getLastReceivedUpPointerId();
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
            event.setDownTime(mPointerTracker.getLastInjectedDownEventTime());
        }

        if (DEBUG) {
            Slog.d(LOG_TAG_INJECTED, "Injecting event: " + event + ", policyFlags=0x"
                    + Integer.toHexString(policyFlags));
        }

        // Make sure that the user will see the event.
        policyFlags |= WindowManagerPolicy.FLAG_PASS_TO_USER;
        mPointerTracker.onInjectedMotionEvent(event);
        mInputFilter.sendInputEvent(event, policyFlags);

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
                PointerTracker pointerTracker = mPointerTracker;
                // Compute the action based on how many down pointers are injected.
                if (pointerTracker.getInjectedPointerDownCount() == 0) {
                    return MotionEvent.ACTION_DOWN;
                } else {
                    return (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        | MotionEvent.ACTION_POINTER_DOWN;
                }
            }
            case MotionEvent.ACTION_POINTER_UP: {
                PointerTracker pointerTracker = mPointerTracker;
                // Compute the action based on how many down pointers are injected.
                if (pointerTracker.getInjectedPointerDownCount() == 1) {
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
        PointerTracker pointerTracker = mPointerTracker;
        int[] pointerIds = mTempPointerIds;
        pointerTracker.populateActivePointerIds(pointerIds);

        final int firstPtrIndex = event.findPointerIndex(pointerIds[0]);
        final int secondPtrIndex = event.findPointerIndex(pointerIds[1]);

        final float firstPtrX = event.getX(firstPtrIndex);
        final float firstPtrY = event.getY(firstPtrIndex);
        final float secondPtrX = event.getX(secondPtrIndex);
        final float secondPtrY = event.getY(secondPtrIndex);

        // Check if the pointers are moving in the same direction.
        final float firstDeltaX =
            firstPtrX - pointerTracker.getReceivedPointerDownX(firstPtrIndex);
        final float firstDeltaY =
            firstPtrY - pointerTracker.getReceivedPointerDownY(firstPtrIndex);

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
            secondPtrX - pointerTracker.getReceivedPointerDownX(secondPtrIndex);
        final float secondDeltaY =
            secondPtrY - pointerTracker.getReceivedPointerDownY(secondPtrIndex);

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

        if (angleCos < MIN_ANGLE_COS) {
            return false;
        }

        return true;
    }

   /**
    * Sends an event announcing the start/end of a touch exploration gesture.
    *
    * @param eventType The type of the event to send.
    */
    private void sendAccessibilityEvent(int eventType) {
        AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
        mAccessibilityManager.sendAccessibilityEvent(event);
    }

    /**
     * Clears the internal state of this explorer.
     */
    public void clear() {
        mSendHoverDelayed.remove();
        mPerformLongPressDelayed.remove();
        mPointerTracker.clear();
        mLastTouchExploreEvent = null;
        mCurrentState = STATE_TOUCH_EXPLORING;
        mTouchExploreGestureInProgress = false;
        mDraggingPointerId = INVALID_POINTER_ID;
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
            default:
                throw new IllegalArgumentException("Unknown state: " + state);
        }
    }

    /**
     * Helper class for tracking pointers and more specifically which of
     * them are currently down, which are active, and which are delivered
     * to the view hierarchy. The enclosing {@link TouchExplorer} uses the
     * pointer state reported by this class to perform touch exploration.
     * <p>
     * The main purpose of this class is to allow the touch explorer to
     * disregard pointers put down by accident by the user and not being
     * involved in the interaction. For example, a blind user grabs the
     * device with her left hand such that she touches the screen and she
     * uses her right hand's index finger to explore the screen content.
     * In this scenario the touches generated by the left hand are to be
     * ignored.
     */
    class PointerTracker {
        private static final String LOG_TAG = "PointerTracker";

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

        // Keep track of which pointers sent to the system are down.
        private int mInjectedPointersDown;

        // Keep track of the last up pointer data.
        private long mLastReceivedUpPointerDownTime;
        private int mLastReceivedUpPointerId;
        private boolean mLastReceivedUpPointerActive;

        // The time of the last injected down.
        private long mLastInjectedDownEventTime;

        // The action of the last injected hover event.
        private int mLastInjectedHoverEventAction = MotionEvent.ACTION_HOVER_EXIT;

        /**
         * Creates a new instance.
         *
         * @param context Context for looking up resources.
         */
        public PointerTracker(Context context) {
            mThresholdActivePointer =
                ViewConfiguration.get(context).getScaledTouchSlop() * COEFFICIENT_ACTIVE_POINTER;
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
            mInjectedPointersDown = 0;
            mLastReceivedUpPointerDownTime = 0;
            mLastReceivedUpPointerId = 0;
            mLastReceivedUpPointerActive = false;
        }

        /**
         * Processes a received {@link MotionEvent} event.
         *
         * @param event The event to process.
         */
        public void onReceivedMotionEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    // New gesture so restart tracking injected down pointers.
                    mInjectedPointersDown = 0;
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
                Slog.i(LOG_TAG, "Received pointer: " + toString());
            }
        }

        /**
         * Processes an injected {@link MotionEvent} event.
         *
         * @param event The event to process.
         */
        public void onInjectedMotionEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    handleInjectedPointerDown(event.getActionIndex(), event);
                    mLastInjectedDownEventTime = event.getDownTime();
                } break;
                case MotionEvent.ACTION_POINTER_DOWN: {
                    handleInjectedPointerDown(event.getActionIndex(), event);
                } break;
                case MotionEvent.ACTION_UP: {
                    handleInjectedPointerUp(event.getActionIndex(), event);
                } break;
                case MotionEvent.ACTION_POINTER_UP: {
                    handleInjectedPointerUp(event.getActionIndex(), event);
                } break;
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE:
                case MotionEvent.ACTION_HOVER_EXIT: {
                    mLastInjectedHoverEventAction = event.getActionMasked();
                } break;
            }
            if (DEBUG) {
                Slog.i(LOG_TAG, "Injected pointer: " + toString());
            }
        }

        /**
         * @return The number of received pointers that are down.
         */
        public int getReceivedPointerDownCount() {
            return Integer.bitCount(mReceivedPointersDown);
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
         * @return The number of down pointers injected to the view hierarchy.
         */
        public int getInjectedPointerDownCount() {
            return Integer.bitCount(mInjectedPointersDown);
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
         * @return Whether the last received pointer that went up was active.
         */
        public boolean wasLastReceivedUpPointerActive() {
            return mLastReceivedUpPointerActive;
        }

        /**
         * @return The time of the last injected down event.
         */
        public long getLastInjectedDownEventTime() {
            return mLastInjectedDownEventTime;
        }

        /**
         * @return The action of the last injected hover event.
         */
        public int getLastInjectedHoverAction() {
            return mLastInjectedHoverEventAction;
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
         * @return The number of non injected active pointers.
         */
        public int getNotInjectedActivePointerCount() {
            final int pointerState = mActivePointers & ~mInjectedPointersDown;
            return Integer.bitCount(pointerState);
        }

        /**
         * @param pointerId The unique pointer id.
         * @return Whether the pointer is active or was the last active than went up.
         */
        private boolean isActiveOrWasLastActiveUpPointer(int pointerId) {
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
         * Handles a injected pointer down event.
         *
         * @param pointerIndex The index of the pointer that has changed.
         * @param event The event to be handled.
         */
        private void handleInjectedPointerDown(int pointerIndex, MotionEvent event) {
            final int pointerId = event.getPointerId(pointerIndex);
            final int pointerFlag = (1 << pointerId);
            mInjectedPointersDown |= pointerFlag;
        }

        /**
         * Handles a injected pointer up event.
         *
         * @param pointerIndex The index of the pointer that has changed.
         * @param event The event to be handled.
         */
        private void handleInjectedPointerUp(int pointerIndex, MotionEvent event) {
            final int pointerId = event.getPointerId(pointerIndex);
            final int pointerFlag = (1 << pointerId);
            mInjectedPointersDown &= ~pointerFlag;
            if (mInjectedPointersDown == 0) {
                mLastInjectedDownEventTime = 0;
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

    /**
     * Class for delayed sending of long press.
     */
    private final class PerformLongPressDelayed implements Runnable {
        private MotionEvent mEvent;
        private int mPolicyFlags;

        public void post(MotionEvent prototype, int policyFlags, long delay) {
            mEvent = MotionEvent.obtain(prototype);
            mPolicyFlags = policyFlags;
            mHandler.postDelayed(this, delay);
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
            mCurrentState = STATE_DELEGATING;
            // Make sure the scheduled hover exit is delivered.
            mSendHoverDelayed.remove();
            final int pointerId = mPointerTracker.getPrimaryActivePointerId();
            final int pointerIdBits = (1 << pointerId);
            ensureHoverExitSent(mEvent, pointerIdBits, mPolicyFlags);

            sendDownForAllActiveNotInjectedPointers(mEvent, mPolicyFlags);
            mTouchExploreGestureInProgress = false;
            mLastTouchExploreEvent = null;
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
    private final class SendHoverDelayed implements Runnable {
        private static final String LOG_TAG = "SendHoverEnterOrExitDelayed";

        private MotionEvent mEvent;
        private int mAction;
        private int mPointerIdBits;
        private int mPolicyFlags;

        public void post(MotionEvent prototype, int action, int pointerIdBits, int policyFlags,
                long delay) {
            remove();
            mEvent = MotionEvent.obtain(prototype);
            mAction = action;
            mPointerIdBits = pointerIdBits;
            mPolicyFlags = policyFlags;
            mHandler.postDelayed(this, delay);
        }

        public void remove() {
            mHandler.removeCallbacks(this);
            clear();
        }

        private boolean isPenidng() {
            return (mEvent != null);
        }

        private void clear() {
            if (!isPenidng()) {
                return;
            }
            mEvent.recycle();
            mEvent = null;
            mAction = 0;
            mPointerIdBits = -1;
            mPolicyFlags = 0;
        }

        public void forceSendAndRemove() {
            if (isPenidng()) {
                run();
                remove();
            }
        }

        public void run() {
            if (DEBUG) {
                if (mAction == MotionEvent.ACTION_HOVER_ENTER) {
                    Slog.d(LOG_TAG, "Injecting: " + MotionEvent.ACTION_HOVER_ENTER);
                } else if (mAction == MotionEvent.ACTION_HOVER_MOVE) {
                    Slog.d(LOG_TAG, "Injecting: MotionEvent.ACTION_HOVER_MOVE");
                } else if (mAction == MotionEvent.ACTION_HOVER_EXIT) {
                    Slog.d(LOG_TAG, "Injecting: MotionEvent.ACTION_HOVER_EXIT");
                }
            }

            sendMotionEvent(mEvent, mAction, mPointerIdBits, mPolicyFlags);
            clear();
        }
    }
}
