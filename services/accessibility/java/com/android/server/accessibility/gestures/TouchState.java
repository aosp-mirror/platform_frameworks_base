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

import static android.view.MotionEvent.INVALID_POINTER_ID;

import static com.android.server.accessibility.gestures.TouchExplorer.DEBUG;

import android.annotation.IntDef;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * This class describes the state of the touch explorer as well as the state of received and
 * injected pointers. This data is accessed both for purposes of touch exploration and gesture
 * dispatch.
 */
public class TouchState {
    private static final String LOG_TAG = "TouchState";
    // Pointer-related constants
    // This constant captures the current implementation detail that
    // pointer IDs are between 0 and 31 inclusive (subject to change).
    // (See MAX_POINTER_ID in frameworks/base/include/ui/Input.h)
    static final int MAX_POINTER_COUNT = 32;
    // Constant referring to the ids bits of all pointers.
    public static final int ALL_POINTER_ID_BITS = 0xFFFFFFFF;

    // States that the touch explorer can be in.
    // In the clear state the user is not touching the screen.
    public static final int STATE_CLEAR = 0;
    // The user is touching the screen and we are trying to figure out their intent.
    // This state gets its name from the TYPE_TOUCH_INTERACTION start and end accessibility events.
    public static final int STATE_TOUCH_INTERACTING = 1;
    // The user is explicitly exploring the screen.
    public static final int STATE_TOUCH_EXPLORING = 2;
    // the user is dragging with two fingers.
    public static final int STATE_DRAGGING = 3;
    // The user is performing some other two finger gesture which we pass through to the view
    // hierarchy as a one-finger gesture e.g. two-finger scrolling.
    public static final int STATE_DELEGATING = 4;
    // The user is performing something that might be a gesture.
    public static final int STATE_GESTURE_DETECTING = 5;

    @IntDef({
        STATE_CLEAR,
        STATE_TOUCH_INTERACTING,
        STATE_TOUCH_EXPLORING,
        STATE_DRAGGING,
        STATE_DELEGATING,
        STATE_GESTURE_DETECTING
    })
    public @interface State {}

    // The current state of the touch explorer.
    private int mState = STATE_CLEAR;
    // Helper class to track received pointers.
    // Todo: collapse or hide this class so multiple classes don't modify it.
    private final ReceivedPointerTracker mReceivedPointerTracker;

    public TouchState() {
        mReceivedPointerTracker = new ReceivedPointerTracker();
    }

    /** Clears the internal shared state. */
    public void clear() {
        setState(STATE_CLEAR);
        // Reset the pointer trackers.
        mReceivedPointerTracker.clear();
    }

    /**
     * Updates the state in response to a touch event received by TouchExplorer.
     *
     * @param rawEvent The raw touch event.
     */
    public void onReceivedMotionEvent(MotionEvent rawEvent) {
        mReceivedPointerTracker.onMotionEvent(rawEvent);
    }

    public void onInjectedAccessibilityEvent(int type) {
        // The below state transitions go here because the related events are often sent on a
        // delay.
        // This allows state to accurately reflect the state in the moment.
        // TODO: replaced the delayed event senders with delayed state transitions
        // so that state transitions trigger events rather than events triggering state
        // transitions.
        switch (type) {
            case AccessibilityEvent.TYPE_TOUCH_INTERACTION_START:
                startTouchInteracting();
                break;
            case AccessibilityEvent.TYPE_TOUCH_INTERACTION_END:
                clear();
                break;
            case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START:
                startTouchExploring();
                break;
            case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END:
                startTouchInteracting();
                break;
            case AccessibilityEvent.TYPE_GESTURE_DETECTION_START:
                startGestureDetecting();
                break;
            case AccessibilityEvent.TYPE_GESTURE_DETECTION_END:
                startTouchInteracting();
                break;
            default:
                break;
        }
    }

    @State
    public int getState() {
        return mState;
    }

    /** Transitions to a new state. */
    public void setState(@State int state) {
        if (mState == state) return;
        if (DEBUG) {
            Slog.i(LOG_TAG, getStateSymbolicName(mState) + "->" + getStateSymbolicName(state));
        }
        mState = state;
    }

    public boolean isTouchExploring() {
        return mState == STATE_TOUCH_EXPLORING;
    }

    /** Starts touch exploration. */
    public void startTouchExploring() {
        setState(STATE_TOUCH_EXPLORING);
    }

    public boolean isDelegating() {
        return mState == STATE_DELEGATING;
    }

    /** Starts delegating gestures to the view hierarchy. */
    public void startDelegating() {
        setState(STATE_DELEGATING);
    }

    public boolean isGestureDetecting() {
        return mState == STATE_GESTURE_DETECTING;
    }

    /** Initiates gesture detection. */
    public void startGestureDetecting() {
        setState(STATE_GESTURE_DETECTING);
    }

    public boolean isDragging() {
        return mState == STATE_DRAGGING;
    }

    /** Starts a dragging gesture. */
    public void startDragging() {
        setState(STATE_DRAGGING);
    }

    public boolean isTouchInteracting() {
        return mState == STATE_TOUCH_INTERACTING;
    }

