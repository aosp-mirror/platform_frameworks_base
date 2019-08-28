/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static com.android.server.wm.DragDropController.MSG_ANIMATION_END;
import static com.android.server.wm.DragDropController.MSG_DRAG_END_TIMEOUT;
import static com.android.server.wm.DragDropController.MSG_TEAR_DOWN_DRAG_AND_DROP_INPUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DRAG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.animation.Animator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.content.ClipData;
import android.content.ClipDescription;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.util.Slog;
import android.view.Display;
import android.view.DragEvent;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputWindowHandle;
import android.view.PointerIcon;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import com.android.internal.view.IDragAndDropPermissions;
import com.android.server.LocalServices;

import java.util.ArrayList;

/**
 * Drag/drop state
 */
class DragState {
    private static final long MIN_ANIMATION_DURATION_MS = 195;
    private static final long MAX_ANIMATION_DURATION_MS = 375;

    private static final int DRAG_FLAGS_URI_ACCESS = View.DRAG_FLAG_GLOBAL_URI_READ |
            View.DRAG_FLAG_GLOBAL_URI_WRITE;

    private static final int DRAG_FLAGS_URI_PERMISSIONS = DRAG_FLAGS_URI_ACCESS |
            View.DRAG_FLAG_GLOBAL_PERSISTABLE_URI_PERMISSION |
            View.DRAG_FLAG_GLOBAL_PREFIX_URI_PERMISSION;

    // Property names for animations
    private static final String ANIMATED_PROPERTY_X = "x";
    private static final String ANIMATED_PROPERTY_Y = "y";
    private static final String ANIMATED_PROPERTY_ALPHA = "alpha";
    private static final String ANIMATED_PROPERTY_SCALE = "scale";

    final WindowManagerService mService;
    final DragDropController mDragDropController;
    IBinder mToken;
    /**
     * Do not use the variable from the out of animation thread while mAnimator is not null.
     */
    SurfaceControl mSurfaceControl;
    int mFlags;
    IBinder mLocalWin;
    int mPid;
    int mUid;
    int mSourceUserId;
    boolean mCrossProfileCopyAllowed;
    ClipData mData;
    ClipDescription mDataDescription;
    int mTouchSource;
    boolean mDragResult;
    float mOriginalAlpha;
    float mOriginalX, mOriginalY;
    float mCurrentX, mCurrentY;
    float mThumbOffsetX, mThumbOffsetY;
    InputInterceptor mInputInterceptor;
    WindowState mTargetWindow;
    ArrayList<WindowState> mNotifiedWindows;
    boolean mDragInProgress;
    /**
     * Whether if animation is completed. Needs to be volatile to update from the animation thread
     * without having a WM lock.
     */
    volatile boolean mAnimationCompleted = false;
    DisplayContent mDisplayContent;

    @Nullable private ValueAnimator mAnimator;
    private final Interpolator mCubicEaseOutInterpolator = new DecelerateInterpolator(1.5f);
    private Point mDisplaySize = new Point();

    // A surface used to catch input events for the drag-and-drop operation.
    SurfaceControl mInputSurface;

    private final SurfaceControl.Transaction mTransaction;

    private final Rect mTmpClipRect = new Rect();

    /**
     * Whether we are finishing this drag and drop. This starts with {@code false}, and is set to
     * {@code true} when {@link #closeLocked()} is called.
     */
    private boolean mIsClosing;

    DragState(WindowManagerService service, DragDropController controller, IBinder token,
            SurfaceControl surface, int flags, IBinder localWin) {
        mService = service;
        mDragDropController = controller;
        mToken = token;
        mSurfaceControl = surface;
        mFlags = flags;
        mLocalWin = localWin;
        mNotifiedWindows = new ArrayList<WindowState>();
        mTransaction = service.mTransactionFactory.get();
    }

    boolean isClosing() {
        return mIsClosing;
    }

    private void showInputSurface() {
        if (mInputSurface == null) {
            mInputSurface = mService.makeSurfaceBuilder(
                    mService.mRoot.getDisplayContent(mDisplayContent.getDisplayId()).getSession())
                    .setContainerLayer()
                    .setName("Drag and Drop Input Consumer").build();
        }
        final InputWindowHandle h = getInputWindowHandle();
        if (h == null) {
            Slog.w(TAG_WM, "Drag is in progress but there is no "
                    + "drag window handle.");
            return;
        }

        mTransaction.show(mInputSurface);
        mTransaction.setInputWindowInfo(mInputSurface, h);
        mTransaction.setLayer(mInputSurface, Integer.MAX_VALUE);

        mTmpClipRect.set(0, 0, mDisplaySize.x, mDisplaySize.y);
        mTransaction.setWindowCrop(mInputSurface, mTmpClipRect);

        // syncInputWindows here to ensure the input window info is sent before the
        // transferTouchFocus is called.
        mTransaction.syncInputWindows();
        mTransaction.apply();
    }

