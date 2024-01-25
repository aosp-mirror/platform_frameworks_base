/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.input.flags.Flags.enablePointerChoreographer;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DRAG;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.content.ClipData;
import android.content.Context;
import android.hardware.input.InputManagerGlobal;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Slog;
import android.view.Display;
import android.view.IWindow;
import android.view.InputDevice;
import android.view.PointerIcon;
import android.view.SurfaceControl;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import com.android.server.wm.WindowManagerInternal.IDragDropCallback;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Managing drag and drop operations initiated by View#startDragAndDrop.
 */
class DragDropController {
    private static final float DRAG_SHADOW_ALPHA_TRANSPARENT = .7071f;
    static final long DRAG_TIMEOUT_MS = 5000;
    private static final int A11Y_DRAG_TIMEOUT_DEFAULT_MS = 60000;

    // Messages for Handler.
    static final int MSG_DRAG_END_TIMEOUT = 0;
    static final int MSG_TEAR_DOWN_DRAG_AND_DROP_INPUT = 1;
    static final int MSG_ANIMATION_END = 2;
    static final int MSG_REMOVE_DRAG_SURFACE_TIMEOUT = 3;

    /**
     * Drag state per operation.
     * Needs a lock of {@code WindowManagerService#mWindowMap} to read this. Needs both locks of
     * {@code mWriteLock} and {@code WindowManagerService#mWindowMap} to update this.
     * The variable is cleared by {@code #onDragStateClosedLocked} which is invoked by DragState
     * itself, thus the variable can be null after calling DragState's methods.
     */
    private DragState mDragState;

    private WindowManagerService mService;
    private final Handler mHandler;

    /**
     * Callback which is used to sync drag state with the vendor-specific code.
     */
    @NonNull private AtomicReference<IDragDropCallback> mCallback = new AtomicReference<>(
            new IDragDropCallback() {});

    DragDropController(WindowManagerService service, Looper looper) {
        mService = service;
        mHandler = new DragHandler(service, looper);
    }

    boolean dragDropActiveLocked() {
        return mDragState != null && !mDragState.isClosing();
    }

    boolean dragSurfaceRelinquishedToDropTarget() {
        return mDragState != null && mDragState.mRelinquishDragSurfaceToDropTarget;
    }

    void registerCallback(IDragDropCallback callback) {
        Objects.requireNonNull(callback);
        mCallback.set(callback);
    }

    void sendDragStartedIfNeededLocked(WindowState window) {
        mDragState.sendDragStartedIfNeededLocked(window);
    }