    /**
     * Transitions to the touch interacting state, where we attempt to figure out what the user is
     * doing.
     */
    public void startTouchInteracting() {
        setState(STATE_TOUCH_INTERACTING);
    }

    public boolean isClear() {
        return mState == STATE_CLEAR;
    }
    /** Returns a string representation of the current state. */
    public String toString() {
        return "TouchState { " + "mState: " + getStateSymbolicName(mState) + " }";
    }
    /** Returns a string representation of the specified state. */
    public static String getStateSymbolicName(int state) {
        switch (state) {
            case STATE_CLEAR:
                return "STATE_CLEAR";
            case STATE_TOUCH_INTERACTING:
                return "STATE_TOUCH_INTERACTING";
            case STATE_TOUCH_EXPLORING:
                return "STATE_TOUCH_EXPLORING";
            case STATE_DRAGGING:
                return "STATE_DRAGGING";
            case STATE_DELEGATING:
                return "STATE_DELEGATING";
            case STATE_GESTURE_DETECTING:
                return "STATE_GESTURE_DETECTING";
            default:
                return "Unknown state: " + state;
        }
    }

    public ReceivedPointerTracker getReceivedPointerTracker() {
        return mReceivedPointerTracker;
    }

    /** This class tracks where and when a pointer went down. It does not track its movement. */
    class ReceivedPointerTracker {
        private static final String LOG_TAG_RECEIVED_POINTER_TRACKER = "ReceivedPointerTracker";

        private final PointerDownInfo[] mReceivedPointers = new PointerDownInfo[MAX_POINTER_COUNT];

        // Which pointers are down.
        private int mReceivedPointersDown;

        // The edge flags of the last received down event.
        private int mLastReceivedDownEdgeFlags;

        // Primary pointer which is either the first that went down
        // or if it goes up the next one that most recently went down.
        private int mPrimaryPointerId;

        // Keep track of the last up pointer data.
        private MotionEvent mLastReceivedEvent;

        ReceivedPointerTracker() {
            clear();
        }

        /** Clears the internals state. */
        public void clear() {
            mReceivedPointersDown = 0;
            mPrimaryPointerId = 0;
            for (int i = 0; i < MAX_POINTER_COUNT; ++i) {
                mReceivedPointers[i] = new PointerDownInfo();
            }
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
                case MotionEvent.ACTION_DOWN:
                    handleReceivedPointerDown(event.getActionIndex(), event);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    handleReceivedPointerDown(event.getActionIndex(), event);
                    break;
                case MotionEvent.ACTION_UP:
                    handleReceivedPointerUp(event.getActionIndex(), event);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    handleReceivedPointerUp(event.getActionIndex(), event);
                    break;
            }
            if (DEBUG) {
                Slog.i(LOG_TAG_RECEIVED_POINTER_TRACKER, "Received pointer:\n" + toString());
            }
        }

        /** @return The last received event. */
        public MotionEvent getLastReceivedEvent() {
            return mLastReceivedEvent;
        }

        /** @return The number of received pointers that are down. */
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
            return mReceivedPointers[pointerId].mX;
        }

        /**
         * @param pointerId The unique pointer id.
         * @return The Y coordinate where the pointer went down.
         */
        public float getReceivedPointerDownY(int pointerId) {
            return mReceivedPointers[pointerId].mY;
        }

        /**
         * @param pointerId The unique pointer id.
         * @return The time when the pointer went down.
         */
        public long getReceivedPointerDownTime(int pointerId) {
            return mReceivedPointers[pointerId].mTime;
        }

        /** @return The id of the primary pointer. */
        public int getPrimaryPointerId() {
            if (mPrimaryPointerId == INVALID_POINTER_ID) {
                mPrimaryPointerId = findPrimaryPointerId();
            }
            return mPrimaryPointerId;
        }

        /** @return The edge flags of the last received down event. */
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
            mLastReceivedDownEdgeFlags = event.getEdgeFlags();

            mReceivedPointersDown |= pointerFlag;
            mReceivedPointers[pointerId].set(
                    event.getX(pointerIndex), event.getY(pointerIndex), event.getEventTime());

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
            mReceivedPointersDown &= ~pointerFlag;
            mReceivedPointers[pointerId].clear();
            if (mPrimaryPointerId == pointerId) {
                mPrimaryPointerId = INVALID_POINTER_ID;
            }
        }

        /** @return The primary pointer id. */
        private int findPrimaryPointerId() {
            int primaryPointerId = INVALID_POINTER_ID;
            long minDownTime = Long.MAX_VALUE;

            // Find the pointer that went down first.
            int pointerIdBits = mReceivedPointersDown;
            while (pointerIdBits > 0) {
                final int pointerId = Integer.numberOfTrailingZeros(pointerIdBits);
                pointerIdBits &= ~(1 << pointerId);
                final long downPointerTime = mReceivedPointers[pointerId].mTime;
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

    /**
     * This class tracks where and when an individual pointer went down. Note that it does not track
     * when it went up.
     */
    class PointerDownInfo {
        private float mX;
        private float mY;
        private long mTime;

        public void set(float x, float y, long time) {
            mX = x;
            mY = y;
            mTime = time;
        }

        public void clear() {
            mX = 0;
            mY = 0;
            mTime = 0;
        }
    }
}
