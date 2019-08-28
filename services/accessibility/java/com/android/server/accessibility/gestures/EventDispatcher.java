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

import static com.android.server.accessibility.gestures.TouchExplorer.DEBUG;
import static com.android.server.accessibility.gestures.TouchState.ALL_POINTER_ID_BITS;
import static com.android.server.accessibility.gestures.TouchState.MAX_POINTER_COUNT;

import android.content.Context;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accessibility.EventStreamTransformation;
import com.android.server.policy.WindowManagerPolicy;

/**
 * This class dispatches motion events and accessibility events relating to touch exploration and
 * gesture dispatch. TouchExplorer is responsible for insuring that the receiver of motion events is
 * set correctly so that events go to the right place.
 */
class EventDispatcher {
    private static final String LOG_TAG = "EventDispatcher";

    private final AccessibilityManagerService mAms;
    private Context mContext;
    // The receiver of motion events.
    private EventStreamTransformation mReceiver;
    // Keep track of which pointers sent to the system are down.
    private int mInjectedPointersDown;

    // The time of the last injected down.
    private long mLastInjectedDownEventTime;

    // The last injected hover event.
    private MotionEvent mLastInjectedHoverEvent;
    private TouchState mState;

    EventDispatcher(
            Context context,
            AccessibilityManagerService ams,
            EventStreamTransformation receiver,
            TouchState state) {
        mContext = context;
        mAms = ams;
        mReceiver = receiver;
        mState = state;
    }

    public void setReceiver(EventStreamTransformation receiver) {
        mReceiver = receiver;
    }

    /**
     * Sends an event.
     *
     * @param prototype The prototype from which to create the injected events.
     * @param action The action of the event.
     * @param pointerIdBits The bits of the pointers to send.
     * @param policyFlags The policy flags associated with the event.
     */
    void sendMotionEvent(MotionEvent prototype, int action, int pointerIdBits, int policyFlags) {
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
            event.setDownTime(getLastInjectedDownEventTime());
        }
        if (DEBUG) {
            Slog.d(
                    LOG_TAG,
                    "Injecting event: "
                            + event
                            + ", policyFlags=0x"
                            + Integer.toHexString(policyFlags));
        }

        // Make sure that the user will see the event.
        policyFlags |= WindowManagerPolicy.FLAG_PASS_TO_USER;
        // TODO: For now pass null for the raw event since the touch
        //       explorer is the last event transformation and it does
        //       not care about the raw event.
        if (mReceiver != null) {
            mReceiver.onMotionEvent(event, null, policyFlags);
        } else {
            Slog.e(LOG_TAG, "Error sending event: no receiver specified.");
        }
        updateState(event);

        if (event != prototype) {
            event.recycle();
        }
    }

    /**
     * Sends an accessibility event of the given type.
     *
     * @param type The event type.
     */
    void sendAccessibilityEvent(int type) {
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
        // Todo: get rid of this and have TouchState control the sending of events rather than react
        // to it.
        mState.onInjectedAccessibilityEvent(type);
    }

    /**
     * Processes an injected {@link MotionEvent} event.
     *
     * @param event The event to process.
     */
    void updateState(MotionEvent event) {
        final int action = event.getActionMasked();
        final int pointerId = event.getPointerId(event.getActionIndex());
        final int pointerFlag = (1 << pointerId);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                mInjectedPointersDown |= pointerFlag;
                mLastInjectedDownEventTime = event.getDownTime();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                mInjectedPointersDown &= ~pointerFlag;
                if (mInjectedPointersDown == 0) {
                    mLastInjectedDownEventTime = 0;
                }
                break;
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                if (mLastInjectedHoverEvent != null) {
                    mLastInjectedHoverEvent.recycle();
                }
                mLastInjectedHoverEvent = MotionEvent.obtain(event);
                break;
        }
        if (DEBUG) {
            Slog.i(LOG_TAG, "Injected pointer:\n" + toString());
        }
    }

    /** Clears the internals state. */
    public void clear() {
        mInjectedPointersDown = 0;
    }

    /** @return The time of the last injected down event. */
    public long getLastInjectedDownEventTime() {
        return mLastInjectedDownEventTime;
    }

    /** @return The number of down pointers injected to the view hierarchy. */
    public int getInjectedPointerDownCount() {
        return Integer.bitCount(mInjectedPointersDown);
    }

    /** @return The bits of the injected pointers that are down. */
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

    /** @return The the last injected hover event. */
    public MotionEvent getLastInjectedHoverEvent() {
        return mLastInjectedHoverEvent;
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

    /**
     * Computes the action for an injected event based on a masked action and a pointer index.
     *
     * @param actionMasked The masked action.
     * @param pointerIndex The index of the pointer which has changed.
     * @return The action to be used for injection.
     */
    private int computeInjectionAction(int actionMasked, int pointerIndex) {
        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                // Compute the action based on how many down pointers are injected.
                if (getInjectedPointerDownCount() == 0) {
                    return MotionEvent.ACTION_DOWN;
                } else {
                    return (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                            | MotionEvent.ACTION_POINTER_DOWN;
                }
            case MotionEvent.ACTION_POINTER_UP:
                // Compute the action based on how many down pointers are injected.
                if (getInjectedPointerDownCount() == 1) {
                    return MotionEvent.ACTION_UP;
                } else {
                    return (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                            | MotionEvent.ACTION_POINTER_UP;
                }
            default:
                return actionMasked;
        }
    }
    /**
     * Sends down events to the view hierarchy for all pointers which are not already being
     * delivered i.e. pointers that are not yet injected.
     *
     * @param prototype The prototype from which to create the injected events.
     * @param policyFlags The policy flags associated with the event.
     */
    void sendDownForAllNotInjectedPointers(MotionEvent prototype, int policyFlags) {

        // Inject the injected pointers.
        int pointerIdBits = 0;
        final int pointerCount = prototype.getPointerCount();
        for (int i = 0; i < pointerCount; i++) {
            final int pointerId = prototype.getPointerId(i);
            // Do not send event for already delivered pointers.
            if (!isInjectedPointerDown(pointerId)) {
                pointerIdBits |= (1 << pointerId);
                final int action = computeInjectionAction(MotionEvent.ACTION_DOWN, i);
                sendMotionEvent(prototype, action, pointerIdBits, policyFlags);
            }
        }
    }

    /**
     * Sends up events to the view hierarchy for all pointers which are already being delivered i.e.
     * pointers that are injected.
     *
     * @param prototype The prototype from which to create the injected events.
     * @param policyFlags The policy flags associated with the event.
     */
    void sendUpForInjectedDownPointers(MotionEvent prototype, int policyFlags) {
        int pointerIdBits = 0;
        final int pointerCount = prototype.getPointerCount();
        for (int i = 0; i < pointerCount; i++) {
            final int pointerId = prototype.getPointerId(i);
            // Skip non injected down pointers.
            if (!isInjectedPointerDown(pointerId)) {
                continue;
            }
            pointerIdBits |= (1 << pointerId);
            final int action = computeInjectionAction(MotionEvent.ACTION_UP, i);
            sendMotionEvent(prototype, action, pointerIdBits, policyFlags);
        }
    }
}
