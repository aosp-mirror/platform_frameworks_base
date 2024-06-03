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

import static com.android.text.flags.Flags.handwritingEndOfLineTap;

import android.Manifest;
import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UiThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerGlobal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Slog;
import android.view.BatchedInputEventReceiver;
import android.view.Choreographer;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.SurfaceControl;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import com.android.server.LocalServices;
import com.android.server.input.InputManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

// TODO(b/210039666): See if we can make this class thread-safe.
final class HandwritingModeController {

    public static final String TAG = HandwritingModeController.class.getSimpleName();
    static final boolean DEBUG = false;
    // Use getHandwritingBufferSize() and not this value directly.
    private static final int EVENT_BUFFER_SIZE = 100;
    // A longer event buffer used for handwriting delegation
    // TODO(b/210039666): make this device touch sampling rate dependent.
    // Use getHandwritingBufferSize() and not this value directly.
    private static final int LONG_EVENT_BUFFER_SIZE = EVENT_BUFFER_SIZE * 20;
    private static final long HANDWRITING_DELEGATION_IDLE_TIMEOUT_MS = 3000;
    private static final long AFTER_STYLUS_UP_ALLOW_PERIOD_MS = 200L;

    private final Context mContext;
    // This must be the looper for the UiThread.
    private final Looper mLooper;
    private final InputManagerInternal mInputManagerInternal;
    private final WindowManagerInternal mWindowManagerInternal;
    private final PackageManagerInternal mPackageManagerInternal;

    private ArrayList<MotionEvent> mHandwritingBuffer;
    private InputEventReceiver mHandwritingEventReceiver;
    private Runnable mInkWindowInitRunnable;
    private boolean mRecordingGesture;
    private boolean mRecordingGestureAfterStylusUp;
    private int mCurrentDisplayId;
    // when set, package names are used for handwriting delegation.
    private @Nullable String mDelegatePackageName;
    private @Nullable String mDelegatorPackageName;
    private boolean mDelegatorFromDefaultHomePackage;
    private boolean mDelegationConnectionlessFlow;
    private Runnable mDelegationIdleTimeoutRunnable;
    private Handler mDelegationIdleTimeoutHandler;
    private final Runnable mDiscardDelegationTextRunnable;
    private HandwritingEventReceiverSurface mHandwritingSurface;

    private int mCurrentRequestId;

