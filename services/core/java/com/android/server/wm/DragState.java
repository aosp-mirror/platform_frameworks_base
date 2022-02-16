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

import static android.content.ClipDescription.MIMETYPE_APPLICATION_ACTIVITY;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_SHORTCUT;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_TASK;
import static android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.server.wm.DragDropController.MSG_ANIMATION_END;
import static com.android.server.wm.DragDropController.MSG_DRAG_END_TIMEOUT;
import static com.android.server.wm.DragDropController.MSG_REMOVE_DRAG_SURFACE_TIMEOUT;
import static com.android.server.wm.DragDropController.MSG_TEAR_DOWN_DRAG_AND_DROP_INPUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DRAG;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
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

import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.view.IDragAndDropPermissions;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

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
    boolean mRelinquishDragSurface;
    float mOriginalAlpha;
    float mOriginalX, mOriginalY;
    float mCurrentX, mCurrentY;
    float mThumbOffsetX, mThumbOffsetY;
    InputInterceptor mInputInterceptor;
    ArrayList<WindowState> mNotifiedWindows;
    boolean mDragInProgress;
    /**
     * Whether if animation is completed. Needs to be volatile to update from the animation thread
     * without having a WM lock.
     */
    volatile boolean mAnimationCompleted = false;
    /**
     * The display on which the drag is happening. If it goes into a different display this will
     * be updated.
     */
    DisplayContent mDisplayContent;

    @Nullable private ValueAnimator mAnimator;
    private final Interpolator mCubicEaseOutInterpolator = new DecelerateInterpolator(1.5f);
    private Point mDisplaySize = new Point();

    // A surface used to catch input events for the drag-and-drop operation.
    SurfaceControl mInputSurface;

    final SurfaceControl.Transaction mTransaction;

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
        mNotifiedWindows = new ArrayList<>();
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
                    .setName("Drag and Drop Input Consumer")
                    .setCallsite("DragState.showInputSurface")
                    .build();
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
        mTransaction.apply(true);
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
                DragEvent event = DragEvent.obtain(DragEvent.ACTION_DRAG_ENDED,
                        x, y, mThumbOffsetX, mThumbOffsetY, null, null, null, null, null,
                        mDragResult);
                try {
                    ws.mClient.dispatchDragEvent(event);
                } catch (RemoteException e) {
                    Slog.w(TAG_WM, "Unable to drag-end window " + ws);
                }
                // if the current window is in the same process,
                // the dispatch has already recycled the event
                if (myPid != ws.mSession.mPid) {
                    event.recycle();
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
            if (!mRelinquishDragSurface) {
                mTransaction.reparent(mSurfaceControl, null).apply();
            } else {
                mDragDropController.sendTimeoutMessage(MSG_REMOVE_DRAG_SURFACE_TIMEOUT,
                        mSurfaceControl);
            }
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

    /**
     * Notify the drop target and tells it about the data. If the drop event is not sent to the
     * target, invokes {@code endDragLocked} immediately.
     */
    void reportDropWindowLock(IBinder token, float x, float y) {
        if (mAnimator != null) {
            return;
        }

        final WindowState touchedWin = mService.mInputToWindowMap.get(token);
        if (!isWindowNotified(touchedWin)) {
            // "drop" outside a valid window -- no recipient to apply a
            // timeout to, and we can send the drag-ended message immediately.
            mDragResult = false;
            endDragLocked();
            if (DEBUG_DRAG) Slog.d(TAG_WM, "Drop outside a valid window " + touchedWin);
            return;
        }

        if (DEBUG_DRAG) Slog.d(TAG_WM, "sending DROP to " + touchedWin);

        final int targetUserId = UserHandle.getUserId(touchedWin.getOwningUid());

        final DragAndDropPermissionsHandler dragAndDropPermissions;
        if ((mFlags & View.DRAG_FLAG_GLOBAL) != 0 && (mFlags & DRAG_FLAGS_URI_ACCESS) != 0
                && mData != null) {
            dragAndDropPermissions = new DragAndDropPermissionsHandler(mService.mGlobalLock,
                    mData,
                    mUid,
                    touchedWin.getOwningPackage(),
                    mFlags & DRAG_FLAGS_URI_PERMISSIONS,
                    mSourceUserId,
                    targetUserId);
        } else {
            dragAndDropPermissions = null;
        }
        if (mSourceUserId != targetUserId) {
            if (mData != null) {
                mData.fixUris(mSourceUserId);
            }
        }
        final int myPid = Process.myPid();
        final IBinder clientToken = touchedWin.mClient.asBinder();
        final DragEvent event = obtainDragEvent(DragEvent.ACTION_DROP, x, y,
                mData, targetInterceptsGlobalDrag(touchedWin),
                dragAndDropPermissions);
        try {
            touchedWin.mClient.dispatchDragEvent(event);

            // 5 second timeout for this window to respond to the drop
            mDragDropController.sendTimeoutMessage(MSG_DRAG_END_TIMEOUT, clientToken);
        } catch (RemoteException e) {
            Slog.w(TAG_WM, "can't send drop notification to win " + touchedWin);
            endDragLocked();
        } finally {
            if (myPid != touchedWin.mSession.mPid) {
                event.recycle();
            }
        }
        mToken = clientToken;
    }

    class InputInterceptor {
        InputChannel mClientChannel;
        DragInputEventReceiver mInputEventReceiver;
        InputApplicationHandle mDragApplicationHandle;
        InputWindowHandle mDragWindowHandle;

        InputInterceptor(Display display) {
            mClientChannel = mService.mInputManager.createInputChannel("drag");
            mInputEventReceiver = new DragInputEventReceiver(mClientChannel,
                    mService.mH.getLooper(), mDragDropController);

            mDragApplicationHandle = new InputApplicationHandle(new Binder(), "drag",
                    DEFAULT_DISPATCHING_TIMEOUT_MILLIS);

            mDragWindowHandle = new InputWindowHandle(mDragApplicationHandle,
                    display.getDisplayId());
            mDragWindowHandle.name = "drag";
            mDragWindowHandle.token = mClientChannel.getToken();
            mDragWindowHandle.layoutParamsFlags = 0;
            mDragWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_DRAG;
            mDragWindowHandle.dispatchingTimeoutMillis = DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
            mDragWindowHandle.visible = true;
            // Allows the system to consume keys when dragging is active. This can also be used to
            // modify the drag state on key press. Example, cancel drag on escape key.
            mDragWindowHandle.focusable = true;
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
            ProtoLog.d(WM_DEBUG_ORIENTATION, "Pausing rotation during drag");
            mService.mRoot.forAllDisplays(dc -> {
                dc.getDisplayRotation().pause();
            });
        }

        void tearDown() {
            mService.mInputManager.removeInputChannel(mClientChannel.getToken());
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
            mClientChannel.dispose();
            mClientChannel = null;

            mDragWindowHandle = null;
            mDragApplicationHandle = null;

            // Resume rotations after a drag.
            ProtoLog.d(WM_DEBUG_ORIENTATION, "Resuming rotation after drag");
            mService.mRoot.forAllDisplays(dc -> {
                dc.getDisplayRotation().resume();
            });
        }
    }

    InputChannel getInputChannel() {
        return mInputInterceptor == null ? null : mInputInterceptor.mClientChannel;
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

        final boolean containsAppExtras = containsApplicationExtras(mDataDescription);
        mService.mRoot.forAllWindows(w -> {
            sendDragStartedLocked(w, touchX, touchY, containsAppExtras);
        }, false /* traverseTopToBottom */);
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
            boolean containsAppExtras) {
        final boolean interceptsGlobalDrag = targetInterceptsGlobalDrag(newWin);
        if (mDragInProgress && isValidDropTarget(newWin, containsAppExtras, interceptsGlobalDrag)) {
            // Only allow the extras to be dispatched to a global-intercepting drag target
            ClipData data = interceptsGlobalDrag ? mData.copyForTransferWithActivityInfo() : null;
            DragEvent event = obtainDragEvent(DragEvent.ACTION_DRAG_STARTED, touchX, touchY,
                    data, false /* includeDragSurface */,
                    null /* dragAndDropPermission */);
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

    /**
     * Returns true if this is a drag of an application mime type.
     */
    private boolean containsApplicationExtras(ClipDescription desc) {
        if (desc == null) {
            return false;
        }
        return desc.hasMimeType(MIMETYPE_APPLICATION_ACTIVITY)
                || desc.hasMimeType(MIMETYPE_APPLICATION_SHORTCUT)
                || desc.hasMimeType(MIMETYPE_APPLICATION_TASK);
    }

    private boolean isValidDropTarget(WindowState targetWin, boolean containsAppExtras,
            boolean interceptsGlobalDrag) {
        if (targetWin == null) {
            return false;
        }
        final boolean isLocalWindow = mLocalWin == targetWin.mClient.asBinder();
        if (!isLocalWindow && !interceptsGlobalDrag && containsAppExtras) {
            // App-drags can only go to local windows or windows that can intercept global drag, and
            // not to other app windows
            return false;
        }
        if (!targetWin.isPotentialDragTarget(interceptsGlobalDrag)) {
            return false;
        }
        if ((mFlags & View.DRAG_FLAG_GLOBAL) == 0 || !targetWindowSupportsGlobalDrag(targetWin)) {
            // Drag is limited to the current window.
            if (!isLocalWindow) {
                return false;
            }
        }

        return interceptsGlobalDrag
                || mCrossProfileCopyAllowed
                || mSourceUserId == UserHandle.getUserId(targetWin.getOwningUid());
    }

    private boolean targetWindowSupportsGlobalDrag(WindowState targetWin) {
        // Global drags are limited to system windows, and windows for apps that are targeting N and
        // above.
        return targetWin.mActivityRecord == null
                || targetWin.mActivityRecord.mTargetSdk >= Build.VERSION_CODES.N;
    }

    /**
     * @return whether the given window {@param targetWin} can intercept global drags.
     */
    public boolean targetInterceptsGlobalDrag(WindowState targetWin) {
        return (targetWin.mAttrs.privateFlags & PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP) != 0;
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
            sendDragStartedLocked(newWin, mCurrentX, mCurrentY,
                    containsApplicationExtras(mDataDescription));
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

    void updateDragSurfaceLocked(boolean keepHandling, float x, float y) {
        if (mAnimator != null) {
            return;
        }
        mCurrentX = x;
        mCurrentY = y;

        if (!keepHandling) {
            return;
        }

        // Move the surface to the given touch
        if (SHOW_LIGHT_TRANSACTIONS) {
            Slog.i(TAG_WM, ">>> OPEN TRANSACTION notifyMoveLocked");
        }
        mTransaction.setPosition(mSurfaceControl, x - mThumbOffsetX, y - mThumbOffsetY).apply();
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "DRAG %s: pos=(%d,%d)", mSurfaceControl,
                (int) (x - mThumbOffsetX), (int) (y - mThumbOffsetY));
    }

    /**
     * Returns true if it has sent DRAG_STARTED broadcast out but has not been sent DRAG_END
     * broadcast.
     */
    boolean isInProgress() {
        return mDragInProgress;
    }

    private DragEvent obtainDragEvent(int action, float x, float y, ClipData data,
            boolean includeDragSurface, IDragAndDropPermissions dragAndDropPermissions) {
        return DragEvent.obtain(action, x, y, mThumbOffsetX, mThumbOffsetY,
                null  /* localState */, mDataDescription, data,
                includeDragSurface ? mSurfaceControl : null,
                dragAndDropPermissions, false /* result */);
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
