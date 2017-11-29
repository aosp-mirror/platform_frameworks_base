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
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DRAG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_POSITIONING;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.content.ClipData;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;
import android.os.UserHandle;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.view.Display;
import android.view.IWindow;
import android.view.IWindowId;
import android.view.IWindowSession;
import android.view.IWindowSessionCallback;
import android.view.InputChannel;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManager;

import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

/**
 * This class represents an active client session.  There is generally one
 * Session object per process that is interacting with the window manager.
 */
// Needs to be public and not final so we can mock during tests...sucks I know :(
public class Session extends IWindowSession.Stub
        implements IBinder.DeathRecipient {
    final WindowManagerService mService;
    final IWindowSessionCallback mCallback;
    final IInputMethodClient mClient;
    final int mUid;
    final int mPid;
    private final String mStringName;
    SurfaceSession mSurfaceSession;
    private int mNumWindow = 0;
    // Set of visible application overlay window surfaces connected to this session.
    private final Set<WindowSurfaceController> mAppOverlaySurfaces = new HashSet<>();
    // Set of visible alert window surfaces connected to this session.
    private final Set<WindowSurfaceController> mAlertWindowSurfaces = new HashSet<>();
    final boolean mCanAddInternalSystemWindow;
    final boolean mCanHideNonSystemOverlayWindows;
    final boolean mCanAcquireSleepToken;
    private AlertWindowNotification mAlertWindowNotification;
    private boolean mShowingAlertWindowNotificationAllowed;
    private boolean mClientDead = false;
    private float mLastReportedAnimatorScale;
    private String mPackageName;
    private String mRelayoutTag;

    public Session(WindowManagerService service, IWindowSessionCallback callback,
            IInputMethodClient client, IInputContext inputContext) {
        mService = service;
        mCallback = callback;
        mClient = client;
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

        synchronized (mService.mWindowMap) {
            if (mService.mInputMethodManager == null && mService.mHaveInputMethods) {
                IBinder b = ServiceManager.getService(
                        Context.INPUT_METHOD_SERVICE);
                mService.mInputMethodManager = IInputMethodManager.Stub.asInterface(b);
            }
        }
        long ident = Binder.clearCallingIdentity();
        try {
            // Note: it is safe to call in to the input method manager
            // here because we are not holding our lock.
            if (mService.mInputMethodManager != null) {
                mService.mInputMethodManager.addClient(client, inputContext,
                        mUid, mPid);
            } else {
                client.setUsingInputMethod(false);
            }
            client.asBinder().linkToDeath(this, 0);
        } catch (RemoteException e) {
            // The caller has died, so we can just forget about this.
            try {
                if (mService.mInputMethodManager != null) {
                    mService.mInputMethodManager.removeClient(client);
                }
            } catch (RemoteException ee) {
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
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
        // Note: it is safe to call in to the input method manager
        // here because we are not holding our lock.
        try {
            if (mService.mInputMethodManager != null) {
                mService.mInputMethodManager.removeClient(mClient);
            }
        } catch (RemoteException e) {
        }
        synchronized(mService.mWindowMap) {
            mClient.asBinder().unlinkToDeath(this, 0);
            mClientDead = true;
            killSessionLocked();
        }
    }

    @Override
    public int add(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int viewVisibility, Rect outContentInsets, Rect outStableInsets,
            InputChannel outInputChannel) {
        return addToDisplay(window, seq, attrs, viewVisibility, Display.DEFAULT_DISPLAY,
                outContentInsets, outStableInsets, null /* outOutsets */, outInputChannel);
    }

    @Override
    public int addToDisplay(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, Rect outContentInsets, Rect outStableInsets,
            Rect outOutsets, InputChannel outInputChannel) {
        return mService.addWindow(this, window, seq, attrs, viewVisibility, displayId,
                outContentInsets, outStableInsets, outOutsets, outInputChannel);
    }

    @Override
    public int addWithoutInputChannel(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int viewVisibility, Rect outContentInsets, Rect outStableInsets) {
        return addToDisplayWithoutInputChannel(window, seq, attrs, viewVisibility,
                Display.DEFAULT_DISPLAY, outContentInsets, outStableInsets);
    }

    @Override
    public int addToDisplayWithoutInputChannel(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, Rect outContentInsets, Rect outStableInsets) {
        return mService.addWindow(this, window, seq, attrs, viewVisibility, displayId,
            outContentInsets, outStableInsets, null /* outOutsets */, null);
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
            int requestedWidth, int requestedHeight, int viewFlags,
            int flags, Rect outFrame, Rect outOverscanInsets, Rect outContentInsets,
            Rect outVisibleInsets, Rect outStableInsets, Rect outsets, Rect outBackdropFrame,
            MergedConfiguration mergedConfiguration, Surface outSurface) {
        if (false) Slog.d(TAG_WM, ">>>>>> ENTERED relayout from "
                + Binder.getCallingPid());
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, mRelayoutTag);
        int res = mService.relayoutWindow(this, window, seq, attrs,
                requestedWidth, requestedHeight, viewFlags, flags,
                outFrame, outOverscanInsets, outContentInsets, outVisibleInsets,
                outStableInsets, outsets, outBackdropFrame, mergedConfiguration, outSurface);
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
        synchronized(mService.mWindowMap) {
            mService.mInTouchMode = mode;
        }
    }

    @Override
    public boolean getInTouchMode() {
        synchronized(mService.mWindowMap) {
            return mService.mInTouchMode;
        }
    }

    @Override
    public boolean performHapticFeedback(IWindow window, int effectId,
            boolean always) {
        synchronized(mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                return mService.mPolicy.performHapticFeedbackLw(
                        mService.windowForClientLocked(this, window, true),
                        effectId, always);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /* Drag/drop */
    @Override
    public IBinder prepareDrag(IWindow window, int flags,
            int width, int height, Surface outSurface) {
        return mService.prepareDragSurface(window, mSurfaceSession, flags,
                width, height, outSurface);
    }

    @Override
    public boolean performDrag(IWindow window, IBinder dragToken,
            int touchSource, float touchX, float touchY, float thumbCenterX, float thumbCenterY,
            ClipData data) {
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "perform drag: win=" + window + " data=" + data);
        }

        synchronized (mService.mWindowMap) {
            if (mService.mDragState == null) {
                Slog.w(TAG_WM, "No drag prepared");
                throw new IllegalStateException("performDrag() without prepareDrag()");
            }

            if (dragToken != mService.mDragState.mToken) {
                Slog.w(TAG_WM, "Performing mismatched drag");
                throw new IllegalStateException("performDrag() does not match prepareDrag()");
            }

            WindowState callingWin = mService.windowForClientLocked(null, window, false);
            if (callingWin == null) {
                Slog.w(TAG_WM, "Bad requesting window " + window);
                return false;  // !!! TODO: throw here?
            }

            // !!! TODO: if input is not still focused on the initiating window, fail
            // the drag initiation (e.g. an alarm window popped up just as the application
            // called performDrag()

            mService.mH.removeMessages(H.DRAG_START_TIMEOUT, window.asBinder());

            // !!! TODO: extract the current touch (x, y) in screen coordinates.  That
            // will let us eliminate the (touchX,touchY) parameters from the API.

            // !!! FIXME: put all this heavy stuff onto the mH looper, as well as
            // the actual drag event dispatch stuff in the dragstate

            final DisplayContent displayContent = callingWin.getDisplayContent();
            if (displayContent == null) {
               return false;
            }
            Display display = displayContent.getDisplay();
            mService.mDragState.register(display);
            if (!mService.mInputManager.transferTouchFocus(callingWin.mInputChannel,
                    mService.mDragState.getInputChannel())) {
                Slog.e(TAG_WM, "Unable to transfer touch focus");
                mService.mDragState.unregister();
                mService.mDragState.reset();
                mService.mDragState = null;
                return false;
            }

            mService.mDragState.mDisplayContent = displayContent;
            mService.mDragState.mData = data;
            mService.mDragState.broadcastDragStartedLw(touchX, touchY);
            mService.mDragState.overridePointerIconLw(touchSource);

            // remember the thumb offsets for later
            mService.mDragState.mThumbOffsetX = thumbCenterX;
            mService.mDragState.mThumbOffsetY = thumbCenterY;

            // Make the surface visible at the proper location
            final SurfaceControl surfaceControl = mService.mDragState.mSurfaceControl;
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(
                    TAG_WM, ">>> OPEN TRANSACTION performDrag");
            mService.openSurfaceTransaction();
            try {
                surfaceControl.setPosition(touchX - thumbCenterX,
                        touchY - thumbCenterY);
                surfaceControl.setLayer(mService.mDragState.getDragLayerLw());
                surfaceControl.setLayerStack(display.getLayerStack());
                surfaceControl.show();
            } finally {
                mService.closeSurfaceTransaction();
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(
                        TAG_WM, "<<< CLOSE TRANSACTION performDrag");
            }

            mService.mDragState.notifyLocationLw(touchX, touchY);
        }

        return true;    // success!
    }

    @Override
    public boolean startMovingTask(IWindow window, float startX, float startY) {
        if (DEBUG_TASK_POSITIONING) Slog.d(
                TAG_WM, "startMovingTask: {" + startX + "," + startY + "}");

        long ident = Binder.clearCallingIdentity();
        try {
            return mService.startMovingTask(window, startX, startY);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void reportDropResult(IWindow window, boolean consumed) {
        IBinder token = window.asBinder();
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "Drop result=" + consumed + " reported by " + token);
        }

        synchronized (mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                if (mService.mDragState == null) {
                    // Most likely the drop recipient ANRed and we ended the drag
                    // out from under it.  Log the issue and move on.
                    Slog.w(TAG_WM, "Drop result given but no drag in progress");
                    return;
                }

                if (mService.mDragState.mToken != token) {
                    // We're in a drag, but the wrong window has responded.
                    Slog.w(TAG_WM, "Invalid drop-result claim by " + window);
                    throw new IllegalStateException("reportDropResult() by non-recipient");
                }

                // The right window has responded, even if it's no longer around,
                // so be sure to halt the timeout even if the later WindowState
                // lookup fails.
                mService.mH.removeMessages(H.DRAG_END_TIMEOUT, window.asBinder());
                WindowState callingWin = mService.windowForClientLocked(null, window, false);
                if (callingWin == null) {
                    Slog.w(TAG_WM, "Bad result-reporting window " + window);
                    return;  // !!! TODO: throw here?
                }

                mService.mDragState.mDragResult = consumed;
                mService.mDragState.endDragLw();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void cancelDragAndDrop(IBinder dragToken) {
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "cancelDragAndDrop");
        }

        synchronized (mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                if (mService.mDragState == null) {
                    Slog.w(TAG_WM, "cancelDragAndDrop() without prepareDrag()");
                    throw new IllegalStateException("cancelDragAndDrop() without prepareDrag()");
                }

                if (mService.mDragState.mToken != dragToken) {
                    Slog.w(TAG_WM,
                            "cancelDragAndDrop() does not match prepareDrag()");
                    throw new IllegalStateException(
                            "cancelDragAndDrop() does not match prepareDrag()");
                }

                mService.mDragState.mDragResult = false;
                mService.mDragState.cancelDragLw();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void dragRecipientEntered(IWindow window) {
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "Drag into new candidate view @ " + window.asBinder());
        }
    }

    @Override
    public void dragRecipientExited(IWindow window) {
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "Drag from old candidate view @ " + window.asBinder());
        }
    }

    @Override
    public void setWallpaperPosition(IBinder window, float x, float y, float xStep, float yStep) {
        synchronized(mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                mService.mRoot.mWallpaperController.setWindowWallpaperPosition(
                        mService.windowForClientLocked(this, window, true),
                        x, y, xStep, yStep);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void wallpaperOffsetsComplete(IBinder window) {
        synchronized (mService.mWindowMap) {
            mService.mRoot.mWallpaperController.wallpaperOffsetsComplete(window);
        }
    }

    @Override
    public void setWallpaperDisplayOffset(IBinder window, int x, int y) {
        synchronized(mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                mService.mRoot.mWallpaperController.setWindowWallpaperDisplayOffset(
                        mService.windowForClientLocked(this, window, true), x, y);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public Bundle sendWallpaperCommand(IBinder window, String action, int x, int y,
            int z, Bundle extras, boolean sync) {
        synchronized(mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                return mService.mRoot.mWallpaperController.sendWindowWallpaperCommand(
                        mService.windowForClientLocked(this, window, true),
                        action, x, y, z, extras, sync);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void wallpaperCommandComplete(IBinder window, Bundle result) {
        synchronized (mService.mWindowMap) {
            mService.mRoot.mWallpaperController.wallpaperCommandComplete(window);
        }
    }

    @Override
    public void onRectangleOnScreenRequested(IBinder token, Rect rectangle) {
        synchronized(mService.mWindowMap) {
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
            } else {
                changed = mAlertWindowSurfaces.remove(surfaceController);
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
        } else {
            changed = mAppOverlaySurfaces.remove(surfaceController);
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
                mAlertWindowNotification.cancel();
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
        mAlertWindowNotification.cancel();
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
