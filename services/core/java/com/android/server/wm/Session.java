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

import static android.Manifest.permission.DEVICE_POWER;
import static android.Manifest.permission.HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.isSystemAlertWindowType;

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_POSITIONING;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.content.ClipData;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.view.DisplayCutout;
import android.view.IWindow;
import android.view.IWindowId;
import android.view.IWindowSession;
import android.view.IWindowSessionCallback;
import android.view.InputChannel;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManager;

import com.android.internal.os.logging.MetricsLoggerWrapper;
import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * This class represents an active client session.  There is generally one
 * Session object per process that is interacting with the window manager.
 */
class Session extends IWindowSession.Stub implements IBinder.DeathRecipient {
    final WindowManagerService mService;
    final IWindowSessionCallback mCallback;
    final int mUid;
    final int mPid;
    private final String mStringName;
    SurfaceSession mSurfaceSession;
    private int mNumWindow = 0;
    // Set of visible application overlay window surfaces connected to this session.
    private final Set<WindowSurfaceController> mAppOverlaySurfaces = new HashSet<>();
    // Set of visible alert window surfaces connected to this session.
    private final Set<WindowSurfaceController> mAlertWindowSurfaces = new HashSet<>();
    private final DragDropController mDragDropController;
    final boolean mCanAddInternalSystemWindow;
    final boolean mCanHideNonSystemOverlayWindows;
    final boolean mCanAcquireSleepToken;
    private AlertWindowNotification mAlertWindowNotification;
    private boolean mShowingAlertWindowNotificationAllowed;
    private boolean mClientDead = false;
    private float mLastReportedAnimatorScale;
    private String mPackageName;
    private String mRelayoutTag;

    public Session(WindowManagerService service, IWindowSessionCallback callback) {
        mService = service;
        mCallback = callback;
        mUid = Binder.getCallingUid();
        mPid = Binder.getCallingPid();
        mLastReportedAnimatorScale = service.getCurrentAnimatorScale();
        mCanAddInternalSystemWindow = service.mContext.checkCallingOrSelfPermission(
                INTERNAL_SYSTEM_WINDOW) == PERMISSION_GRANTED;
        mCanHideNonSystemOverlayWindows = service.mContext.checkCallingOrSelfPermission(
                HIDE_NON_SYSTEM_OVERLAY_WINDOWS) == PERMISSION_GRANTED;
        mCanAcquireSleepToken = service.mContext.checkCallingOrSelfPermission(DEVICE_POWER)
                == PERMISSION_GRANTED;
        mShowingAlertWindowNotificationAllowed = mService.mShowAlertWindowNotifications;
        mDragDropController = mService.mDragDropController;
        StringBuilder sb = new StringBuilder();
        sb.append("Session{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" ");
        sb.append(mPid);
        if (mUid < Process.FIRST_APPLICATION_UID) {
            sb.append(":");
            sb.append(mUid);
        } else {
            sb.append(":u");
            sb.append(UserHandle.getUserId(mUid));
            sb.append('a');
            sb.append(UserHandle.getAppId(mUid));
        }
        sb.append("}");
        mStringName = sb.toString();

        try {
            mCallback.asBinder().linkToDeath(this, 0);
        } catch (RemoteException e) {
            // The caller has died, so we can just forget about this.
            // Hmmm, should we call killSessionLocked()??
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            // Log all 'real' exceptions thrown to the caller
            if (!(e instanceof SecurityException)) {
                Slog.wtf(TAG_WM, "Window Session Crash", e);
            }
            throw e;
        }
    }

    @Override
    public void binderDied() {
        synchronized (mService.mGlobalLock) {
            mCallback.asBinder().unlinkToDeath(this, 0);
            mClientDead = true;
            killSessionLocked();
        }
    }

    @Override
    public int addToDisplay(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, Rect outFrame, Rect outContentInsets,
            Rect outStableInsets, Rect outOutsets,
            DisplayCutout.ParcelableWrapper outDisplayCutout, InputChannel outInputChannel,
            InsetsState outInsetsState) {
        return mService.addWindow(this, window, seq, attrs, viewVisibility, displayId, outFrame,
                outContentInsets, outStableInsets, outOutsets, outDisplayCutout, outInputChannel,
                outInsetsState);
    }

    @Override
    public int addToDisplayWithoutInputChannel(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, Rect outContentInsets, Rect outStableInsets,
            InsetsState outInsetsState) {
        return mService.addWindow(this, window, seq, attrs, viewVisibility, displayId,
                new Rect() /* outFrame */, outContentInsets, outStableInsets, null /* outOutsets */,
                new DisplayCutout.ParcelableWrapper() /* cutout */, null /* outInputChannel */,
                outInsetsState);
    }

    @Override
    public void remove(IWindow window) {
        mService.removeWindow(this, window);
    }

    @Override
    public void prepareToReplaceWindows(IBinder appToken, boolean childrenOnly) {
        mService.setWillReplaceWindows(appToken, childrenOnly);
    }

    @Override
    public int relayout(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewFlags, int flags, long frameNumber,
            Rect outFrame, Rect outOverscanInsets, Rect outContentInsets, Rect outVisibleInsets,
            Rect outStableInsets, Rect outsets, Rect outBackdropFrame,
            DisplayCutout.ParcelableWrapper cutout, MergedConfiguration mergedConfiguration,
            SurfaceControl outSurfaceControl, InsetsState outInsetsState) {
        if (false) Slog.d(TAG_WM, ">>>>>> ENTERED relayout from "
                + Binder.getCallingPid());
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, mRelayoutTag);
        int res = mService.relayoutWindow(this, window, seq, attrs,
                requestedWidth, requestedHeight, viewFlags, flags, frameNumber,
                outFrame, outOverscanInsets, outContentInsets, outVisibleInsets,
                outStableInsets, outsets, outBackdropFrame, cutout,
                mergedConfiguration, outSurfaceControl, outInsetsState);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        if (false) Slog.d(TAG_WM, "<<<<<< EXITING relayout to "
                + Binder.getCallingPid());
        return res;
    }