    /**
     * After calling this, DragDropController#onDragStateClosedLocked is invoked, which causes
     * DragDropController#mDragState becomes null.
     */
    void closeLocked() {
        mIsClosing = true;
        // Unregister the input interceptor.
        if (mInputInterceptor != null) {
            if (DEBUG_DRAG)
                Slog.d(TAG_WM, "unregistering drag input channel");

            // Input channel should be disposed on the thread where the input is being handled.
            mDragDropController.sendHandlerMessage(
                    MSG_TEAR_DOWN_DRAG_AND_DROP_INPUT, mInputInterceptor);
            mInputInterceptor = null;
        }

        // Send drag end broadcast if drag start has been sent.
        if (mDragInProgress) {
            final int myPid = Process.myPid();

            if (DEBUG_DRAG) {
                Slog.d(TAG_WM, "broadcasting DRAG_ENDED");
            }
            for (WindowState ws : mNotifiedWindows) {
                float x = 0;
                float y = 0;
                if (!mDragResult && (ws.mSession.mPid == mPid)) {
                    // Report unconsumed drop location back to the app that started the drag.
                    x = mCurrentX;
                    y = mCurrentY;
                }
                DragEvent evt = DragEvent.obtain(DragEvent.ACTION_DRAG_ENDED,
                        x, y, null, null, null, null, mDragResult);
                try {
                    ws.mClient.dispatchDragEvent(evt);
                } catch (RemoteException e) {
                    Slog.w(TAG_WM, "Unable to drag-end window " + ws);
                }
                // if the current window is in the same process,
                // the dispatch has already recycled the event
                if (myPid != ws.mSession.mPid) {
                    evt.recycle();
                }
            }
            mNotifiedWindows.clear();
            mDragInProgress = false;
        }

        // Take the cursor back if it has been changed.
        if (isFromSource(InputDevice.SOURCE_MOUSE)) {
            mService.restorePointerIconLocked(mDisplayContent, mCurrentX, mCurrentY);
            mTouchSource = 0;
        }

        // Clear the internal variables.
        if (mInputSurface != null) {
            mTransaction.remove(mInputSurface).apply();
            mInputSurface = null;
        }
        if (mSurfaceControl != null) {
            mTransaction.reparent(mSurfaceControl, null).apply();
            mSurfaceControl = null;
        }
        if (mAnimator != null && !mAnimationCompleted) {
            Slog.wtf(TAG_WM,
                    "Unexpectedly destroying mSurfaceControl while animation is running");
        }
        mFlags = 0;
        mLocalWin = null;
        mToken = null;
        mData = null;
        mThumbOffsetX = mThumbOffsetY = 0;
        mNotifiedWindows = null;

        // Notifies the controller that the drag state is closed.
        mDragDropController.onDragStateClosedLocked(this);
    }

    class InputInterceptor {
        InputChannel mServerChannel, mClientChannel;
        DragInputEventReceiver mInputEventReceiver;
        InputApplicationHandle mDragApplicationHandle;
        InputWindowHandle mDragWindowHandle;

