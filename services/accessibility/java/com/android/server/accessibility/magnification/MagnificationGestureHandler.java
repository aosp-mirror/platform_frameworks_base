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

package com.android.server.accessibility.magnification;

import static android.view.InputDevice.SOURCE_TOUCHSCREEN;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_UP;

import android.accessibilityservice.AccessibilityTrace;
import android.annotation.NonNull;
import android.util.Log;
import android.util.Slog;
import android.view.MotionEvent;

import com.android.server.accessibility.AccessibilityTraceManager;
import com.android.server.accessibility.BaseEventStreamTransformation;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * A base class that detects gestures and defines common methods for magnification.
 */
public abstract class MagnificationGestureHandler extends BaseEventStreamTransformation {

    protected final String mLogTag = this.getClass().getSimpleName();
    protected static final boolean DEBUG_ALL = Log.isLoggable("MagnificationGestureHandler",
            Log.DEBUG);
    protected static final boolean DEBUG_EVENT_STREAM = false | DEBUG_ALL;
    private final Queue<MotionEvent> mDebugInputEventHistory;
    private final Queue<MotionEvent> mDebugOutputEventHistory;

    /**
     * The logical display id.
     */
    protected final int mDisplayId;

    /**
     * {@code true} if this detector should be "triggerable" by some
     * external shortcut invoking {@link #notifyShortcutTriggered},
     * {@code false} if it should ignore such triggers.
     */
    protected final boolean mDetectShortcutTrigger;

    /**
     * {@code true} if this detector should detect and respond to triple-tap
     * gestures for engaging and disengaging magnification,
     * {@code false} if it should ignore such gestures
     */
    protected final boolean mDetectTripleTap;

    /** Callback interface to report that magnification is interactive with a user. */
    public interface Callback {
        /**
         * Called when the touch interaction is started by a user.
         *
         * @param displayId The logical display id
         * @param mode The magnification mode
         */
        void onTouchInteractionStart(int displayId, int mode);

        /**
         * Called when the touch interaction is ended by a user.
         *
         * @param displayId The logical display id
         * @param mode The magnification mode
         */
        void onTouchInteractionEnd(int displayId, int mode);
    }

    private final AccessibilityTraceManager mTrace;
    protected final Callback mCallback;

    protected MagnificationGestureHandler(int displayId, boolean detectTripleTap,
            boolean detectShortcutTrigger,
            AccessibilityTraceManager trace,
            @NonNull Callback callback) {
        mDisplayId = displayId;
        mDetectTripleTap = detectTripleTap;
        mDetectShortcutTrigger = detectShortcutTrigger;
        mTrace = trace;
        mCallback = callback;

        mDebugInputEventHistory = DEBUG_EVENT_STREAM ? new ArrayDeque<>() : null;
        mDebugOutputEventHistory = DEBUG_EVENT_STREAM ? new ArrayDeque<>() : null;
    }

    @Override
    public final void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (DEBUG_ALL) {
            Slog.i(mLogTag, "onMotionEvent(" + event + ")");
        }
        if (mTrace.isA11yTracingEnabledForTypes(
                AccessibilityTrace.FLAGS_INPUT_FILTER | AccessibilityTrace.FLAGS_GESTURE)) {
            mTrace.logTrace("MagnificationGestureHandler.onMotionEvent",
                    AccessibilityTrace.FLAGS_INPUT_FILTER | AccessibilityTrace.FLAGS_GESTURE,
                    "event=" + event + ";rawEvent=" + rawEvent + ";policyFlags=" + policyFlags);
        }
        if (DEBUG_EVENT_STREAM) {
            storeEventInto(mDebugInputEventHistory, event);
        }
        if (shouldDispatchTransformedEvent(event)) {
            dispatchTransformedEvent(event, rawEvent, policyFlags);
        } else {
            onMotionEventInternal(event, rawEvent, policyFlags);

            final int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                mCallback.onTouchInteractionStart(mDisplayId, getMode());
            } else if (action == ACTION_UP || action == ACTION_CANCEL) {
                mCallback.onTouchInteractionEnd(mDisplayId, getMode());
            }
        }
    }

    private boolean shouldDispatchTransformedEvent(MotionEvent event) {
        if ((!mDetectTripleTap && !mDetectShortcutTrigger) || !event.isFromSource(
                SOURCE_TOUCHSCREEN)) {
            return true;
        }
        return false;
    }

    final void dispatchTransformedEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (DEBUG_EVENT_STREAM) {
            storeEventInto(mDebugOutputEventHistory, event);
            try {
                super.onMotionEvent(event, rawEvent, policyFlags);
                return;
            } catch (Exception e) {
                throw new RuntimeException(
                        "Exception downstream following input events: " + mDebugInputEventHistory
                                + "\nTransformed into output events: " + mDebugOutputEventHistory,
                        e);
            }
        }
        super.onMotionEvent(event, rawEvent, policyFlags);
    }

    private static void storeEventInto(Queue<MotionEvent> queue, MotionEvent event) {
        queue.add(MotionEvent.obtain(event));
        // Prune old events
        while (!queue.isEmpty() && (event.getEventTime() - queue.peek().getEventTime() > 5000)) {
            queue.remove().recycle();
        }
    }

    /**
     * Called when this MagnificationGestureHandler handles the motion event.
     */
    abstract void onMotionEventInternal(MotionEvent event, MotionEvent rawEvent, int policyFlags);

    /**
     * Called when the shortcut target is magnification.
     */
    public void notifyShortcutTriggered() {
        if (DEBUG_ALL) {
            Slog.i(mLogTag, "notifyShortcutTriggered():");
        }
        if (mDetectShortcutTrigger) {
            handleShortcutTriggered();
        }
    }

    /**
     * Handles shortcut triggered event.
     */
    abstract void handleShortcutTriggered();

    /**
     * Indicates the magnification mode.
     *
     * @return the magnification mode of the handler
     * @see android.provider.Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
     * @see android.provider.Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
     */
    public abstract int getMode();
}