    @Override
    public boolean outOfMemory(IWindow window) {
        return mService.outOfMemoryWindow(this, window);
    }

    @Override
    public void setTransparentRegion(IWindow window, Region region) {
        mService.setTransparentRegionWindow(this, window, region);
    }

    @Override
    public void setInsets(IWindow window, int touchableInsets,
            Rect contentInsets, Rect visibleInsets, Region touchableArea) {
        mService.setInsetsWindow(this, window, touchableInsets, contentInsets,
                visibleInsets, touchableArea);
    }

    @Override
    public void getDisplayFrame(IWindow window, Rect outDisplayFrame) {
        mService.getWindowDisplayFrame(this, window, outDisplayFrame);
    }

    @Override
    public void finishDrawing(IWindow window) {
        if (WindowManagerService.localLOGV) Slog.v(
            TAG_WM, "IWindow finishDrawing called for " + window);
        mService.finishDrawingWindow(this, window);
    }

    @Override
    public void setInTouchMode(boolean mode) {
        synchronized (mService.mGlobalLock) {
            mService.mInTouchMode = mode;
        }
    }

    @Override
    public boolean getInTouchMode() {
        synchronized (mService.mGlobalLock) {
            return mService.mInTouchMode;
        }
    }

    @Override
    public boolean performHapticFeedback(int effectId, boolean always) {
        long ident = Binder.clearCallingIdentity();
        try {
            return mService.mPolicy.performHapticFeedback(mUid, mPackageName,
                        effectId, always, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /* Drag/drop */

    @Override
    public IBinder performDrag(IWindow window, int flags, SurfaceControl surface, int touchSource,
            float touchX, float touchY, float thumbCenterX, float thumbCenterY, ClipData data) {
        final int callerPid = Binder.getCallingPid();
        final int callerUid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mDragDropController.performDrag(mSurfaceSession, callerPid, callerUid, window,
                    flags, surface, touchSource, touchX, touchY, thumbCenterX, thumbCenterY, data);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void reportDropResult(IWindow window, boolean consumed) {
        final long ident = Binder.clearCallingIdentity();
        try {
            mDragDropController.reportDropResult(window, consumed);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void cancelDragAndDrop(IBinder dragToken) {
        final long ident = Binder.clearCallingIdentity();
        try {
            mDragDropController.cancelDragAndDrop(dragToken);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void dragRecipientEntered(IWindow window) {
        mDragDropController.dragRecipientEntered(window);
    }

    @Override
    public void dragRecipientExited(IWindow window) {
        mDragDropController.dragRecipientExited(window);
    }

    @Override
    public boolean startMovingTask(IWindow window, float startX, float startY) {
        if (DEBUG_TASK_POSITIONING) Slog.d(
                TAG_WM, "startMovingTask: {" + startX + "," + startY + "}");

        long ident = Binder.clearCallingIdentity();
        try {
            return mService.mTaskPositioningController.startMovingTask(window, startX, startY);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void actionOnWallpaper(IBinder window,
            BiConsumer<WallpaperController, WindowState> action) {
        final WindowState windowState = mService.windowForClientLocked(this, window, true);
        action.accept(windowState.getDisplayContent().mWallpaperController, windowState);
    }

    @Override
    public void setWallpaperPosition(IBinder window, float x, float y, float xStep, float yStep) {
        synchronized (mService.mGlobalLock) {
            long ident = Binder.clearCallingIdentity();
            try {
                actionOnWallpaper(window, (wpController, windowState) ->
                        wpController.setWindowWallpaperPosition(windowState, x, y, xStep, yStep));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void wallpaperOffsetsComplete(IBinder window) {
        synchronized (mService.mGlobalLock) {
            actionOnWallpaper(window, (wpController, windowState) ->
                    wpController.wallpaperOffsetsComplete(window));
        }
    }

    @Override
    public void setWallpaperDisplayOffset(IBinder window, int x, int y) {
        synchronized (mService.mGlobalLock) {
            long ident = Binder.clearCallingIdentity();
            try {
                actionOnWallpaper(window, (wpController, windowState) ->
                        wpController.setWindowWallpaperDisplayOffset(windowState, x, y));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public Bundle sendWallpaperCommand(IBinder window, String action, int x, int y,
            int z, Bundle extras, boolean sync) {
        synchronized (mService.mGlobalLock) {
            long ident = Binder.clearCallingIdentity();
            try {
                final WindowState windowState = mService.windowForClientLocked(this, window, true);
                return windowState.getDisplayContent().mWallpaperController
                        .sendWindowWallpaperCommand(windowState, action, x, y, z, extras, sync);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void wallpaperCommandComplete(IBinder window, Bundle result) {
        synchronized (mService.mGlobalLock) {
            actionOnWallpaper(window, (wpController, windowState) ->
                    wpController.wallpaperCommandComplete(window));
        }
    }

    @Override
    public void onRectangleOnScreenRequested(IBinder token, Rect rectangle) {
        synchronized (mService.mGlobalLock) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mService.onRectangleOnScreenRequested(token, rectangle);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public IWindowId getWindowId(IBinder window) {
        return mService.getWindowId(window);
    }

    @Override
    public void pokeDrawLock(IBinder window) {
        final long identity = Binder.clearCallingIdentity();
        try {
            mService.pokeDrawLock(this, window);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void updatePointerIcon(IWindow window) {
        final long identity = Binder.clearCallingIdentity();
        try {
            mService.updatePointerIcon(window);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void updateTapExcludeRegion(IWindow window, int regionId, int left, int top, int width,
            int height) {
        final long identity = Binder.clearCallingIdentity();
        try {
            mService.updateTapExcludeRegion(window, regionId, left, top, width, height);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void insetsModified(IWindow window, InsetsState state) {
        synchronized (mService.mGlobalLock) {
            final WindowState windowState = mService.windowForClientLocked(this, window,
                    false /* throwOnError */);
            if (windowState != null) {
                windowState.getDisplayContent().getInsetsStateController().onInsetsModified(
                        windowState, state);
            }
        }
    }

    void windowAddedLocked(String packageName) {
        mPackageName = packageName;
        mRelayoutTag = "relayoutWindow: " + mPackageName;
        if (mSurfaceSession == null) {
            if (WindowManagerService.localLOGV) Slog.v(
                TAG_WM, "First window added to " + this + ", creating SurfaceSession");
            mSurfaceSession = new SurfaceSession();
            if (SHOW_TRANSACTIONS) Slog.i(
                    TAG_WM, "  NEW SURFACE SESSION " + mSurfaceSession);
            mService.mSessions.add(this);
            if (mLastReportedAnimatorScale != mService.getCurrentAnimatorScale()) {
                mService.dispatchNewAnimatorScaleLocked(this);
            }
        }
        mNumWindow++;
    }

    void windowRemovedLocked() {
        mNumWindow--;
        killSessionLocked();
    }


    void onWindowSurfaceVisibilityChanged(WindowSurfaceController surfaceController,
            boolean visible, int type) {

        if (!isSystemAlertWindowType(type)) {
            return;
        }

        boolean changed;

        if (!mCanAddInternalSystemWindow) {
            // We want to track non-system signature apps adding alert windows so we can post an
            // on-going notification for the user to control their visibility.
            if (visible) {
                changed = mAlertWindowSurfaces.add(surfaceController);
                MetricsLoggerWrapper.logAppOverlayEnter(mUid, mPackageName, changed, type, true);
            } else {
                changed = mAlertWindowSurfaces.remove(surfaceController);
                MetricsLoggerWrapper.logAppOverlayExit(mUid, mPackageName, changed, type, true);
            }

            if (changed) {
                if (mAlertWindowSurfaces.isEmpty()) {
                    cancelAlertWindowNotification();
                } else if (mAlertWindowNotification == null){
                    mAlertWindowNotification = new AlertWindowNotification(mService, mPackageName);
                    if (mShowingAlertWindowNotificationAllowed) {
                        mAlertWindowNotification.post();
                    }
                }
            }
        }

        if (type != TYPE_APPLICATION_OVERLAY) {
            return;
        }

        if (visible) {
            changed = mAppOverlaySurfaces.add(surfaceController);
            MetricsLoggerWrapper.logAppOverlayEnter(mUid, mPackageName, changed, type, false);
        } else {
            changed = mAppOverlaySurfaces.remove(surfaceController);
            MetricsLoggerWrapper.logAppOverlayExit(mUid, mPackageName, changed, type, false);
        }

        if (changed) {
            // Notify activity manager of changes to app overlay windows so it can adjust the
            // importance score for the process.
            setHasOverlayUi(!mAppOverlaySurfaces.isEmpty());
        }
    }

    void setShowingAlertWindowNotificationAllowed(boolean allowed) {
        mShowingAlertWindowNotificationAllowed = allowed;
        if (mAlertWindowNotification != null) {
            if (allowed) {
                mAlertWindowNotification.post();
            } else {
                mAlertWindowNotification.cancel(false /* deleteChannel */);
            }
        }
    }

    private void killSessionLocked() {
        if (mNumWindow > 0 || !mClientDead) {
            return;
        }

        mService.mSessions.remove(this);
        if (mSurfaceSession == null) {
            return;
        }

        if (WindowManagerService.localLOGV) Slog.v(TAG_WM, "Last window removed from " + this
                + ", destroying " + mSurfaceSession);
        if (SHOW_TRANSACTIONS) Slog.i(TAG_WM, "  KILL SURFACE SESSION " + mSurfaceSession);
        try {
            mSurfaceSession.kill();
        } catch (Exception e) {
            Slog.w(TAG_WM, "Exception thrown when killing surface session " + mSurfaceSession
                    + " in session " + this + ": " + e.toString());
        }
        mSurfaceSession = null;
        mAlertWindowSurfaces.clear();
        mAppOverlaySurfaces.clear();
        setHasOverlayUi(false);
        cancelAlertWindowNotification();
    }

    private void setHasOverlayUi(boolean hasOverlayUi) {
        mService.mH.obtainMessage(H.SET_HAS_OVERLAY_UI, mPid, hasOverlayUi ? 1 : 0).sendToTarget();
    }

    private void cancelAlertWindowNotification() {
        if (mAlertWindowNotification == null) {
            return;
        }
        mAlertWindowNotification.cancel(true /* deleteChannel */);
        mAlertWindowNotification = null;
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mNumWindow="); pw.print(mNumWindow);
                pw.print(" mCanAddInternalSystemWindow="); pw.print(mCanAddInternalSystemWindow);
                pw.print(" mAppOverlaySurfaces="); pw.print(mAppOverlaySurfaces);
                pw.print(" mAlertWindowSurfaces="); pw.print(mAlertWindowSurfaces);
                pw.print(" mClientDead="); pw.print(mClientDead);
                pw.print(" mSurfaceSession="); pw.println(mSurfaceSession);
        pw.print(prefix); pw.print("mPackageName="); pw.println(mPackageName);
    }

    @Override
    public String toString() {
        return mStringName;
    }

    boolean hasAlertWindowSurfaces() {
        return !mAlertWindowSurfaces.isEmpty();
    }
}