        InputInterceptor(Display display) {
            InputChannel[] channels = InputChannel.openInputChannelPair("drag");
            mServerChannel = channels[0];
            mClientChannel = channels[1];
            mService.mInputManager.registerInputChannel(mServerChannel, null);
            mInputEventReceiver = new DragInputEventReceiver(mClientChannel,
                    mService.mH.getLooper(), mDragDropController);

            mDragApplicationHandle = new InputApplicationHandle(new Binder());
            mDragApplicationHandle.name = "drag";
            mDragApplicationHandle.dispatchingTimeoutNanos =
                    WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;

            mDragWindowHandle = new InputWindowHandle(mDragApplicationHandle, null,
                    display.getDisplayId());
            mDragWindowHandle.name = "drag";
            mDragWindowHandle.token = mServerChannel.getToken();
            mDragWindowHandle.layer = getDragLayerLocked();
            mDragWindowHandle.layoutParamsFlags = 0;
            mDragWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_DRAG;
            mDragWindowHandle.dispatchingTimeoutNanos =
                    WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;
            mDragWindowHandle.visible = true;
            mDragWindowHandle.canReceiveKeys = false;
            mDragWindowHandle.hasFocus = true;
            mDragWindowHandle.hasWallpaper = false;
            mDragWindowHandle.paused = false;
            mDragWindowHandle.ownerPid = Process.myPid();
            mDragWindowHandle.ownerUid = Process.myUid();
            mDragWindowHandle.inputFeatures = 0;
            mDragWindowHandle.scaleFactor = 1.0f;

            // The drag window cannot receive new touches.
            mDragWindowHandle.touchableRegion.setEmpty();

            // The drag window covers the entire display
            mDragWindowHandle.frameLeft = 0;
            mDragWindowHandle.frameTop = 0;
            mDragWindowHandle.frameRight = mDisplaySize.x;
            mDragWindowHandle.frameBottom = mDisplaySize.y;

            // Pause rotations before a drag.
            if (DEBUG_ORIENTATION) {
                Slog.d(TAG_WM, "Pausing rotation during drag");
            }
            mDisplayContent.getDisplayRotation().pause();
        }

        void tearDown() {
            mService.mInputManager.unregisterInputChannel(mServerChannel);
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
            mClientChannel.dispose();
            mServerChannel.dispose();
            mClientChannel = null;
            mServerChannel = null;

            mDragWindowHandle = null;
            mDragApplicationHandle = null;

            // Resume rotations after a drag.
            if (DEBUG_ORIENTATION) {
                Slog.d(TAG_WM, "Resuming rotation after drag");
            }
            mDisplayContent.getDisplayRotation().resume();
        }
    }

    InputChannel getInputChannel() {
        return mInputInterceptor == null ? null : mInputInterceptor.mServerChannel;
    }

    InputWindowHandle getInputWindowHandle() {
        return mInputInterceptor == null ? null : mInputInterceptor.mDragWindowHandle;
    }

    /**
     * @param display The Display that the window being dragged is on.
     */
    void register(Display display) {
        display.getRealSize(mDisplaySize);
        if (DEBUG_DRAG) Slog.d(TAG_WM, "registering drag input channel");
        if (mInputInterceptor != null) {
            Slog.e(TAG_WM, "Duplicate register of drag input channel");
        } else {
            mInputInterceptor = new InputInterceptor(display);
            showInputSurface();
        }
    }

    int getDragLayerLocked() {
        return mService.mPolicy.getWindowLayerFromTypeLw(WindowManager.LayoutParams.TYPE_DRAG)
                * WindowManagerService.TYPE_LAYER_MULTIPLIER
                + WindowManagerService.TYPE_LAYER_OFFSET;
    }

