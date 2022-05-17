/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.inputmethod;

import static android.view.InputDevice.SOURCE_STYLUS;

import android.annotation.AnyThread;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.hardware.input.InputManagerInternal;
import android.os.IBinder;
import android.os.Looper;
import android.util.Slog;
import android.view.BatchedInputEventReceiver;
import android.view.Choreographer;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.SurfaceControl;

import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerInternal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

// TODO(b/210039666): See if we can make this class thread-safe.
final class HandwritingModeController {

    public static final String TAG = HandwritingModeController.class.getSimpleName();
    // TODO(b/210039666): flip the flag.
    static final boolean DEBUG = true;
    private static final int EVENT_BUFFER_SIZE = 100;

    // This must be the looper for the UiThread.
    private final Looper mLooper;
    private final InputManagerInternal mInputManagerInternal;
    private final WindowManagerInternal mWindowManagerInternal;

    private List<MotionEvent> mHandwritingBuffer;
    private InputEventReceiver mHandwritingEventReceiver;
    private Runnable mInkWindowInitRunnable;
    private boolean mRecordingGesture;
    private int mCurrentDisplayId;

    private HandwritingEventReceiverSurface mHandwritingSurface;

    private int mCurrentRequestId;

    @AnyThread
    HandwritingModeController(Looper uiThreadLooper, Runnable inkWindowInitRunnable) {
        mLooper = uiThreadLooper;
        mCurrentDisplayId = Display.INVALID_DISPLAY;
        mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
        mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
        mCurrentRequestId = 0;
        mInkWindowInitRunnable = inkWindowInitRunnable;
    }

    // TODO(b/210039666): Consider moving this to MotionEvent
    private static boolean isStylusEvent(MotionEvent event) {
        if (!event.isFromSource(SOURCE_STYLUS)) {
            return false;
        }
        final int tool = event.getToolType(0);
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
    }

    /**
     * Initializes the handwriting spy on the given displayId.
     *
     * This must be called from the UI Thread because it will start processing events using an
     * InputEventReceiver that batches events according to the current thread's Choreographer.
     */
    @UiThread
    void initializeHandwritingSpy(int displayId) {
        // When resetting, reuse resources if we are reinitializing on the same display.
        reset(displayId == mCurrentDisplayId);
        mCurrentDisplayId = displayId;

        if (mHandwritingBuffer == null) {
            mHandwritingBuffer = new ArrayList<>(EVENT_BUFFER_SIZE);
        }

        if (DEBUG) Slog.d(TAG, "Initializing handwriting spy monitor for display: " + displayId);
        final String name = "stylus-handwriting-event-receiver-" + displayId;
        final InputChannel channel = mInputManagerInternal.createInputChannel(name);
        Objects.requireNonNull(channel, "Failed to create input channel");
        final SurfaceControl surface =
                mHandwritingSurface != null ? mHandwritingSurface.getSurface()
                        : mWindowManagerInternal.getHandwritingSurfaceForDisplay(displayId);
        if (surface == null) {
            Slog.e(TAG, "Failed to create input surface");
            return;
        }

        mHandwritingSurface = new HandwritingEventReceiverSurface(
                name, displayId, surface, channel);

        // Use a dup of the input channel so that event processing can be paused by disposing the
        // event receiver without causing a fd hangup.
        mHandwritingEventReceiver = new BatchedInputEventReceiver.SimpleBatchedInputEventReceiver(
                channel.dup(), mLooper, Choreographer.getInstance(), this::onInputEvent);
        mCurrentRequestId++;
    }

    OptionalInt getCurrentRequestId() {
        if (mHandwritingSurface == null) {
            Slog.e(TAG, "Cannot get requestId: Handwriting was not initialized.");
            return OptionalInt.empty();
        }
        return OptionalInt.of(mCurrentRequestId);
    }

    boolean isStylusGestureOngoing() {
        return mRecordingGesture;
    }