    @AnyThread
    HandwritingModeController(Context context, Looper uiThreadLooper,
            Runnable inkWindowInitRunnable,
            Runnable discardDelegationTextRunnable) {
        mContext = context;
        mLooper = uiThreadLooper;
        mCurrentDisplayId = Display.INVALID_DISPLAY;
        mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
        mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mCurrentRequestId = 0;
        mInkWindowInitRunnable = inkWindowInitRunnable;
        mDiscardDelegationTextRunnable = discardDelegationTextRunnable;
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
            mHandwritingBuffer = new ArrayList<>(getHandwritingBufferSize());
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

    void setNotTouchable(boolean notTouchable) {
        if (!getCurrentRequestId().isPresent()) {
            return;
        }
        mHandwritingSurface.setNotTouchable(notTouchable);
    }

    boolean isStylusGestureOngoing() {
        if (mRecordingGestureAfterStylusUp && !mHandwritingBuffer.isEmpty()) {
            // If it is less than AFTER_STYLUS_UP_ALLOW_PERIOD_MS after the stylus up event, return
            // true so that handwriting can start.
            MotionEvent lastEvent = mHandwritingBuffer.get(mHandwritingBuffer.size() - 1);
            if (lastEvent.getActionMasked() == MotionEvent.ACTION_UP) {
                return SystemClock.uptimeMillis() - lastEvent.getEventTime()
                        < AFTER_STYLUS_UP_ALLOW_PERIOD_MS;
            }
        }
        return mRecordingGesture;
    }

    boolean hasOngoingStylusHandwritingSession() {
        return mHandwritingSurface != null && mHandwritingSurface.isIntercepting();
    }

    /**
     * Prepare delegation of stylus handwriting to a different editor
     * @see InputMethodManager#prepareStylusHandwritingDelegation(View, String)
     */
    void prepareStylusHandwritingDelegation(
            int userId, @NonNull String delegatePackageName, @NonNull String delegatorPackageName,
            boolean connectionless) {
        mDelegatePackageName = delegatePackageName;
        mDelegatorPackageName = delegatorPackageName;
        mDelegatorFromDefaultHomePackage = false;
        // mDelegatorFromDefaultHomeActivity is only used in the cross-package delegation case.
        // For same-package delegation, it doesn't need to be checked.
        if (!delegatorPackageName.equals(delegatePackageName)) {
            ComponentName defaultHomeActivity =
                    mPackageManagerInternal.getDefaultHomeActivity(userId);
            if (defaultHomeActivity != null) {
                mDelegatorFromDefaultHomePackage =
                        delegatorPackageName.equals(defaultHomeActivity.getPackageName());
            }
        }
        mDelegationConnectionlessFlow = connectionless;
        if (!connectionless) {
            if (mHandwritingBuffer == null) {
                mHandwritingBuffer = new ArrayList<>(getHandwritingBufferSize());
            } else {
                mHandwritingBuffer.ensureCapacity(getHandwritingBufferSize());
            }
        }
        scheduleHandwritingDelegationTimeout();
    }

    @Nullable String getDelegatePackageName() {
        return mDelegatePackageName;
    }

    @Nullable String getDelegatorPackageName() {
        return mDelegatorPackageName;
    }

    boolean isDelegatorFromDefaultHomePackage() {
        return mDelegatorFromDefaultHomePackage;
    }

    boolean isDelegationUsingConnectionlessFlow() {
        return mDelegationConnectionlessFlow;
    }

    private void scheduleHandwritingDelegationTimeout() {
        if (mDelegationIdleTimeoutHandler == null) {
            mDelegationIdleTimeoutHandler = new Handler(mLooper);
        } else {
            mDelegationIdleTimeoutHandler.removeCallbacks(mDelegationIdleTimeoutRunnable);
        }
        mDelegationIdleTimeoutRunnable =  () -> {
            Slog.d(TAG, "Stylus handwriting delegation idle timed-out.");
            clearPendingHandwritingDelegation();
            if (mHandwritingBuffer != null) {
                mHandwritingBuffer.forEach(MotionEvent::recycle);
                mHandwritingBuffer.clear();
                mHandwritingBuffer.trimToSize();
                mHandwritingBuffer.ensureCapacity(getHandwritingBufferSize());
            }
        };
        mDelegationIdleTimeoutHandler.postDelayed(
                mDelegationIdleTimeoutRunnable, HANDWRITING_DELEGATION_IDLE_TIMEOUT_MS);
    }

    private int getHandwritingBufferSize() {
        if (mDelegatePackageName != null && mDelegatorPackageName != null) {
            return LONG_EVENT_BUFFER_SIZE;
        }
        return EVENT_BUFFER_SIZE;
    }
    /**
     * Clear any pending handwriting delegation info.
     */
    void clearPendingHandwritingDelegation() {
        if (DEBUG) {
            Slog.d(TAG, "clearPendingHandwritingDelegation");
        }
        if (mDelegationIdleTimeoutHandler != null) {
            mDelegationIdleTimeoutHandler.removeCallbacks(mDelegationIdleTimeoutRunnable);
            mDelegationIdleTimeoutHandler = null;
        }
        mDelegationIdleTimeoutRunnable = null;
        mDelegatorPackageName = null;
        mDelegatePackageName = null;
        mDelegatorFromDefaultHomePackage = false;
        if (mDelegationConnectionlessFlow) {
            mDelegationConnectionlessFlow = false;
            mDiscardDelegationTextRunnable.run();
        }
    }

    /**
     * Starts a {@link HandwritingSession} to transfer to the IME.
     *
     * This must be called from the UI Thread to avoid race conditions between processing more
     * input events and disposing the input event receiver.
     * @return the handwriting session to send to the IME, or null if the request was invalid.
     */
    @RequiresPermission(Manifest.permission.MONITOR_INPUT)
    @UiThread
    @Nullable
    HandwritingSession startHandwritingSession(
            int requestId, int imePid, int imeUid, IBinder focusedWindowToken) {
        clearPendingHandwritingDelegation();
        if (mHandwritingSurface == null) {
            Slog.e(TAG, "Cannot start handwriting session: Handwriting was not initialized.");
            return null;
        }
        if (requestId != mCurrentRequestId) {
            Slog.e(TAG, "Cannot start handwriting session: Invalid request id: " + requestId);
            return null;
        }
        if (!isStylusGestureOngoing()) {
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

        InputManagerGlobal.getInstance()
                .pilferPointers(mHandwritingSurface.getInputChannel().getToken());

        // Stop processing more events.
        mHandwritingEventReceiver.dispose();
        mHandwritingEventReceiver = null;
        mRecordingGesture = false;
        mRecordingGestureAfterStylusUp = false;

        if (mHandwritingSurface.isIntercepting()) {
            throw new IllegalStateException(
                    "Handwriting surface should not be already intercepting.");
        }
        mHandwritingSurface.startIntercepting(imePid, imeUid);

        // Unset the pointer icon for the stylus in case the app had set it.
        Objects.requireNonNull(mContext.getSystemService(InputManager.class)).setPointerIcon(
                PointerIcon.getSystemIcon(mContext, PointerIcon.TYPE_NOT_SPECIFIED),
                downEvent.getDisplayId(), downEvent.getDeviceId(), downEvent.getPointerId(0),
                mHandwritingSurface.getInputChannel().getToken());

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

    void setInkWindowInitializer(Runnable inkWindowInitializer) {
        mInkWindowInitRunnable = inkWindowInitializer;
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

        if (!mDelegationConnectionlessFlow) {
            clearPendingHandwritingDelegation();
        }
        mRecordingGesture = false;
        mRecordingGestureAfterStylusUp = false;
    }

    private boolean onInputEvent(InputEvent ev) {
        if (mHandwritingEventReceiver == null) {
            throw new IllegalStateException(
                    "Input Event should not be processed when IME has the spy channel.");
        }

        if (!(ev instanceof MotionEvent event)) {
            Slog.wtf(TAG, "Received non-motion event in stylus monitor.");
            return false;
        }
        if (!event.isStylusPointer()) {
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
            return;
        } else if (event.isHoverEvent()) {
            // Hover events need not be recorded to buffer.
            return;
        }

        // If handwriting delegation is ongoing, don't clear the buffer so that multiple strokes
        // can be buffered across windows.
        // (This isn't needed for the connectionless delegation flow.)
        if ((TextUtils.isEmpty(mDelegatePackageName) || mDelegationConnectionlessFlow)
                && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            mRecordingGesture = false;
            if (handwritingEndOfLineTap() && action == MotionEvent.ACTION_UP) {
                mRecordingGestureAfterStylusUp = true;
            } else {
                mHandwritingBuffer.clear();
                return;
            }
        }

        if (action == MotionEvent.ACTION_DOWN) {
            clearBufferIfRecordingAfterStylusUp();
            mRecordingGesture = true;
        }

        if (!mRecordingGesture && !mRecordingGestureAfterStylusUp) {
            return;
        }

        if (mHandwritingBuffer.size() >= getHandwritingBufferSize()) {
            if (DEBUG) {
                Slog.w(TAG, "Current gesture exceeds the buffer capacity."
                        + " The rest of the gesture will not be recorded.");
            }
            mRecordingGesture = false;
            clearBufferIfRecordingAfterStylusUp();
            return;
        }

        mHandwritingBuffer.add(MotionEvent.obtain(event));
    }

    private void clearBufferIfRecordingAfterStylusUp() {
        if (mRecordingGestureAfterStylusUp) {
            mHandwritingBuffer.clear();
            mRecordingGestureAfterStylusUp = false;
        }
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
