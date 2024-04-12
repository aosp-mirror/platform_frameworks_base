/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.ambient.touch;

import android.graphics.Rect;
import android.graphics.Region;
import android.view.GestureDetector;

import com.android.systemui.shared.system.InputChannelCompat;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * The {@link TouchHandler} interface provides a way for dream overlay components to observe
 * touch events and gestures with the ability to intercept the latter. Touch interaction sequences
 * are abstracted as sessions. A session represents the time of first
 * {@code android.view.MotionEvent.ACTION_DOWN} event to the last {@link TouchHandler}
 * stopping interception of gestures. If no gesture is intercepted, the session continues
 * indefinitely. {@link TouchHandler} have the ability to create a stack of sessions, which
 * allows for motion logic to be captured in modal states.
 */
public interface TouchHandler {
    /**
     * A touch session captures the interaction surface of a {@link TouchHandler}. Clients
     * register listeners as desired to participate in motion/gesture callbacks.
     */
    interface TouchSession {
        interface Callback {
            /**
             * Invoked when the session has been removed.
             */
            void onRemoved();
        }

        /**
         * Registers a callback to be notified when there are updates to the {@link TouchSession}.
         */
        void registerCallback(Callback callback);

        /**
         * Adds a input event listener for the given session.
         * @param inputEventListener
         */
        boolean registerInputListener(InputChannelCompat.InputEventListener inputEventListener);

        /**
         * Adds a gesture listener for the given session.
         * @param gestureListener
         */
        boolean registerGestureListener(GestureDetector.OnGestureListener gestureListener);

        /**
         * Creates a new {@link TouchSession} that will receive any updates that would have been
         * directed to this {@link TouchSession}.
         * @return The future which will return a new {@link TouchSession} that will receive
         * subsequent events. If the operation fails, {@code null} will be returned.
         */
        ListenableFuture<TouchSession> push();

        /**
         * Explicitly releases this {@link TouchSession}. The registered listeners will no longer
         * receive any further updates.
         * @return The future containing the {@link TouchSession} that will receive subsequent
         * events. This session will be the direct predecessor of the popped session. {@code null}
         * if the popped {@link TouchSession} was the initial session or has already been popped.
         */
        ListenableFuture<TouchSession> pop();

        /**
         * Returns the number of currently active sessions.
         */
        int getActiveSessionCount();

        /**
         * Returns the bounds of the display the touch region.
         */
        Rect getBounds();
    }

    /**
     * Returns whether the handler is enabled to handle touch on dream.
     * @return isEnabled state. By default it's true.
     */
    default Boolean isEnabled() {
        return true;
    }

    /**
     * Sets whether to enable the handler to handle touch on dream.
     * @param enabled new value to be set whether to enable the handler.
     */
    default void setIsEnabled(Boolean enabled){}

    /**
     * Returns the region the touch handler is interested in. By default, no region is specified,
     * indicating the entire screen should be considered.
     * @param region A {@link Region} that is passed in to the target entry touch region.
     */
    default void getTouchInitiationRegion(Rect bounds, Region region, Rect exclusionRect) {
    }

    /**
     * Informed a new touch session has begun. The first touch event will be delivered to any
     * listener registered through
     * {@link TouchSession#registerInputListener(InputChannelCompat.InputEventListener)} during this
     * call. If there are no interactions with this touch session after this method returns, it will
     * be dropped.
     * @param session
     */
    void onSessionStart(TouchSession session);
}
