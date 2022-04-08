/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.annotation.WorkerThread;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.CloseGuard;

import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A client of the system providing Scroll Capture capability on behalf of a Window.
 * <p>
 * An instance is created to wrap the selected {@link ScrollCaptureCallback}.
 *
 * @hide
 */
public class ScrollCaptureClient extends IScrollCaptureClient.Stub {

    private static final String TAG = "ScrollCaptureClient";
    private static final int DEFAULT_TIMEOUT = 1000;

    private final Handler mHandler;
    private ScrollCaptureTarget mSelectedTarget;
    private int mTimeoutMillis = DEFAULT_TIMEOUT;

    protected Surface mSurface;
    private IScrollCaptureController mController;

    private final Rect mScrollBounds;
    private final Point mPositionInWindow;
    private final CloseGuard mCloseGuard;

    // The current session instance in use by the callback.
    private ScrollCaptureSession mSession;

    // Helps manage timeout callbacks registered to handler and aids testing.
    private DelayedAction mTimeoutAction;

    /**
     * Constructs a ScrollCaptureClient.
     *
     * @param selectedTarget  the target the client is controlling
     * @param controller the callbacks to reply to system requests
     *
     * @hide
     */
    public ScrollCaptureClient(
            @NonNull ScrollCaptureTarget selectedTarget,
            @NonNull IScrollCaptureController controller) {
        requireNonNull(selectedTarget, "<selectedTarget> must non-null");
        requireNonNull(controller, "<controller> must non-null");
        final Rect scrollBounds = requireNonNull(selectedTarget.getScrollBounds(),
                "target.getScrollBounds() must be non-null to construct a client");

        mSelectedTarget = selectedTarget;
        mHandler = selectedTarget.getContainingView().getHandler();
        mScrollBounds = new Rect(scrollBounds);
        mPositionInWindow = new Point(selectedTarget.getPositionInWindow());

        mController = controller;
        mCloseGuard = new CloseGuard();
        mCloseGuard.open("close");

        selectedTarget.getContainingView().addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {

                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        selectedTarget.getContainingView().removeOnAttachStateChangeListener(this);
                        endCapture();
                    }
                });
    }

    @VisibleForTesting
    public void setTimeoutMillis(int timeoutMillis) {
        mTimeoutMillis = timeoutMillis;
    }

    @Nullable
    @VisibleForTesting
    public DelayedAction getTimeoutAction() {
        return mTimeoutAction;
    }

    private void checkConnected() {
        if (mSelectedTarget == null || mController == null) {
            throw new IllegalStateException("This client has been disconnected.");
        }
    }

    private void checkStarted() {
        if (mSession == null) {
            throw new IllegalStateException("Capture session has not been started!");
        }
    }

    @WorkerThread // IScrollCaptureClient
    @Override
    public void startCapture(Surface surface) throws RemoteException {
        checkConnected();
        mSurface = surface;
        scheduleTimeout(mTimeoutMillis, this::onStartCaptureTimeout);
        mSession = new ScrollCaptureSession(mSurface, mScrollBounds, mPositionInWindow, this);
        mHandler.post(() -> mSelectedTarget.getCallback().onScrollCaptureStart(mSession,
                this::onStartCaptureCompleted));
    }

    @UiThread
    private void onStartCaptureCompleted() {
        if (cancelTimeout()) {
            mHandler.post(() -> {
                try {
                    mController.onCaptureStarted();
                } catch (RemoteException e) {
                    doShutdown();
                }
            });
        }
    }

    @UiThread
    private void onStartCaptureTimeout() {
        endCapture();
    }

    @WorkerThread // IScrollCaptureClient
    @Override
    public void requestImage(Rect requestRect) {
        checkConnected();
        checkStarted();
        scheduleTimeout(mTimeoutMillis, this::onRequestImageTimeout);
        // Response is dispatched via ScrollCaptureSession, to onRequestImageCompleted
        mHandler.post(() -> mSelectedTarget.getCallback().onScrollCaptureImageRequest(
                mSession, new Rect(requestRect)));
    }

    @UiThread
    void onRequestImageCompleted(long frameNumber, Rect capturedArea) {
        final Rect finalCapturedArea = new Rect(capturedArea);
        if (cancelTimeout()) {
            mHandler.post(() -> {
                try {
                    mController.onCaptureBufferSent(frameNumber, finalCapturedArea);
                } catch (RemoteException e) {
                    doShutdown();
                }
            });
        }
    }

    @UiThread
    private void onRequestImageTimeout() {
        endCapture();
    }

    @WorkerThread // IScrollCaptureClient
    @Override
    public void endCapture() {
        if (isStarted()) {
            scheduleTimeout(mTimeoutMillis, this::onEndCaptureTimeout);
            mHandler.post(() ->
                    mSelectedTarget.getCallback().onScrollCaptureEnd(this::onEndCaptureCompleted));
        } else {
            disconnect();
        }
    }

    private boolean isStarted() {
        return mController != null && mSelectedTarget != null;
    }

    @UiThread
    private void onEndCaptureCompleted() { // onEndCaptureCompleted
        if (cancelTimeout()) {
            doShutdown();
        }
    }

    @UiThread
    private void onEndCaptureTimeout() {
        doShutdown();
    }


    private void doShutdown() {
        try {
            if (mController != null) {
                mController.onConnectionClosed();
            }
        } catch (RemoteException e) {
            // Ignore
        } finally {
            disconnect();
        }
    }

    /**
     * Shuts down this client and releases references to dependent objects. No attempt is made
     * to notify the controller, use with caution!
     */
    public void disconnect() {
        if (mSession != null) {
            mSession.disconnect();
            mSession = null;
        }

        mSelectedTarget = null;
        mController = null;
    }

    /** @return a string representation of the state of this client */
    public String toString() {
        return "ScrollCaptureClient{"
                + ", session=" + mSession
                + ", selectedTarget=" + mSelectedTarget
                + ", clientCallbacks=" + mController
                + "}";
    }

    private boolean cancelTimeout() {
        if (mTimeoutAction != null) {
            return mTimeoutAction.cancel();
        }
        return false;
    }

    private void scheduleTimeout(long timeoutMillis, Runnable action) {
        if (mTimeoutAction != null) {
            mTimeoutAction.cancel();
        }
        mTimeoutAction = new DelayedAction(mHandler, timeoutMillis, action);
    }

    /** @hide */
    @VisibleForTesting
    public static class DelayedAction {
        private final AtomicBoolean mCompleted = new AtomicBoolean();
        private final Object mToken = new Object();
        private final Handler mHandler;
        private final Runnable mAction;

        @VisibleForTesting
        public DelayedAction(Handler handler, long timeoutMillis, Runnable action) {
            mHandler = handler;
            mAction = action;
            mHandler.postDelayed(this::onTimeout, mToken, timeoutMillis);
        }

        private boolean onTimeout() {
            if (mCompleted.compareAndSet(false, true)) {
                mAction.run();
                return true;
            }
            return false;
        }

        /**
         * Cause the timeout action to run immediately and mark as timed out.
         *
         * @return true if the timeout was run, false if the timeout had already been canceled
         */
        @VisibleForTesting
        public boolean timeoutNow() {
            return onTimeout();
        }

        /**
         * Attempt to cancel the timeout action (such as after a callback is made)
         *
         * @return true if the timeout was canceled and will not run, false if time has expired and
         * the timeout action has or will run momentarily
         */
        public boolean cancel() {
            if (!mCompleted.compareAndSet(false, true)) {
                // Whoops, too late!
                return false;
            }
            mHandler.removeCallbacksAndMessages(mToken);
            return true;
        }
    }
}
