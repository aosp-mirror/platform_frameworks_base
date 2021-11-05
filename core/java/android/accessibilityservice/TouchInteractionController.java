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
    private @interface State {}

    // The maximum number of pointers that can be touching the screen at once. (See MAX_POINTER_ID
    // in frameworks/native/include/input/Input.h)
    private static final int MAX_POINTER_COUNT = 32;

    private final AccessibilityService mService;
    private final Object mLock;
    private final int mDisplayId;
    private boolean mServiceDetectsGestures;
    /** Map of listeners to executors. Lazily created when adding the first listener. */
    private ArrayMap<Listener, Executor> mListeners;

    // The current state of the display.
    private int mState = STATE_CLEAR;

    TouchInteractionController(
            @NonNull AccessibilityService service, @NonNull Object lock, int displayId) {
        mDisplayId = displayId;
        mLock = lock;
        mService = service;
    }

    /**
     * Adds the specified change listener to the list of motion event listeners. The callback will
     * run using on the specified {@link Executor}', or on the service's main thread if the
     * Executor is {@code null}.
     * @param listener the listener to add, must be non-null
     * @param executor the executor for this callback, or {@code null} to execute on the service's
     *     main thread
     */
    public void addListener(@Nullable Executor executor, @NonNull Listener listener) {
        synchronized (mLock) {
            if (mListeners == null) {
                mListeners = new ArrayMap<>();
            }
            mListeners.put(listener, executor);
            if (mListeners.size() == 1) {
                setServiceDetectsGestures(true);
            }
        }
    }

    /**
     * Removes the specified listener from the list of motion event listeners.
     *
     * @param listener the listener to remove, must be non-null
     * @return {@code true} if the listener was removed, {@code false} otherwise
     */
    public boolean removeListener(@NonNull Listener listener) {
        if (mListeners == null) {
            return false;
        }
        synchronized (mLock) {
            boolean result = mListeners.remove(listener) != null;
            if (result && mListeners.size() == 0) {
                setServiceDetectsGestures(false);
            }
            return result;
        }
    }

    /**
     * Removes all listeners and returns control of touch interactions to the framework.
     */
    public void removeAllListeners() {
        if (mListeners != null) {
            synchronized (mLock) {
                mListeners.clear();
                setServiceDetectsGestures(false);
            }
        }
    }

    /**
     * Dispatches motion events to any registered listeners. This should be called on the service's
     * main thread.
     */
    void onMotionEvent(MotionEvent event) {
        final ArrayMap<Listener, Executor> entries;
        synchronized (mLock) {
            // Listeners may remove themselves. Perform a shallow copy to avoid concurrent
            // modification.
            entries = new ArrayMap<>(mListeners);
        }
        for (int i = 0, count = entries.size(); i < count; i++) {
            final Listener listener = entries.keyAt(i);
            final Executor executor = entries.valueAt(i);
            if (executor != null) {
                executor.execute(() -> listener.onMotionEvent(event));
            } else {
                // We're already on the main thread, just run the listener.
                listener.onMotionEvent(event);
            }
        }
    }

    /**
     * Dispatches motion events to any registered listeners. This should be called on the service's
     * main thread.
     */
    void onStateChanged(@State int state) {
        mState = state;
        final ArrayMap<Listener, Executor> entries;
        synchronized (mLock) {
            // Listeners may remove themselves. Perform a shallow copy to avoid concurrent
            // modification.
            entries = new ArrayMap<>(mListeners);
        }
        for (int i = 0, count = entries.size(); i < count; i++) {
            final Listener listener = entries.keyAt(i);
            final Executor executor = entries.valueAt(i);
            if (executor != null) {
                executor.execute(() -> listener.onStateChanged(state));
            } else {
                // We're already on the main thread, just run the listener.
                listener.onStateChanged(state);
            }
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
     * least one listener has been added for this display this function tells the framework to
     * initiate touch exploration. Touch exploration will continue for the duration of this
     * interaction.
     */
    public void requestTouchExploration() {
        checkState();
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance()
                        .getConnection(mService.getConnectionId());
        if (connection != null) {
            try {
                connection.requestTouchExploration(mDisplayId);
            } catch (RemoteException re) {
                throw new RuntimeException(re);
            }
        }
    }

    /**
     * If {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} and {@link If
     * {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is enabled and at least
     * one listener has been added, this function tells the framework to initiate a dragging
     * interaction using the specified pointer. The pointer's movements will be passed through to
     * the rest of the input pipeline. Dragging is often used to perform two-finger scrolling.
     *
     * @param pointerId the pointer to be passed through to the rest of the input pipeline. If the
     *            pointer id is valid but not actually present on the screen it will be ignored.
     * @throws IllegalArgumentException if the pointer id is outside of the allowed range.
     */
    public void requestDragging(int pointerId) {
        checkState();
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
        }
    }

    /**
     * If {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} and {@link If
     * {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is enabled and at least
     * one listener has been added, this function tells the framework to initiate a delegating
     * interaction. Motion events will be passed through as-is to the rest of the input pipeline for
     * the duration of this interaction.
     */
    public void requestDelegating() {
        checkState();
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance()
                        .getConnection(mService.getConnectionId());
        if (connection != null) {
            try {
                connection.requestDelegating(mDisplayId);
            } catch (RemoteException re) {
                throw new RuntimeException(re);
            }
        }
    }

    /**
     * If {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} and {@link If
     * {@link AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE} is enabled and at least
     * one listener has been added, this function tells the framework to perform a click.
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
     * one listener has been added, this function tells the framework to perform a long click.
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

    private void checkState() {
        if (!mServiceDetectsGestures || mListeners.size() == 0) {
            throw new IllegalStateException(
                    "State transitions are not allowed without first adding a listener.");
        }
        if (mState != STATE_TOUCH_INTERACTING) {
            throw new IllegalStateException(
                    "State transitions are not allowed in " + stateToString(mState));
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

    /** Listeners allow services to receive motion events and state change updates. */
    public interface Listener {
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
