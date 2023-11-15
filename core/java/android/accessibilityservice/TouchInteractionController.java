/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.accessibilityservice;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityInteractionClient;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * This class allows a service to handle touch exploration and the detection of specialized
 * accessibility gestures. The service receives motion events and can match those motion events
 * against the gestures it supports. The service can also request the framework enter three other
 * states of operation for the duration of this interaction. Upon entering any of these states the
 * framework will take over and the service will not receive motion events until the start of a new
 * interaction. The states are as follows:
 *
 * <ul>
 *   <li>The service can tell the framework that this interaction is touch exploration. The user is
 *       trying to explore the screen rather than manipulate it. The framework will then convert the
 *       motion events to hover events to support touch exploration.
 *   <li>The service can tell the framework that this interaction is a dragging interaction where
 *       two fingers are used to execute a one-finger gesture such as scrolling the screen. The
 *       service must specify which of the two fingers should be passed through to rest of the input
 *       pipeline.
 *   <li>Finally, the service can request that the framework delegate this interaction, meaning pass
 *       it through to the rest of the input pipeline as-is.
 * </ul>
 *
 * When {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE } is enabled, this
 * controller will receive all motion events received by the framework for the specified display
 * when not touch-exploring or delegating. If the service classifies this interaction as touch
 * exploration or delegating the framework will stop sending motion events to the service for the
 * duration of this interaction. If the service classifies this interaction as a dragging
 * interaction the framework will send motion events to the service to allow the service to
 * determine if the interaction still qualifies as dragging or if it has become a delegating
 * interaction. If {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE } is disabled
 * this controller will not receive any motion events because touch interactions are being passed
 * through to the input pipeline unaltered.
 * Note that {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE }
 * requires setting {@link android.R.attr#canRequestTouchExplorationMode} as well.
 */
public final class TouchInteractionController {
    /** The state where the user is not touching the screen. */
    public static final int STATE_CLEAR = 0;
    /**
     * The state where the user is touching the screen and the service is receiving motion events.
     */
    public static final int STATE_TOUCH_INTERACTING = 1;
    /**
     * The state where the user is explicitly exploring the screen. The service is not receiving
     * motion events.
     */
    public static final int STATE_TOUCH_EXPLORING = 2;
    /**
     * The state where the user is dragging with two fingers. The service is not receiving motion
     * events. The selected finger is being dispatched to the rest of the input pipeline to execute
     * the drag.
     */
    public static final int STATE_DRAGGING = 3;
    /**
     * The user is performing a gesture which is being passed through to the input pipeline as-is.
     * The service is not receiving motion events.
     */
    public static final int STATE_DELEGATING = 4;

    @IntDef({
        STATE_CLEAR,
        STATE_TOUCH_INTERACTING,
        STATE_TOUCH_EXPLORING,
        STATE_DRAGGING,
        STATE_DELEGATING
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface State {}

    // The maximum number of pointers that can be touching the screen at once. (See MAX_POINTER_ID
    // in frameworks/native/include/input/Input.h)
    private static final int MAX_POINTER_COUNT = 32;

    private final AccessibilityService mService;
    private final Object mLock;
    private final int mDisplayId;
    private boolean mServiceDetectsGestures;
    /** Map of callbacks to executors. Lazily created when adding the first callback. */
    private ArrayMap<Callback, Executor> mCallbacks;
    // A list of motion events that should be queued until a pending transition has taken place.
    private Queue<MotionEvent> mQueuedMotionEvents = new LinkedList<>();
    // Whether this controller is waiting for a state transition.
    // Motion events will be queued and sent to listeners after the transition has taken place.
    private boolean mStateChangeRequested = false;

    // The current state of the display.
    private int mState = STATE_CLEAR;

    TouchInteractionController(
            @NonNull AccessibilityService service, @NonNull Object lock, int displayId) {
        mDisplayId = displayId;
        mLock = lock;
        mService = service;
    }

    /**
     * Adds the specified callback to the list of callbacks. The callback will
     * run using on the specified {@link Executor}', or on the service's main thread if the
     * Executor is {@code null}.
     * @param callback the callback to add, must be non-null
     * @param executor the executor for this callback, or {@code null} to execute on the service's
     *     main thread
     */
    public void registerCallback(@Nullable Executor executor, @NonNull Callback callback) {
        synchronized (mLock) {
            if (mCallbacks == null) {
                mCallbacks = new ArrayMap<>();
            }
            mCallbacks.put(callback, executor);
            if (mCallbacks.size() == 1) {
                setServiceDetectsGestures(true);
            }
        }
    }

    /**
     * Unregisters the specified callback.
     *
     * @param callback the callback to remove, must be non-null
     * @return {@code true} if the callback was removed, {@code false} otherwise
     */
    public boolean unregisterCallback(@NonNull Callback callback) {
        if (mCallbacks == null) {
            return false;
        }
        synchronized (mLock) {
            boolean result = mCallbacks.remove(callback) != null;
            if (result && mCallbacks.size() == 0) {
                setServiceDetectsGestures(false);
            }
            return result;
        }
    }

    /**
     * Removes all callbacks and returns control of touch interactions to the framework.
     */
    public void unregisterAllCallbacks() {
        if (mCallbacks != null) {
            synchronized (mLock) {
                mCallbacks.clear();
                setServiceDetectsGestures(false);
            }
        }
    }

    /**
     * Dispatches motion events to any registered callbacks. This should be called on the service's
     * main thread.
     */
    void onMotionEvent(MotionEvent event) {
        if (mStateChangeRequested) {
            mQueuedMotionEvents.add(event);
        } else {
            sendEventToAllListeners(event);
        }
    }

    private void sendEventToAllListeners(MotionEvent event) {
        final ArrayMap<Callback, Executor> entries;
        synchronized (mLock) {
            // callbacks may remove themselves. Perform a shallow copy to avoid concurrent
            // modification.
            entries = new ArrayMap<>(mCallbacks);
        }
        for (int i = 0, count = entries.size(); i < count; i++) {
            final Callback callback = entries.keyAt(i);
            final Executor executor = entries.valueAt(i);
            if (executor != null) {
                executor.execute(() -> callback.onMotionEvent(event));
            } else {
                // We're already on the main thread, just run the callback.
                callback.onMotionEvent(event);
            }
        }
    }

    /**
     * Dispatches motion events to any registered callbacks. This should be called on the service's
     * main thread.
     */
    void onStateChanged(@State int state) {
        mState = state;
        final ArrayMap<Callback, Executor> entries;
        synchronized (mLock) {
            // callbacks may remove themselves. Perform a shallow copy to avoid concurrent
            // modification.
            entries = new ArrayMap<>(mCallbacks);
        }
        for (int i = 0, count = entries.size(); i < count; i++) {
            final Callback callback = entries.keyAt(i);
            final Executor executor = entries.valueAt(i);
            if (executor != null) {
                executor.execute(() -> callback.onStateChanged(state));
            } else {
                // We're already on the main thread, just run the callback.
                callback.onStateChanged(state);
            }
        }
        mStateChangeRequested = false;
        while (mQueuedMotionEvents.size() > 0) {
            sendEventToAllListeners(mQueuedMotionEvents.poll());
        }
    }

    /**
     * When {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is enabled, this
     * controller will receive all motion events received by the framework for the specified display
     * when not touch-exploring, delegating, or dragging. This allows the service to detect its own
     * gestures, and use its own logic to judge when the framework should start touch-exploring,
     * delegating, or dragging. If {@link
     * AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE } is disabled this flag has no
     * effect.
     *
     * @see AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE
     */
    private void setServiceDetectsGestures(boolean mode) {
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance()
                        .getConnection(mService.getConnectionId());
        if (connection != null) {
            try {
                connection.setServiceDetectsGesturesEnabled(mDisplayId, mode);
                mServiceDetectsGestures = mode;
            } catch (RemoteException re) {
                throw new RuntimeException(re);
            }
        }
    }

    /**
     * If {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is enabled and at
     * least one callback has been added for this display this function tells the framework to
     * initiate touch exploration. Touch exploration will continue for the duration of this
     * interaction.
     */
    public void requestTouchExploration() {
        validateTransitionRequest();
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance()
                        .getConnection(mService.getConnectionId());
        if (connection != null) {
            try {
                connection.requestTouchExploration(mDisplayId);
            } catch (RemoteException re) {
                throw new RuntimeException(re);
            }
            mStateChangeRequested = true;
        }
    }

    /**
     * If {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} and {@link If
     * {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is enabled and at least
     * one callback has been added, this function tells the framework to initiate a dragging
     * interaction using the specified pointer. The pointer's movements will be passed through to
     * the rest of the input pipeline. Dragging is often used to perform two-finger scrolling.
     *
     * @param pointerId the pointer to be passed through to the rest of the input pipeline. If the
     *            pointer id is valid but not actually present on the screen it will be ignored.
     * @throws IllegalArgumentException if the pointer id is outside of the allowed range.
     */
    public void requestDragging(int pointerId) {
        validateTransitionRequest();
        if (pointerId < 0 || pointerId > MAX_POINTER_COUNT) {
            throw new IllegalArgumentException("Invalid pointer id: " + pointerId);
        }
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance()
                        .getConnection(mService.getConnectionId());
        if (connection != null) {
            try {
                connection.requestDragging(mDisplayId, pointerId);
            } catch (RemoteException re) {
                throw new RuntimeException(re);
            }
            mStateChangeRequested = true;
        }
    }

    /**
     * If {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} and {@link If
     * {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is enabled and at least
     * one callback has been added, this function tells the framework to initiate a delegating
     * interaction. Motion events will be passed through as-is to the rest of the input pipeline for
     * the duration of this interaction.
     */
    public void requestDelegating() {
        validateTransitionRequest();
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance()
                        .getConnection(mService.getConnectionId());
        if (connection != null) {
            try {
                connection.requestDelegating(mDisplayId);
            } catch (RemoteException re) {
                throw new RuntimeException(re);
            }
            mStateChangeRequested = true;
        }
    }

    /**
     * If {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} and {@link If
     * {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is enabled and at least
     * one callback has been added, this function tells the framework to perform a click.
     * The framework will first try to perform
     * {@link AccessibilityNodeInfo.AccessibilityAction#ACTION_CLICK} on the item with
     * accessibility focus. If that fails, the framework will simulate a click using motion events
     * on the last location to have accessibility focus.
     */
    public void performClick() {
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance()
                        .getConnection(mService.getConnectionId());
        if (connection != null) {
            try {
                connection.onDoubleTap(mDisplayId);
            } catch (RemoteException re) {
                throw new RuntimeException(re);
            }
        }
    }

    /**
     * If {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} and {@link If
     * {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is enabled and at least
     * one callback has been added, this function tells the framework to perform a long click.
     * The framework will simulate a long click using motion events on the last location with
     * accessibility focus and will delegate any movements to the rest of the input pipeline. This
     * allows a user to double-tap and hold to trigger a drag and then execute that drag by moving
     * their finger.
     */
    public void performLongClickAndStartDrag() {
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance()
                        .getConnection(mService.getConnectionId());
        if (connection != null) {
            try {
                connection.onDoubleTapAndHold(mDisplayId);
            } catch (RemoteException re) {
                throw new RuntimeException(re);
            }
        }
    }

    private void validateTransitionRequest() {
        if (!mServiceDetectsGestures || mCallbacks.size() == 0) {
            throw new IllegalStateException(
                    "State transitions are not allowed without first adding a callback.");
        }
        if ((mState == STATE_DELEGATING || mState == STATE_TOUCH_EXPLORING)) {
            throw new IllegalStateException(
                    "State transition requests are not allowed in " + stateToString(mState));
        }
    }

    /** @return the maximum number of pointers that this display will accept. */
    public int getMaxPointerCount() {
        return MAX_POINTER_COUNT;
    }

    /** @return the display id associated with this controller. */
    public int getDisplayId() {
        return mDisplayId;
    }

    /**
     * @return the current state of this controller.
     * @see TouchInteractionController#STATE_CLEAR
     * @see TouchInteractionController#STATE_DELEGATING
     * @see TouchInteractionController#STATE_DRAGGING
     * @see TouchInteractionController#STATE_TOUCH_EXPLORING
     */
    public int getState() {
        synchronized (mLock) {
            return mState;
        }
    }

    /** Returns a string representation of the specified state. */
    @NonNull
    public static String stateToString(int state) {
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
            default:
                return "Unknown state: " + state;
        }
    }

    /** callbacks allow services to receive motion events and state change updates. */
    public interface Callback {
        /**
         * Called when the framework has sent a motion event to the service.
         *
         * @param event the event being passed to the service.
         */
        void onMotionEvent(@NonNull MotionEvent event);

        /**
         * Called when the state of motion event dispatch for this display has changed.
         *
         * @param state the new state of motion event dispatch.
         * @see TouchInteractionController#STATE_CLEAR
         * @see TouchInteractionController#STATE_DELEGATING
         * @see TouchInteractionController#STATE_DRAGGING
         * @see TouchInteractionController#STATE_TOUCH_EXPLORING
         */
        void onStateChanged(@State int state);
    }
}
