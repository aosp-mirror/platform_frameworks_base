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
import android.graphics.Point;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;
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
    private static final int CLICK_LOCATION_NONE = 0;
    private static final int CLICK_LOCATION_ACCESSIBILITY_FOCUS = 1;
    private static final int CLICK_LOCATION_LAST_TOUCH_EXPLORED = 2;

    private final AccessibilityManagerService mAms;
    private Context mContext;
    // The receiver of motion events.
    private EventStreamTransformation mReceiver;

    // The long pressing pointer id if coordinate remapping is needed for double tap and hold
    private int mLongPressingPointerId = -1;

    // The long pressing pointer X if coordinate remapping is needed for double tap and hold.
    private int mLongPressingPointerDeltaX;

    // The long pressing pointer Y if coordinate remapping is needed for double tap and hold.
    private int mLongPressingPointerDeltaY;

    // Temporary point to avoid instantiation.
    private final Point mTempPoint = new Point();

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
     * @param rawEvent The original event prior to magnification or other transformations.
     * @param pointerIdBits The bits of the pointers to send.
     * @param policyFlags The policy flags associated with the event.
     */
    void sendMotionEvent(
            MotionEvent prototype,
            int action,
            MotionEvent rawEvent,
            int pointerIdBits,
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
            event.setDownTime(mState.getLastInjectedDownEventTime());
        }
        // If the user is long pressing but the long pressing pointer
        // was not exactly over the accessibility focused item we need
        // to remap the location of that pointer so the user does not
        // have to explicitly touch explore something to be able to
        // long press it, or even worse to avoid the user long pressing
        // on the wrong item since click and long press behave differently.
        if (mLongPressingPointerId >= 0) {
            event = offsetEvent(event, -mLongPressingPointerDeltaX, -mLongPressingPointerDeltaY);
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
        if (mReceiver != null) {
            mReceiver.onMotionEvent(event, rawEvent, policyFlags);
        } else {
            Slog.e(LOG_TAG, "Error sending event: no receiver specified.");
        }
        mState.onInjectedMotionEvent(event);

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

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("=========================");
        builder.append("\nDown pointers #");
        builder.append(Integer.bitCount(mState.getInjectedPointersDown()));
        builder.append(" [ ");
        for (int i = 0; i < MAX_POINTER_COUNT; i++) {
            if (mState.isInjectedPointerDown(i)) {
                builder.append(i);
                builder.append(" ");
            }
        }
        builder.append("]");
        builder.append("\n=========================");
        return builder.toString();
    }

    /**
     * /** Offsets all pointers in the given event by adding the specified X and Y offsets.
     *
     * @param event The event to offset.
     * @param offsetX The X offset.
     * @param offsetY The Y offset.
     * @return An event with the offset pointers or the original event if both offsets are zero.
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
        return MotionEvent.obtain(
                event.getDownTime(),
                event.getEventTime(),
                event.getAction(),
                event.getPointerCount(),
                props,
                coords,
                event.getMetaState(),
                event.getButtonState(),
                1.0f,
                1.0f,
                event.getDeviceId(),
                event.getEdgeFlags(),
                event.getSource(),
                event.getDisplayId(),
                event.getFlags());
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
                if (mState.getInjectedPointerDownCount() == 0) {
                    return MotionEvent.ACTION_DOWN;
                } else {
                    return (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                            | MotionEvent.ACTION_POINTER_DOWN;
                }
            case MotionEvent.ACTION_POINTER_UP:
                // Compute the action based on how many down pointers are injected.
                if (mState.getInjectedPointerDownCount() == 1) {
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
            if (!mState.isInjectedPointerDown(pointerId)) {
                pointerIdBits |= (1 << pointerId);
                final int action = computeInjectionAction(MotionEvent.ACTION_DOWN, i);
                sendMotionEvent(
                        prototype,
                        action,
                        mState.getLastReceivedEvent(),
                        pointerIdBits,
                        policyFlags);
            }
        }
    }

    /**
     * Sends down events to the view hierarchy for all pointers which are not already being
     * delivered with original down location. i.e. pointers that are not yet injected. The down time
     * is also replaced by the original one.
     *
     *
     * @param prototype The prototype from which to create the injected events.
     * @param policyFlags The policy flags associated with the event.
     */
    void sendDownForAllNotInjectedPointersWithOriginalDown(MotionEvent prototype, int policyFlags) {
        // Inject the injected pointers.
        int pointerIdBits = 0;
        final int pointerCount = prototype.getPointerCount();
        final MotionEvent event = computeInjectionDownEvent(prototype);
        for (int i = 0; i < pointerCount; i++) {
            final int pointerId = prototype.getPointerId(i);
            // Do not send event for already delivered pointers.
            if (!mState.isInjectedPointerDown(pointerId)) {
                pointerIdBits |= (1 << pointerId);
                final int action = computeInjectionAction(MotionEvent.ACTION_DOWN, i);
                sendMotionEvent(
                        event,
                        action,
                        mState.getLastReceivedEvent(),
                        pointerIdBits,
                        policyFlags);
            }
        }
    }

    private MotionEvent computeInjectionDownEvent(MotionEvent prototype) {
        final int pointerCount = prototype.getPointerCount();
        if (pointerCount != mState.getReceivedPointerTracker().getReceivedPointerDownCount()) {
            Slog.w(LOG_TAG, "The pointer count doesn't match the received count.");
            return MotionEvent.obtain(prototype);
        }
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointerCount];
        MotionEvent.PointerProperties[] properties =
                new MotionEvent.PointerProperties[pointerCount];
        for (int i = 0; i < pointerCount; ++i) {
            final int pointerId = prototype.getPointerId(i);
            final float x = mState.getReceivedPointerTracker().getReceivedPointerDownX(pointerId);
            final float y = mState.getReceivedPointerTracker().getReceivedPointerDownY(pointerId);
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = x;
            coords[i].y = y;
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = pointerId;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
        }
        MotionEvent event =
                MotionEvent.obtain(
                        prototype.getDownTime(),
                        // The event time is used for downTime while sending ACTION_DOWN. We adjust
                        // it to avoid the motion velocity is too fast in the beginning after
                        // Delegating.
                        prototype.getDownTime(),
                        prototype.getAction(),
                        pointerCount,
                        properties,
                        coords,
                        prototype.getMetaState(),
                        prototype.getButtonState(),
                        prototype.getXPrecision(),
                        prototype.getYPrecision(),
                        prototype.getDeviceId(),
                        prototype.getEdgeFlags(),
                        prototype.getSource(),
                        prototype.getFlags());
        return event;
    }

    /**
     *
     * Sends up events to the view hierarchy for all pointers which are already being delivered i.e.
     * pointers that are injected.
     *
     * @param prototype The prototype from which to create the injected events.
     * @param policyFlags The policy flags associated with the event.
     */
    void sendUpForInjectedDownPointers(MotionEvent prototype, int policyFlags) {
        int pointerIdBits = prototype.getPointerIdBits();
        final int pointerCount = prototype.getPointerCount();
        for (int i = 0; i < pointerCount; i++) {
            final int pointerId = prototype.getPointerId(i);
            // Skip non injected down pointers.
            if (!mState.isInjectedPointerDown(pointerId)) {
                continue;
            }
            final int action = computeInjectionAction(MotionEvent.ACTION_POINTER_UP, i);
            sendMotionEvent(
                    prototype, action, mState.getLastReceivedEvent(), pointerIdBits, policyFlags);
            pointerIdBits &= ~(1 << pointerId);
        }
    }

    public boolean longPressWithTouchEvents(MotionEvent event, int policyFlags) {
        final int pointerIndex = event.getActionIndex();
        final int pointerId = event.getPointerId(pointerIndex);
        Point clickLocation = mTempPoint;
        final int result = computeClickLocation(clickLocation);
        if (result == CLICK_LOCATION_NONE) {
            return false;
        }
        mLongPressingPointerId = pointerId;
        mLongPressingPointerDeltaX = (int) event.getX(pointerIndex) - clickLocation.x;
        mLongPressingPointerDeltaY = (int) event.getY(pointerIndex) - clickLocation.y;
        sendDownForAllNotInjectedPointers(event, policyFlags);
        return true;
    }

    void clear() {
        mLongPressingPointerId = -1;
        mLongPressingPointerDeltaX = 0;
        mLongPressingPointerDeltaY = 0;
    }

    public void clickWithTouchEvents(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        final int pointerIndex = event.getActionIndex();
        final int pointerId = event.getPointerId(pointerIndex);
        Point clickLocation = mTempPoint;
        final int result = computeClickLocation(clickLocation);
        if (result == CLICK_LOCATION_NONE) {
            Slog.e(LOG_TAG, "Unable to compute click location.");
            // We can't send a click to no location, but the gesture was still
            // consumed.
            return;
        }
        // Do the click.
        PointerProperties[] properties = new PointerProperties[1];
        properties[0] = new PointerProperties();
        event.getPointerProperties(pointerIndex, properties[0]);
        PointerCoords[] coords = new PointerCoords[1];
        coords[0] = new PointerCoords();
        coords[0].x = clickLocation.x;
        coords[0].y = clickLocation.y;
        MotionEvent clickEvent =
                MotionEvent.obtain(
                        event.getDownTime(),
                        event.getEventTime(),
                        MotionEvent.ACTION_DOWN,
                        1,
                        properties,
                        coords,
                        0,
                        0,
                        1.0f,
                        1.0f,
                        event.getDeviceId(),
                        0,
                        event.getSource(),
                        event.getDisplayId(),
                        event.getFlags());
        final boolean targetAccessibilityFocus = (result == CLICK_LOCATION_ACCESSIBILITY_FOCUS);
        sendActionDownAndUp(clickEvent, rawEvent, policyFlags, targetAccessibilityFocus);
        clickEvent.recycle();
    }

    private int computeClickLocation(Point outLocation) {
        if (mState.getLastInjectedHoverEventForClick() != null) {
            final int lastExplorePointerIndex =
                    mState.getLastInjectedHoverEventForClick().getActionIndex();
            outLocation.x =
                    (int) mState.getLastInjectedHoverEventForClick().getX(lastExplorePointerIndex);
            outLocation.y =
                    (int) mState.getLastInjectedHoverEventForClick().getY(lastExplorePointerIndex);
            if (!mAms.accessibilityFocusOnlyInActiveWindow()
                    || mState.getLastTouchedWindowId() == mAms.getActiveWindowId()) {
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

    private void sendActionDownAndUp(
            MotionEvent prototype,
            MotionEvent rawEvent,
            int policyFlags,
            boolean targetAccessibilityFocus) {
        // Tap with the pointer that last explored.
        final int pointerId = prototype.getPointerId(prototype.getActionIndex());
        final int pointerIdBits = (1 << pointerId);
        prototype.setTargetAccessibilityFocus(targetAccessibilityFocus);
        sendMotionEvent(prototype, MotionEvent.ACTION_DOWN, rawEvent, pointerIdBits, policyFlags);
        prototype.setTargetAccessibilityFocus(targetAccessibilityFocus);
        sendMotionEvent(prototype, MotionEvent.ACTION_UP, rawEvent, pointerIdBits, policyFlags);
    }
}