    /* call out to each visible window/session informing it about the drag
     */
    void broadcastDragStartedLocked(final float touchX, final float touchY) {
        mOriginalX = mCurrentX = touchX;
        mOriginalY = mCurrentY = touchY;

        // Cache a base-class instance of the clip metadata so that parceling
        // works correctly in calling out to the apps.
        mDataDescription = (mData != null) ? mData.getDescription() : null;
        mNotifiedWindows.clear();
        mDragInProgress = true;

        mSourceUserId = UserHandle.getUserId(mUid);

        final UserManagerInternal userManager = LocalServices.getService(UserManagerInternal.class);
        mCrossProfileCopyAllowed = !userManager.getUserRestriction(
                mSourceUserId, UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE);

        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "broadcasting DRAG_STARTED at (" + touchX + ", " + touchY + ")");
        }

        mDisplayContent.forAllWindows(w -> {
            sendDragStartedLocked(w, touchX, touchY, mDataDescription);
        }, false /* traverseTopToBottom */ );
    }

    /* helper - send a ACTION_DRAG_STARTED event, if the
     * designated window is potentially a drop recipient.  There are race situations
     * around DRAG_ENDED broadcast, so we make sure that once we've declared that
     * the drag has ended, we never send out another DRAG_STARTED for this drag action.
     *
     * This method clones the 'event' parameter if it's being delivered to the same
     * process, so it's safe for the caller to call recycle() on the event afterwards.
     */
    private void sendDragStartedLocked(WindowState newWin, float touchX, float touchY,
            ClipDescription desc) {
        if (mDragInProgress && isValidDropTarget(newWin)) {
            DragEvent event = obtainDragEvent(newWin, DragEvent.ACTION_DRAG_STARTED,
                    touchX, touchY, null, desc, null, null, false);
            try {
                newWin.mClient.dispatchDragEvent(event);
                // track each window that we've notified that the drag is starting
                mNotifiedWindows.add(newWin);
            } catch (RemoteException e) {
                Slog.w(TAG_WM, "Unable to drag-start window " + newWin);
            } finally {
                // if the callee was local, the dispatch has already recycled the event
                if (Process.myPid() != newWin.mSession.mPid) {
                    event.recycle();
                }
            }
        }
    }

    private boolean isValidDropTarget(WindowState targetWin) {
        if (targetWin == null) {
            return false;
        }
        if (!targetWin.isPotentialDragTarget()) {
            return false;
        }
        if ((mFlags & View.DRAG_FLAG_GLOBAL) == 0 || !targetWindowSupportsGlobalDrag(targetWin)) {
            // Drag is limited to the current window.
            if (mLocalWin != targetWin.mClient.asBinder()) {
                return false;
            }
        }

        return mCrossProfileCopyAllowed ||
                mSourceUserId == UserHandle.getUserId(targetWin.getOwningUid());
    }

    private boolean targetWindowSupportsGlobalDrag(WindowState targetWin) {
        // Global drags are limited to system windows, and windows for apps that are targeting N and
        // above.
        return targetWin.mAppToken == null
                || targetWin.mAppToken.mTargetSdk >= Build.VERSION_CODES.N;
    }

    /* helper - send a ACTION_DRAG_STARTED event only if the window has not
     * previously been notified, i.e. it became visible after the drag operation
     * was begun.  This is a rare case.
     */
    void sendDragStartedIfNeededLocked(WindowState newWin) {
        if (mDragInProgress) {
            // If we have sent the drag-started, we needn't do so again
            if (isWindowNotified(newWin)) {
                return;
            }
            if (DEBUG_DRAG) {
                Slog.d(TAG_WM, "need to send DRAG_STARTED to new window " + newWin);
            }
            sendDragStartedLocked(newWin, mCurrentX, mCurrentY, mDataDescription);
        }
    }

    private boolean isWindowNotified(WindowState newWin) {
        for (WindowState ws : mNotifiedWindows) {
            if (ws == newWin) {
                return true;
            }
        }
        return false;
    }

    void endDragLocked() {
        if (mAnimator != null) {
            return;
        }
        if (!mDragResult) {
            mAnimator = createReturnAnimationLocked();
            return;  // Will call closeLocked() when the animation is done.
        }
        closeLocked();
    }

    void cancelDragLocked(boolean skipAnimation) {
        if (mAnimator != null) {
            return;
        }
        if (!mDragInProgress || skipAnimation) {
            // mDragInProgress is false if an app invokes Session#cancelDragAndDrop before
            // Session#performDrag. Reset the drag state without playing the cancel animation
            // because H.DRAG_START_TIMEOUT may be sent to WindowManagerService, which will cause
            // DragState#reset() while playing the cancel animation.
            // skipAnimation is true when a caller requests to skip the drag cancel animation.
            closeLocked();
            return;
        }
        mAnimator = createCancelAnimationLocked();
    }

    void notifyMoveLocked(float x, float y) {
        if (mAnimator != null) {
            return;
        }
        mCurrentX = x;
        mCurrentY = y;

        // Move the surface to the given touch
        if (SHOW_LIGHT_TRANSACTIONS) {
            Slog.i(TAG_WM, ">>> OPEN TRANSACTION notifyMoveLocked");
        }
        mTransaction.setPosition(mSurfaceControl, x - mThumbOffsetX, y - mThumbOffsetY).apply();
        if (SHOW_TRANSACTIONS) {
            Slog.i(TAG_WM, "  DRAG " + mSurfaceControl + ": pos=(" + (int) (x - mThumbOffsetX) + ","
                    + (int) (y - mThumbOffsetY) + ")");
        }
        notifyLocationLocked(x, y);
    }

    void notifyLocationLocked(float x, float y) {
        // Tell the affected window
        WindowState touchedWin = mDisplayContent.getTouchableWinAtPointLocked(x, y);
        if (touchedWin != null && !isWindowNotified(touchedWin)) {
            // The drag point is over a window which was not notified about a drag start.
            // Pretend it's over empty space.
            touchedWin = null;
        }

        try {
            final int myPid = Process.myPid();

            // have we dragged over a new window?
            if ((touchedWin != mTargetWindow) && (mTargetWindow != null)) {
                if (DEBUG_DRAG) {
                    Slog.d(TAG_WM, "sending DRAG_EXITED to " + mTargetWindow);
                }
                // force DRAG_EXITED_EVENT if appropriate
                DragEvent evt = obtainDragEvent(mTargetWindow, DragEvent.ACTION_DRAG_EXITED,
                        0, 0, null, null, null, null, false);
                mTargetWindow.mClient.dispatchDragEvent(evt);
                if (myPid != mTargetWindow.mSession.mPid) {
                    evt.recycle();
                }
            }
            if (touchedWin != null) {
                if (false && DEBUG_DRAG) {
                    Slog.d(TAG_WM, "sending DRAG_LOCATION to " + touchedWin);
                }
                DragEvent evt = obtainDragEvent(touchedWin, DragEvent.ACTION_DRAG_LOCATION,
                        x, y, null, null, null, null, false);
                touchedWin.mClient.dispatchDragEvent(evt);
                if (myPid != touchedWin.mSession.mPid) {
                    evt.recycle();
                }
            }
        } catch (RemoteException e) {
            Slog.w(TAG_WM, "can't send drag notification to windows");
        }
        mTargetWindow = touchedWin;
    }

    /**
     * Finds the drop target and tells it about the data. If the drop event is not sent to the
     * target, invokes {@code endDragLocked} immediately.
     */
    void notifyDropLocked(float x, float y) {
        if (mAnimator != null) {
            return;
        }
        mCurrentX = x;
        mCurrentY = y;

        final WindowState touchedWin = mDisplayContent.getTouchableWinAtPointLocked(x, y);

        if (!isWindowNotified(touchedWin)) {
            // "drop" outside a valid window -- no recipient to apply a
            // timeout to, and we can send the drag-ended message immediately.
            mDragResult = false;
            endDragLocked();
            return;
        }

        if (DEBUG_DRAG) Slog.d(TAG_WM, "sending DROP to " + touchedWin);

        final int targetUserId = UserHandle.getUserId(touchedWin.getOwningUid());

        final DragAndDropPermissionsHandler dragAndDropPermissions;
        if ((mFlags & View.DRAG_FLAG_GLOBAL) != 0 && (mFlags & DRAG_FLAGS_URI_ACCESS) != 0
                && mData != null) {
            dragAndDropPermissions = new DragAndDropPermissionsHandler(
                    mData,
                    mUid,
                    touchedWin.getOwningPackage(),
                    mFlags & DRAG_FLAGS_URI_PERMISSIONS,
                    mSourceUserId,
                    targetUserId);
        } else {
            dragAndDropPermissions = null;
        }
        if (mSourceUserId != targetUserId){
            if (mData != null) {
                mData.fixUris(mSourceUserId);
            }
        }
        final int myPid = Process.myPid();
        final IBinder token = touchedWin.mClient.asBinder();
        final DragEvent evt = obtainDragEvent(touchedWin, DragEvent.ACTION_DROP, x, y,
                null, null, mData, dragAndDropPermissions, false);
        try {
            touchedWin.mClient.dispatchDragEvent(evt);

            // 5 second timeout for this window to respond to the drop
            mDragDropController.sendTimeoutMessage(MSG_DRAG_END_TIMEOUT, token);
        } catch (RemoteException e) {
            Slog.w(TAG_WM, "can't send drop notification to win " + touchedWin);
            endDragLocked();
        } finally {
            if (myPid != touchedWin.mSession.mPid) {
                evt.recycle();
            }
        }
        mToken = token;
    }

    /**
     * Returns true if it has sent DRAG_STARTED broadcast out but has not been sent DRAG_END
     * broadcast.
     */
    boolean isInProgress() {
        return mDragInProgress;
    }

    private static DragEvent obtainDragEvent(WindowState win, int action,
            float x, float y, Object localState,
            ClipDescription description, ClipData data,
            IDragAndDropPermissions dragAndDropPermissions,
            boolean result) {
        final float winX = win.translateToWindowX(x);
        final float winY = win.translateToWindowY(y);
        return DragEvent.obtain(action, winX, winY, localState, description, data,
                dragAndDropPermissions, result);
    }

    private ValueAnimator createReturnAnimationLocked() {
        final ValueAnimator animator = ValueAnimator.ofPropertyValuesHolder(
                PropertyValuesHolder.ofFloat(
                        ANIMATED_PROPERTY_X, mCurrentX - mThumbOffsetX,
                        mOriginalX - mThumbOffsetX),
                PropertyValuesHolder.ofFloat(
                        ANIMATED_PROPERTY_Y, mCurrentY - mThumbOffsetY,
                        mOriginalY - mThumbOffsetY),
                PropertyValuesHolder.ofFloat(ANIMATED_PROPERTY_SCALE, 1, 1),
                PropertyValuesHolder.ofFloat(
                        ANIMATED_PROPERTY_ALPHA, mOriginalAlpha, mOriginalAlpha / 2));

        final float translateX = mOriginalX - mCurrentX;
        final float translateY = mOriginalY - mCurrentY;
        // Adjust the duration to the travel distance.
        final double travelDistance = Math.sqrt(translateX * translateX + translateY * translateY);
        final double displayDiagonal =
                Math.sqrt(mDisplaySize.x * mDisplaySize.x + mDisplaySize.y * mDisplaySize.y);
        final long duration = MIN_ANIMATION_DURATION_MS + (long) (travelDistance / displayDiagonal
                * (MAX_ANIMATION_DURATION_MS - MIN_ANIMATION_DURATION_MS));
        final AnimationListener listener = new AnimationListener();
        animator.setDuration(duration);
        animator.setInterpolator(mCubicEaseOutInterpolator);
        animator.addListener(listener);
        animator.addUpdateListener(listener);

        mService.mAnimationHandler.post(() -> animator.start());
        return animator;
    }

    private ValueAnimator createCancelAnimationLocked() {
        final ValueAnimator animator = ValueAnimator.ofPropertyValuesHolder(
                PropertyValuesHolder.ofFloat(
                        ANIMATED_PROPERTY_X, mCurrentX - mThumbOffsetX, mCurrentX),
                PropertyValuesHolder.ofFloat(
                        ANIMATED_PROPERTY_Y, mCurrentY - mThumbOffsetY, mCurrentY),
                PropertyValuesHolder.ofFloat(ANIMATED_PROPERTY_SCALE, 1, 0),
                PropertyValuesHolder.ofFloat(ANIMATED_PROPERTY_ALPHA, mOriginalAlpha, 0));
        final AnimationListener listener = new AnimationListener();
        animator.setDuration(MIN_ANIMATION_DURATION_MS);
        animator.setInterpolator(mCubicEaseOutInterpolator);
        animator.addListener(listener);
        animator.addUpdateListener(listener);

        mService.mAnimationHandler.post(() -> animator.start());
        return animator;
    }

    private boolean isFromSource(int source) {
        return (mTouchSource & source) == source;
    }

    void overridePointerIconLocked(int touchSource) {
        mTouchSource = touchSource;
        if (isFromSource(InputDevice.SOURCE_MOUSE)) {
            InputManager.getInstance().setPointerIconType(PointerIcon.TYPE_GRABBING);
        }
    }

    private class AnimationListener
            implements ValueAnimator.AnimatorUpdateListener, Animator.AnimatorListener {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            try (SurfaceControl.Transaction transaction =
                         mService.mTransactionFactory.get()) {
                transaction.setPosition(
                        mSurfaceControl,
                        (float) animation.getAnimatedValue(ANIMATED_PROPERTY_X),
                        (float) animation.getAnimatedValue(ANIMATED_PROPERTY_Y));
                transaction.setAlpha(
                        mSurfaceControl,
                        (float) animation.getAnimatedValue(ANIMATED_PROPERTY_ALPHA));
                transaction.setMatrix(
                        mSurfaceControl,
                        (float) animation.getAnimatedValue(ANIMATED_PROPERTY_SCALE), 0,
                        0, (float) animation.getAnimatedValue(ANIMATED_PROPERTY_SCALE));
                transaction.apply();
            }
        }

        @Override
        public void onAnimationStart(Animator animator) {}

        @Override
        public void onAnimationCancel(Animator animator) {}

        @Override
        public void onAnimationRepeat(Animator animator) {}

        @Override
        public void onAnimationEnd(Animator animator) {
            mAnimationCompleted = true;
            // Updating mDragState requires the WM lock so continues it on the out of
            // AnimationThread.
            mDragDropController.sendHandlerMessage(MSG_ANIMATION_END, null);
        }
    }
}
