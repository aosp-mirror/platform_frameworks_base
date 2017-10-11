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

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DRAG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Point;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.IUserManager;
import android.util.Slog;
import android.view.Display;
import android.view.DragEvent;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.PointerIcon;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.Transformation;
import android.view.animation.TranslateAnimation;

import com.android.server.input.InputApplicationHandle;
import com.android.server.input.InputWindowHandle;
import com.android.server.wm.WindowManagerService.DragInputEventReceiver;
import com.android.server.wm.WindowManagerService.H;

import com.android.internal.view.IDragAndDropPermissions;

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

    final WindowManagerService mService;
    IBinder mToken;
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
    DisplayContent mDisplayContent;

    private Animation mAnimation;
    final Transformation mTransformation = new Transformation();
    private final Interpolator mCubicEaseOutInterpolator = new DecelerateInterpolator(1.5f);
    private Point mDisplaySize = new Point();

    DragState(WindowManagerService service, IBinder token, SurfaceControl surface,
            int flags, IBinder localWin) {
        mService = service;
        mToken = token;
        mSurfaceControl = surface;
        mFlags = flags;
        mLocalWin = localWin;
        mNotifiedWindows = new ArrayList<WindowState>();
    }

    void reset() {
        if (mSurfaceControl != null) {
            mSurfaceControl.destroy();
        }
        mSurfaceControl = null;
        mFlags = 0;
        mLocalWin = null;
        mToken = null;
        mData = null;
        mThumbOffsetX = mThumbOffsetY = 0;
        mNotifiedWindows = null;
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
            mInputEventReceiver = mService.new DragInputEventReceiver(mClientChannel,
                    mService.mH.getLooper());

            mDragApplicationHandle = new InputApplicationHandle(null);
            mDragApplicationHandle.name = "drag";
            mDragApplicationHandle.dispatchingTimeoutNanos =
                    WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;

            mDragWindowHandle = new InputWindowHandle(mDragApplicationHandle, null, null,
                    display.getDisplayId());
            mDragWindowHandle.name = "drag";
            mDragWindowHandle.inputChannel = mServerChannel;
            mDragWindowHandle.layer = getDragLayerLw();
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
            mService.pauseRotationLocked();
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
            mService.resumeRotationLocked();
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
            mService.mInputMonitor.updateInputWindowsLw(true /*force*/);
        }
    }

    void unregister() {
        if (DEBUG_DRAG) Slog.d(TAG_WM, "unregistering drag input channel");
        if (mInputInterceptor == null) {
            Slog.e(TAG_WM, "Unregister of nonexistent drag input channel");
        } else {
            // Input channel should be disposed on the thread where the input is being handled.
            mService.mH.obtainMessage(
                    H.TEAR_DOWN_DRAG_AND_DROP_INPUT, mInputInterceptor).sendToTarget();
            mInputInterceptor = null;
            mService.mInputMonitor.updateInputWindowsLw(true /*force*/);
        }
    }

    int getDragLayerLw() {
        return mService.mPolicy.getWindowLayerFromTypeLw(WindowManager.LayoutParams.TYPE_DRAG)
                * WindowManagerService.TYPE_LAYER_MULTIPLIER
                + WindowManagerService.TYPE_LAYER_OFFSET;
    }

    /* call out to each visible window/session informing it about the drag
     */
    void broadcastDragStartedLw(final float touchX, final float touchY) {
        mOriginalX = mCurrentX = touchX;
        mOriginalY = mCurrentY = touchY;

        // Cache a base-class instance of the clip metadata so that parceling
        // works correctly in calling out to the apps.
        mDataDescription = (mData != null) ? mData.getDescription() : null;
        mNotifiedWindows.clear();
        mDragInProgress = true;

        mSourceUserId = UserHandle.getUserId(mUid);

        final IUserManager userManager =
                (IUserManager) ServiceManager.getService(Context.USER_SERVICE);
        try {
            mCrossProfileCopyAllowed = !userManager.getUserRestrictions(mSourceUserId).getBoolean(
                    UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE);
        } catch (RemoteException e) {
            Slog.e(TAG_WM, "Remote Exception calling UserManager: " + e);
            mCrossProfileCopyAllowed = false;
        }

        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "broadcasting DRAG_STARTED at (" + touchX + ", " + touchY + ")");
        }

        mDisplayContent.forAllWindows(w -> {
            sendDragStartedLw(w, touchX, touchY, mDataDescription);
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
    private void sendDragStartedLw(WindowState newWin, float touchX, float touchY,
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
    void sendDragStartedIfNeededLw(WindowState newWin) {
        if (mDragInProgress) {
            // If we have sent the drag-started, we needn't do so again
            if (isWindowNotified(newWin)) {
                return;
            }
            if (DEBUG_DRAG) {
                Slog.d(TAG_WM, "need to send DRAG_STARTED to new window " + newWin);
            }
            sendDragStartedLw(newWin, mCurrentX, mCurrentY, mDataDescription);
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

    private void broadcastDragEndedLw() {
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

    void endDragLw() {
        if (mAnimation != null) {
            return;
        }
        if (!mDragResult) {
            mAnimation = createReturnAnimationLocked();
            mService.scheduleAnimationLocked();
            return;  // Will call cleanUpDragLw when the animation is done.
        }
        cleanUpDragLw();
    }

    void cancelDragLw() {
        if (mAnimation != null) {
            return;
        }
        mAnimation = createCancelAnimationLocked();
        mService.scheduleAnimationLocked();
    }

    private void cleanUpDragLw() {
        broadcastDragEndedLw();
        if (isFromSource(InputDevice.SOURCE_MOUSE)) {
            mService.restorePointerIconLocked(mDisplayContent, mCurrentX, mCurrentY);
        }

        // stop intercepting input
        unregister();

        // free our resources and drop all the object references
        reset();
        mService.mDragState = null;
    }

    void notifyMoveLw(float x, float y) {
        if (mAnimation != null) {
            return;
        }
        mCurrentX = x;
        mCurrentY = y;

        // Move the surface to the given touch
        if (SHOW_LIGHT_TRANSACTIONS) Slog.i(
                TAG_WM, ">>> OPEN TRANSACTION notifyMoveLw");
        mService.openSurfaceTransaction();
        try {
            mSurfaceControl.setPosition(x - mThumbOffsetX, y - mThumbOffsetY);
            if (SHOW_TRANSACTIONS) Slog.i(TAG_WM, "  DRAG "
                    + mSurfaceControl + ": pos=(" +
                    (int)(x - mThumbOffsetX) + "," + (int)(y - mThumbOffsetY) + ")");
        } finally {
            mService.closeSurfaceTransaction();
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(
                    TAG_WM, "<<< CLOSE TRANSACTION notifyMoveLw");
        }
        notifyLocationLw(x, y);
    }

    void notifyLocationLw(float x, float y) {
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

    // Find the drop target and tell it about the data.  Returns 'true' if we can immediately
    // dispatch the global drag-ended message, 'false' if we need to wait for a
    // result from the recipient.
    boolean notifyDropLw(float x, float y) {
        if (mAnimation != null) {
            return false;
        }
        mCurrentX = x;
        mCurrentY = y;

        WindowState touchedWin = mDisplayContent.getTouchableWinAtPointLocked(x, y);

        if (!isWindowNotified(touchedWin)) {
            // "drop" outside a valid window -- no recipient to apply a
            // timeout to, and we can send the drag-ended message immediately.
            mDragResult = false;
            return true;
        }

        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "sending DROP to " + touchedWin);
        }

        final int targetUserId = UserHandle.getUserId(touchedWin.getOwningUid());

        DragAndDropPermissionsHandler dragAndDropPermissions = null;
        if ((mFlags & View.DRAG_FLAG_GLOBAL) != 0 &&
                (mFlags & DRAG_FLAGS_URI_ACCESS) != 0) {
            dragAndDropPermissions = new DragAndDropPermissionsHandler(
                    mData,
                    mUid,
                    touchedWin.getOwningPackage(),
                    mFlags & DRAG_FLAGS_URI_PERMISSIONS,
                    mSourceUserId,
                    targetUserId);
        }
        if (mSourceUserId != targetUserId){
            mData.fixUris(mSourceUserId);
        }
        final int myPid = Process.myPid();
        final IBinder token = touchedWin.mClient.asBinder();
        DragEvent evt = obtainDragEvent(touchedWin, DragEvent.ACTION_DROP, x, y,
                null, null, mData, dragAndDropPermissions, false);
        try {
            touchedWin.mClient.dispatchDragEvent(evt);

            // 5 second timeout for this window to respond to the drop
            mService.mH.removeMessages(H.DRAG_END_TIMEOUT, token);
            Message msg = mService.mH.obtainMessage(H.DRAG_END_TIMEOUT, token);
            mService.mH.sendMessageDelayed(msg, 5000);
        } catch (RemoteException e) {
            Slog.w(TAG_WM, "can't send drop notification to win " + touchedWin);
            return true;
        } finally {
            if (myPid != touchedWin.mSession.mPid) {
                evt.recycle();
            }
        }
        mToken = token;
        return false;
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

    boolean stepAnimationLocked(long currentTimeMs) {
        if (mAnimation == null) {
            return false;
        }

        mTransformation.clear();
        if (!mAnimation.getTransformation(currentTimeMs, mTransformation)) {
            cleanUpDragLw();
            return false;
        }

        mTransformation.getMatrix().postTranslate(
                mCurrentX - mThumbOffsetX, mCurrentY - mThumbOffsetY);
        final float tmpFloats[] = mService.mTmpFloats;
        mTransformation.getMatrix().getValues(tmpFloats);
        mSurfaceControl.setPosition(tmpFloats[Matrix.MTRANS_X], tmpFloats[Matrix.MTRANS_Y]);
        mSurfaceControl.setAlpha(mTransformation.getAlpha());
        mSurfaceControl.setMatrix(tmpFloats[Matrix.MSCALE_X], tmpFloats[Matrix.MSKEW_Y],
                tmpFloats[Matrix.MSKEW_X], tmpFloats[Matrix.MSCALE_Y]);
        return true;
    }

    private Animation createReturnAnimationLocked() {
        final AnimationSet set = new AnimationSet(false);
        final float translateX = mOriginalX - mCurrentX;
        final float translateY = mOriginalY - mCurrentY;
        set.addAnimation(new TranslateAnimation( 0, translateX, 0, translateY));
        set.addAnimation(new AlphaAnimation(mOriginalAlpha, mOriginalAlpha / 2));
        // Adjust the duration to the travel distance.
        final double travelDistance = Math.sqrt(translateX * translateX + translateY * translateY);
        final double displayDiagonal =
                Math.sqrt(mDisplaySize.x * mDisplaySize.x + mDisplaySize.y * mDisplaySize.y);
        final long duration = MIN_ANIMATION_DURATION_MS + (long) (travelDistance / displayDiagonal
                * (MAX_ANIMATION_DURATION_MS - MIN_ANIMATION_DURATION_MS));
        set.setDuration(duration);
        set.setInterpolator(mCubicEaseOutInterpolator);
        set.initialize(0, 0, 0, 0);
        set.start();  // Will start on the first call to getTransformation.
        return set;
    }

    private Animation createCancelAnimationLocked() {
        final AnimationSet set = new AnimationSet(false);
        set.addAnimation(new ScaleAnimation(1, 0, 1, 0, mThumbOffsetX, mThumbOffsetY));
        set.addAnimation(new AlphaAnimation(mOriginalAlpha, 0));
        set.setDuration(MIN_ANIMATION_DURATION_MS);
        set.setInterpolator(mCubicEaseOutInterpolator);
        set.initialize(0, 0, 0, 0);
        set.start();  // Will start on the first call to getTransformation.
        return set;
    }

    private boolean isFromSource(int source) {
        return (mTouchSource & source) == source;
    }

    void overridePointerIconLw(int touchSource) {
        mTouchSource = touchSource;
        if (isFromSource(InputDevice.SOURCE_MOUSE)) {
            InputManager.getInstance().setPointerIconType(PointerIcon.TYPE_GRABBING);
        }
    }
}