    /**
     * Starts a {@link HandwritingSession} to transfer to the IME.
     *
     * This must be called from the UI Thread to avoid race conditions between processing more
     * input events and disposing the input event receiver.
     * @return the handwriting session to send to the IME, or null if the request was invalid.
     */
    @UiThread
    @Nullable
    HandwritingSession startHandwritingSession(
            int requestId, int imePid, int imeUid, IBinder focusedWindowToken) {
        if (mHandwritingSurface == null) {
            Slog.e(TAG, "Cannot start handwriting session: Handwriting was not initialized.");
            return null;
        }
        if (requestId != mCurrentRequestId) {
            Slog.e(TAG, "Cannot start handwriting session: Invalid request id: " + requestId);
            return null;
        }
        if (!mRecordingGesture || mHandwritingBuffer.isEmpty()) {
            Slog.e(TAG, "Cannot start handwriting session: No stylus gesture is being recorded.");
            return null;
        }
        Objects.requireNonNull(mHandwritingEventReceiver,
                "Handwriting session was already transferred to IME.");
        final MotionEvent downEvent = mHandwritingBuffer.get(0);
        assert (downEvent.getActionMasked() == MotionEvent.ACTION_DOWN);
        if (!mWindowManagerInternal.isPointInsideWindow(
                focusedWindowToken, mCurrentDisplayId, downEvent.getRawX(), downEvent.getRawY())) {
            Slog.e(TAG, "Cannot start handwriting session: "
                    + "Stylus gesture did not start inside the focused window.");
            return null;
        }
        if (DEBUG) Slog.d(TAG, "Starting handwriting session in display: " + mCurrentDisplayId);

        mInputManagerInternal.pilferPointers(mHandwritingSurface.getInputChannel().getToken());

        // Stop processing more events.
        mHandwritingEventReceiver.dispose();
        mHandwritingEventReceiver = null;
        mRecordingGesture = false;

        if (mHandwritingSurface.isIntercepting()) {
            throw new IllegalStateException(
                    "Handwriting surface should not be already intercepting.");
        }
        mHandwritingSurface.startIntercepting(imePid, imeUid);

        return new HandwritingSession(mCurrentRequestId, mHandwritingSurface.getInputChannel(),
                mHandwritingBuffer);
    }

    /**
     * Reset the current handwriting session without initializing another session.
     *
     * This must be called from UI Thread to avoid race conditions between processing more input
     * events and disposing the input event receiver.
     */
    @UiThread
    void reset() {
        reset(false /* reinitializing */);
    }

    private void reset(boolean reinitializing) {
        if (mHandwritingEventReceiver != null) {
            mHandwritingEventReceiver.dispose();
            mHandwritingEventReceiver = null;
        }

        if (mHandwritingBuffer != null) {
            mHandwritingBuffer.forEach(MotionEvent::recycle);
            mHandwritingBuffer.clear();
            if (!reinitializing) {
                mHandwritingBuffer = null;
            }
        }

        if (mHandwritingSurface != null) {
            mHandwritingSurface.getInputChannel().dispose();
            if (!reinitializing) {
                mHandwritingSurface.remove();
                mHandwritingSurface = null;
            }
        }

        mRecordingGesture = false;
    }

    private boolean onInputEvent(InputEvent ev) {
        if (mHandwritingEventReceiver == null) {
            throw new IllegalStateException(
                    "Input Event should not be processed when IME has the spy channel.");
        }

        if (!(ev instanceof MotionEvent)) {
            Slog.wtf(TAG, "Received non-motion event in stylus monitor.");
            return false;
        }
        final MotionEvent event = (MotionEvent) ev;
        if (!isStylusEvent(event)) {
            return false;
        }
        if (event.getDisplayId() != mCurrentDisplayId) {
            Slog.wtf(TAG, "Received stylus event associated with the incorrect display.");
            return false;
        }

        onStylusEvent(event);
        return true;
    }

    private void onStylusEvent(MotionEvent event) {
        final int action = event.getActionMasked();

        if (mInkWindowInitRunnable != null && (action == MotionEvent.ACTION_HOVER_ENTER
                || event.getAction() == MotionEvent.ACTION_HOVER_ENTER)) {
            // Ask IMMS to make ink window ready.
            mInkWindowInitRunnable.run();
            mInkWindowInitRunnable = null;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mRecordingGesture = false;
            mHandwritingBuffer.clear();
            return;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            mRecordingGesture = true;
        }

        if (!mRecordingGesture) {
            return;
        }

        if (mHandwritingBuffer.size() >= EVENT_BUFFER_SIZE) {
            if (DEBUG) {
                Slog.w(TAG, "Current gesture exceeds the buffer capacity."
                        + " The rest of the gesture will not be recorded.");
            }
            mRecordingGesture = false;
            return;
        }

        mHandwritingBuffer.add(MotionEvent.obtain(event));
    }

    static final class HandwritingSession {
        private final int mRequestId;
        private final InputChannel mHandwritingChannel;
        private final List<MotionEvent> mRecordedEvents;

        private HandwritingSession(int requestId, InputChannel handwritingChannel,
                List<MotionEvent> recordedEvents) {
            mRequestId = requestId;
            mHandwritingChannel = handwritingChannel;
            mRecordedEvents = recordedEvents;
        }

        int getRequestId() {
            return mRequestId;
        }

        InputChannel getHandwritingChannel() {
            return mHandwritingChannel;
        }

        List<MotionEvent> getRecordedEvents() {
            return mRecordedEvents;
        }
    }
}