    IBinder performDrag(int callerPid, int callerUid, IWindow window, int flags,
            SurfaceControl surface, int touchSource, int touchDeviceId, int touchPointerId,
            float touchX, float touchY, float thumbCenterX, float thumbCenterY, ClipData data) {
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "perform drag: win=" + window + " surface=" + surface + " flags=" +
                    Integer.toHexString(flags) + " data=" + data + " touch(" + touchX + ","
                    + touchY + ") thumb center(" + thumbCenterX + "," + thumbCenterY + ")");
        }

        final IBinder dragToken = new Binder();
        final boolean callbackResult = mCallback.get().prePerformDrag(window, dragToken,
                touchSource, touchX, touchY, thumbCenterX, thumbCenterY, data);
        try {
            DisplayContent displayContent = null;
            CompletableFuture<Boolean> touchFocusTransferredFuture = null;
            synchronized (mService.mGlobalLock) {
                try {
                    if (!callbackResult) {
                        Slog.w(TAG_WM, "IDragDropCallback rejects the performDrag request");
                        return null;
                    }

                    if (dragDropActiveLocked()) {
                        Slog.w(TAG_WM, "Drag already in progress");
                        return null;
                    }

                    final WindowState callingWin = mService.windowForClientLocked(
                            null, window, false);
                    if (callingWin == null || !callingWin.canReceiveTouchInput()) {
                        Slog.w(TAG_WM, "Bad requesting window " + window);
                        return null;  // !!! TODO: throw here?
                    }

                    // !!! TODO: if input is not still focused on the initiating window, fail
                    // the drag initiation (e.g. an alarm window popped up just as the application
                    // called performDrag()

                    // !!! TODO: extract the current touch (x, y) in screen coordinates.  That
                    // will let us eliminate the (touchX,touchY) parameters from the API.

                    // !!! FIXME: put all this heavy stuff onto the mHandler looper, as well as
                    // the actual drag event dispatch stuff in the dragstate

                    // !!! TODO(multi-display): support other displays

                    displayContent = callingWin.getDisplayContent();
                    if (displayContent == null) {
                        Slog.w(TAG_WM, "display content is null");
                        return null;
                    }

                    final float alpha = (flags & View.DRAG_FLAG_OPAQUE) == 0 ?
                            DRAG_SHADOW_ALPHA_TRANSPARENT : 1;
                    final IBinder winBinder = window.asBinder();
                    IBinder token = new Binder();
                    mDragState = new DragState(mService, this, token, surface, flags, winBinder);
                    surface = null;
                    mDragState.mPid = callerPid;
                    mDragState.mUid = callerUid;
                    mDragState.mOriginalAlpha = alpha;
                    mDragState.mAnimatedScale = callingWin.mGlobalScale;
                    mDragState.mToken = dragToken;
                    mDragState.mDisplayContent = displayContent;
                    mDragState.mData = data;

                    if ((flags & View.DRAG_FLAG_ACCESSIBILITY_ACTION) == 0) {
                        final Display display = displayContent.getDisplay();
                        touchFocusTransferredFuture = mCallback.get().registerInputChannel(
                                mDragState, display, mService.mInputManager,
                                callingWin.mInputChannel);
                    } else {
                        // Skip surface logic for a drag triggered by an AccessibilityAction
                        mDragState.broadcastDragStartedLocked(touchX, touchY);

                        // Timeout for the user to drop the content
                        sendTimeoutMessage(MSG_DRAG_END_TIMEOUT, callingWin.mClient.asBinder(),
                                getAccessibilityManager().getRecommendedTimeoutMillis(
                                        A11Y_DRAG_TIMEOUT_DEFAULT_MS,
                                        AccessibilityManager.FLAG_CONTENT_CONTROLS));

                        return dragToken;
                    }
                } finally {
                    if (surface != null) {
                        try (final SurfaceControl.Transaction transaction =
                                mService.mTransactionFactory.get()) {
                            transaction.remove(surface);
                            transaction.apply();
                        }
                    }
                }
            }

            boolean touchFocusTransferred = false;
            try {
                touchFocusTransferred = touchFocusTransferredFuture.get(DRAG_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS);
            } catch (Exception exception) {
                Slog.e(TAG_WM, "Exception thrown while waiting for touch focus transfer",
                        exception);
            }

            synchronized (mService.mGlobalLock) {
                if (!touchFocusTransferred) {
                    Slog.e(TAG_WM, "Unable to transfer touch focus");
                    mDragState.closeLocked();
                    return null;
                }

                final SurfaceControl surfaceControl = mDragState.mSurfaceControl;
                mDragState.broadcastDragStartedLocked(touchX, touchY);
                if (enablePointerChoreographer()) {
                    if ((touchSource & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
                        InputManagerGlobal.getInstance().setPointerIcon(
                                PointerIcon.getSystemIcon(
                                        mService.mContext, PointerIcon.TYPE_GRABBING),
                                mDragState.mDisplayContent.getDisplayId(), touchDeviceId,
                                touchPointerId, mDragState.getInputToken());
                    }
                } else {
                    mDragState.overridePointerIconLocked(touchSource);
                }
                // remember the thumb offsets for later
                mDragState.mThumbOffsetX = thumbCenterX;
                mDragState.mThumbOffsetY = thumbCenterY;

                // Make the surface visible at the proper location
                if (SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG_WM, ">>> OPEN TRANSACTION performDrag");
                }

                final SurfaceControl.Transaction transaction = mDragState.mTransaction;
                transaction.setAlpha(surfaceControl, mDragState.mOriginalAlpha);
                transaction.show(surfaceControl);
                displayContent.reparentToOverlay(transaction, surfaceControl);
                mDragState.updateDragSurfaceLocked(true, touchX, touchY);
                if (SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG_WM, "<<< CLOSE TRANSACTION performDrag");
                }
            }
            return dragToken;    // success!
        } finally {
            mCallback.get().postPerformDrag();
        }
    }

    void reportDropResult(IWindow window, boolean consumed) {
        IBinder token = window.asBinder();
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "Drop result=" + consumed + " reported by " + token);
        }

        mCallback.get().preReportDropResult(window, consumed);
        try {
            synchronized (mService.mGlobalLock) {
                if (mDragState == null) {
                    // Most likely the drop recipient ANRed and we ended the drag
                    // out from under it.  Log the issue and move on.
                    Slog.w(TAG_WM, "Drop result given but no drag in progress");
                    return;
                }

                if (mDragState.mToken != token) {
                    // We're in a drag, but the wrong window has responded.
                    Slog.w(TAG_WM, "Invalid drop-result claim by " + window);
                    throw new IllegalStateException("reportDropResult() by non-recipient");
                }

                // The right window has responded, even if it's no longer around,
                // so be sure to halt the timeout even if the later WindowState
                // lookup fails.
                mHandler.removeMessages(MSG_DRAG_END_TIMEOUT, window.asBinder());
                WindowState callingWin = mService.windowForClientLocked(null, window, false);
                if (callingWin == null) {
                    Slog.w(TAG_WM, "Bad result-reporting window " + window);
                    return;  // !!! TODO: throw here?
                }

                mDragState.mDragResult = consumed;
                mDragState.mRelinquishDragSurfaceToDropTarget = consumed
                        && mDragState.targetInterceptsGlobalDrag(callingWin);
                mDragState.endDragLocked();
            }
        } finally {
            mCallback.get().postReportDropResult();
        }
    }

    void cancelDragAndDrop(IBinder dragToken, boolean skipAnimation) {
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "cancelDragAndDrop");
        }

        mCallback.get().preCancelDragAndDrop(dragToken);
        try {
            synchronized (mService.mGlobalLock) {
                if (mDragState == null) {
                    Slog.w(TAG_WM, "cancelDragAndDrop() without prepareDrag()");
                    throw new IllegalStateException("cancelDragAndDrop() without prepareDrag()");
                }

                if (mDragState.mToken != dragToken) {
                    Slog.w(TAG_WM,
                            "cancelDragAndDrop() does not match prepareDrag()");
                    throw new IllegalStateException(
                            "cancelDragAndDrop() does not match prepareDrag()");
                }

                mDragState.mDragResult = false;
                mDragState.cancelDragLocked(skipAnimation);
            }
        } finally {
            mCallback.get().postCancelDragAndDrop();
        }
    }

    /**
     * Handles motion events.
     * @param keepHandling Whether if the drag operation is continuing or this is the last motion
     *          event.
     * @param newX X coordinate value in dp in the screen coordinate
     * @param newY Y coordinate value in dp in the screen coordinate
     */
    void handleMotionEvent(boolean keepHandling, float newX, float newY) {
        synchronized (mService.mGlobalLock) {
            if (!dragDropActiveLocked()) {
                // The drag has ended but the clean-up message has not been processed by
                // window manager. Drop events that occur after this until window manager
                // has a chance to clean-up the input handle.
                return;
            }

            mDragState.updateDragSurfaceLocked(keepHandling, newX, newY);
        }
    }

    void dragRecipientEntered(IWindow window) {
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "Drag into new candidate view @ " + window.asBinder());
        }
        mCallback.get().dragRecipientEntered(window);
    }

    void dragRecipientExited(IWindow window) {
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "Drag from old candidate view @ " + window.asBinder());
        }
        mCallback.get().dragRecipientExited(window);
    }

    /**
     * Sends a message to the Handler managed by DragDropController.
     */
    void sendHandlerMessage(int what, Object arg) {
        mHandler.obtainMessage(what, arg).sendToTarget();
    }

    /**
     * Sends a timeout message to the Handler managed by DragDropController.
     */
    void sendTimeoutMessage(int what, Object arg, long timeoutMs) {
        mHandler.removeMessages(what, arg);
        final Message msg = mHandler.obtainMessage(what, arg);
        mHandler.sendMessageDelayed(msg, timeoutMs);
    }

    /**
     * Notifies the current drag state is closed.
     */
    void onDragStateClosedLocked(DragState dragState) {
        if (mDragState != dragState) {
            Slog.wtf(TAG_WM, "Unknown drag state is closed");
            return;
        }
        mDragState = null;
    }

    void reportDropWindow(IBinder token, float x, float y) {
        if (mDragState == null) {
            Slog.w(TAG_WM, "Drag state is closed.");
            return;
        }

        synchronized (mService.mGlobalLock) {
            mDragState.reportDropWindowLock(token, x, y);
        }
    }

    boolean dropForAccessibility(IWindow window, float x, float y) {
        synchronized (mService.mGlobalLock) {
            final boolean isA11yEnabled = getAccessibilityManager().isEnabled();
            if (!dragDropActiveLocked()) {
                return false;
            }
            if (mDragState.isAccessibilityDragDrop() && isA11yEnabled) {
                final WindowState winState = mService.windowForClientLocked(
                        null, window, false);
                if (!mDragState.isWindowNotified(winState)) {
                    return false;
                }
                IBinder token = winState.mInputChannelToken;
                return mDragState.reportDropWindowLock(token, x, y);
            }
            return false;
        }
    }

    AccessibilityManager getAccessibilityManager() {
        return (AccessibilityManager) mService.mContext.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
    }

    private class DragHandler extends Handler {
        /**
         * Lock for window manager.
         */
        private final WindowManagerService mService;

        DragHandler(WindowManagerService service, Looper looper) {
            super(looper);
            mService = service;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DRAG_END_TIMEOUT: {
                    final IBinder win = (IBinder) msg.obj;
                    if (DEBUG_DRAG) {
                        Slog.w(TAG_WM, "Timeout ending drag to win " + win);
                    }

                    synchronized (mService.mGlobalLock) {
                        // !!! TODO: ANR the drag-receiving app
                        if (mDragState != null) {
                            mDragState.mDragResult = false;
                            mDragState.endDragLocked();
                        }
                    }
                    break;
                }

                case MSG_TEAR_DOWN_DRAG_AND_DROP_INPUT: {
                    if (DEBUG_DRAG)
                        Slog.d(TAG_WM, "Drag ending; tearing down input channel");
                    final DragState.InputInterceptor interceptor =
                            (DragState.InputInterceptor) msg.obj;
                    if (interceptor == null) return;
                    synchronized (mService.mGlobalLock) {
                        interceptor.tearDown();
                    }
                    break;
                }

                case MSG_ANIMATION_END: {
                    synchronized (mService.mGlobalLock) {
                        if (mDragState == null) {
                            Slog.wtf(TAG_WM, "mDragState unexpectedly became null while " +
                                    "playing animation");
                            return;
                        }
                        mDragState.closeLocked();
                    }
                    break;
                }

                case MSG_REMOVE_DRAG_SURFACE_TIMEOUT: {
                    synchronized (mService.mGlobalLock) {
                        mService.mTransactionFactory.get().remove((SurfaceControl) msg.obj).apply();
                    }
                    break;
                }
            }
        }
    }
}
