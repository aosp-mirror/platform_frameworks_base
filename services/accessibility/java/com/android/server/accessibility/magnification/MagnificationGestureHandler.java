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

import android.annotation.NonNull;
import android.util.Log;
import android.util.Slog;
import android.view.MotionEvent;

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

    /** Interface for listening to the magnification scaling gesture. */
    public interface ScaleChangedListener {

    }

    protected final ScaleChangedListener mListener;

    protected MagnificationGestureHandler(int displayId, boolean detectTripleTap,
            boolean detectShortcutTrigger,
            @NonNull ScaleChangedListener listener) {
        mDisplayId = displayId;
        mDetectTripleTap = detectTripleTap;
        mDetectShortcutTrigger = detectShortcutTrigger;
        mListener = listener;

        mDebugInputEventHistory = DEBUG_EVENT_STREAM ? new ArrayDeque<>() : null;
        mDebugOutputEventHistory = DEBUG_EVENT_STREAM ? new ArrayDeque<>() : null;
    }

    @Override
    public final void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (DEBUG_ALL) {
            Slog.i(mLogTag, "onMotionEvent(" + event + ")");
        }
        if (DEBUG_EVENT_STREAM) {
            storeEventInto(mDebugInputEventHistory, event);
        }
        if (shouldDispatchTransformedEvent(event)) {
            dispatchTransformedEvent(event, rawEvent, policyFlags);
        } else {
            onMotionEventInternal(event, rawEvent, policyFlags);
        }
    }

    private boolean shouldDispatchTransformedEvent(MotionEvent event) {
        if ((!mDetectTripleTap && !mDetectShortcutTrigger) || !event.isFromSource(
                SOURCE_TOUCHSCREEN)) {
            return true;
        }
        return false;
    }

    final void dispatchTransformedEvent(MotionEvent event, MotionEvent rawEvent,
            int policyFlags) {
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
    public abstract void notifyShortcutTriggered();

    /**
     * Indicates the magnification mode.
     *
     * @return the magnification mode of the handler
     * @see android.provider.Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
     * @see android.provider.Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
     */
    public abstract int getMode();
}
