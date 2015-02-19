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

import android.view.IWindowId;
import android.view.IWindowSessionCallback;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.server.wm.WindowManagerService.H;

import android.content.ClipData;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;
import android.view.Display;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.InputChannel;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManager;

import java.io.PrintWriter;

/**
 * This class represents an active client session.  There is generally one
 * Session object per process that is interacting with the window manager.
 */
final class Session extends IWindowSession.Stub
        implements IBinder.DeathRecipient {
    final WindowManagerService mService;
    final IWindowSessionCallback mCallback;
    final IInputMethodClient mClient;
    final IInputContext mInputContext;
    final int mUid;
    final int mPid;
    final String mStringName;
    SurfaceSession mSurfaceSession;
    int mNumWindow = 0;
    boolean mClientDead = false;
    float mLastReportedAnimatorScale;

    public Session(WindowManagerService service, IWindowSessionCallback callback,
            IInputMethodClient client, IInputContext inputContext) {
        mService = service;
        mCallback = callback;
        mClient = client;
        mInputContext = inputContext;
        mUid = Binder.getCallingUid();
        mPid = Binder.getCallingPid();
        mLastReportedAnimatorScale = service.getCurrentAnimatorScale();
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
                Slog.wtf(WindowManagerService.TAG, "Window Session Crash", e);
            }
            throw e;
        }
    }

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
                outContentInsets, outStableInsets, outInputChannel);
    }

    @Override
    public int addToDisplay(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, Rect outContentInsets, Rect outStableInsets,
            InputChannel outInputChannel) {
        return mService.addWindow(this, window, seq, attrs, viewVisibility, displayId,
                outContentInsets, outStableInsets, outInputChannel);
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
            outContentInsets, outStableInsets, null);
    }

    public void remove(IWindow window) {
        mService.removeWindow(this, window);
    }

    public int relayout(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewFlags,
            int flags, Rect outFrame, Rect outOverscanInsets, Rect outContentInsets,
            Rect outVisibleInsets, Rect outStableInsets, Configuration outConfig,
            Surface outSurface) {
        if (false) Slog.d(WindowManagerService.TAG, ">>>>>> ENTERED relayout from "
                + Binder.getCallingPid());
        int res = mService.relayoutWindow(this, window, seq, attrs,
                requestedWidth, requestedHeight, viewFlags, flags,
                outFrame, outOverscanInsets, outContentInsets, outVisibleInsets,
                outStableInsets, outConfig, outSurface);
        if (false) Slog.d(WindowManagerService.TAG, "<<<<<< EXITING relayout to "
                + Binder.getCallingPid());
        return res;
    }

    public void performDeferredDestroy(IWindow window) {
        mService.performDeferredDestroyWindow(this, window);
    }

    public boolean outOfMemory(IWindow window) {
        return mService.outOfMemoryWindow(this, window);
    }

    public void setTransparentRegion(IWindow window, Region region) {
        mService.setTransparentRegionWindow(this, window, region);
    }

    public void setInsets(IWindow window, int touchableInsets,
            Rect contentInsets, Rect visibleInsets, Region touchableArea) {
        mService.setInsetsWindow(this, window, touchableInsets, contentInsets,
                visibleInsets, touchableArea);
    }

    public void getDisplayFrame(IWindow window, Rect outDisplayFrame) {
        mService.getWindowDisplayFrame(this, window, outDisplayFrame);
    }

    public void finishDrawing(IWindow window) {
        if (WindowManagerService.localLOGV) Slog.v(
            WindowManagerService.TAG, "IWindow finishDrawing called for " + window);
        mService.finishDrawingWindow(this, window);
    }

    public void setInTouchMode(boolean mode) {
        synchronized(mService.mWindowMap) {
            mService.mInTouchMode = mode;
        }
    }

    public boolean getInTouchMode() {
        synchronized(mService.mWindowMap) {
            return mService.mInTouchMode;
        }
    }

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
    public IBinder prepareDrag(IWindow window, int flags,
            int width, int height, Surface outSurface) {
        return mService.prepareDragSurface(window, mSurfaceSession, flags,
                width, height, outSurface);
    }

    public boolean performDrag(IWindow window, IBinder dragToken,
            float touchX, float touchY, float thumbCenterX, float thumbCenterY,
            ClipData data) {
        if (WindowManagerService.DEBUG_DRAG) {
            Slog.d(WindowManagerService.TAG, "perform drag: win=" + window + " data=" + data);
        }

        synchronized (mService.mWindowMap) {
            if (mService.mDragState == null) {
                Slog.w(WindowManagerService.TAG, "No drag prepared");
                throw new IllegalStateException("performDrag() without prepareDrag()");
            }

            if (dragToken != mService.mDragState.mToken) {
                Slog.w(WindowManagerService.TAG, "Performing mismatched drag");
                throw new IllegalStateException("performDrag() does not match prepareDrag()");
            }

            WindowState callingWin = mService.windowForClientLocked(null, window, false);
            if (callingWin == null) {
                Slog.w(WindowManagerService.TAG, "Bad requesting window " + window);
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
            mService.mInputMonitor.updateInputWindowsLw(true /*force*/);
            if (!mService.mInputManager.transferTouchFocus(callingWin.mInputChannel,
                    mService.mDragState.mServerChannel)) {
                Slog.e(WindowManagerService.TAG, "Unable to transfer touch focus");
                mService.mDragState.unregister();
                mService.mDragState = null;
                mService.mInputMonitor.updateInputWindowsLw(true /*force*/);
                return false;
            }

            mService.mDragState.mData = data;
            mService.mDragState.mCurrentX = touchX;
            mService.mDragState.mCurrentY = touchY;
            mService.mDragState.broadcastDragStartedLw(touchX, touchY);

            // remember the thumb offsets for later
            mService.mDragState.mThumbOffsetX = thumbCenterX;
            mService.mDragState.mThumbOffsetY = thumbCenterY;

            // Make the surface visible at the proper location
            final SurfaceControl surfaceControl = mService.mDragState.mSurfaceControl;
            if (WindowManagerService.SHOW_LIGHT_TRANSACTIONS) Slog.i(
                    WindowManagerService.TAG, ">>> OPEN TRANSACTION performDrag");
            SurfaceControl.openTransaction();
            try {
                surfaceControl.setPosition(touchX - thumbCenterX,
                        touchY - thumbCenterY);
                surfaceControl.setAlpha(.7071f);
                surfaceControl.setLayer(mService.mDragState.getDragLayerLw());
                surfaceControl.setLayerStack(display.getLayerStack());
                surfaceControl.show();
            } finally {
                SurfaceControl.closeTransaction();
                if (WindowManagerService.SHOW_LIGHT_TRANSACTIONS) Slog.i(
                        WindowManagerService.TAG, "<<< CLOSE TRANSACTION performDrag");
            }
        }

        return true;    // success!
    }

    public void reportDropResult(IWindow window, boolean consumed) {
        IBinder token = window.asBinder();
        if (WindowManagerService.DEBUG_DRAG) {
            Slog.d(WindowManagerService.TAG, "Drop result=" + consumed + " reported by " + token);
        }

        synchronized (mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                if (mService.mDragState == null) {
                    // Most likely the drop recipient ANRed and we ended the drag
                    // out from under it.  Log the issue and move on.
                    Slog.w(WindowManagerService.TAG, "Drop result given but no drag in progress");
                    return;
                }

                if (mService.mDragState.mToken != token) {
                    // We're in a drag, but the wrong window has responded.
                    Slog.w(WindowManagerService.TAG, "Invalid drop-result claim by " + window);
                    throw new IllegalStateException("reportDropResult() by non-recipient");
                }

                // The right window has responded, even if it's no longer around,
                // so be sure to halt the timeout even if the later WindowState
                // lookup fails.
                mService.mH.removeMessages(H.DRAG_END_TIMEOUT, window.asBinder());
                WindowState callingWin = mService.windowForClientLocked(null, window, false);
                if (callingWin == null) {
                    Slog.w(WindowManagerService.TAG, "Bad result-reporting window " + window);
                    return;  // !!! TODO: throw here?
                }

                mService.mDragState.mDragResult = consumed;
                mService.mDragState.endDragLw();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public void dragRecipientEntered(IWindow window) {
        if (WindowManagerService.DEBUG_DRAG) {
            Slog.d(WindowManagerService.TAG, "Drag into new candidate view @ " + window.asBinder());
        }
    }

    public void dragRecipientExited(IWindow window) {
        if (WindowManagerService.DEBUG_DRAG) {
            Slog.d(WindowManagerService.TAG, "Drag from old candidate view @ " + window.asBinder());
        }
    }

    public void setWallpaperPosition(IBinder window, float x, float y, float xStep, float yStep) {
        synchronized(mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                mService.setWindowWallpaperPositionLocked(
                        mService.windowForClientLocked(this, window, true),
                        x, y, xStep, yStep);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public void wallpaperOffsetsComplete(IBinder window) {
        mService.wallpaperOffsetsComplete(window);
    }

    public void setWallpaperDisplayOffset(IBinder window, int x, int y) {
        synchronized(mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                mService.setWindowWallpaperDisplayOffsetLocked(
                        mService.windowForClientLocked(this, window, true), x, y);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public Bundle sendWallpaperCommand(IBinder window, String action, int x, int y,
            int z, Bundle extras, boolean sync) {
        synchronized(mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                return mService.sendWindowWallpaperCommandLocked(
                        mService.windowForClientLocked(this, window, true),
                        action, x, y, z, extras, sync);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public void wallpaperCommandComplete(IBinder window, Bundle result) {
        mService.wallpaperCommandComplete(window, result);
    }

    public void setUniverseTransform(IBinder window, float alpha, float offx, float offy,
            float dsdx, float dtdx, float dsdy, float dtdy) {
        synchronized(mService.mWindowMap) {
            long ident = Binder.clearCallingIdentity();
            try {
                mService.setUniverseTransformLocked(
                        mService.windowForClientLocked(this, window, true),
                        alpha, offx, offy, dsdx, dtdx, dsdy, dtdy);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

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

    public IWindowId getWindowId(IBinder window) {
        return mService.getWindowId(window);
    }

    void windowAddedLocked() {
        if (mSurfaceSession == null) {
            if (WindowManagerService.localLOGV) Slog.v(
                WindowManagerService.TAG, "First window added to " + this + ", creating SurfaceSession");
            mSurfaceSession = new SurfaceSession();
            if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(
                    WindowManagerService.TAG, "  NEW SURFACE SESSION " + mSurfaceSession);
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

    void killSessionLocked() {
        if (mNumWindow <= 0 && mClientDead) {
            mService.mSessions.remove(this);
            if (mSurfaceSession != null) {
                if (WindowManagerService.localLOGV) Slog.v(
                    WindowManagerService.TAG, "Last window removed from " + this
                    + ", destroying " + mSurfaceSession);
                if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(
                        WindowManagerService.TAG, "  KILL SURFACE SESSION " + mSurfaceSession);
                try {
                    mSurfaceSession.kill();
                } catch (Exception e) {
                    Slog.w(WindowManagerService.TAG, "Exception thrown when killing surface session "
                        + mSurfaceSession + " in session " + this
                        + ": " + e.toString());
                }
                mSurfaceSession = null;
            }
        }
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mNumWindow="); pw.print(mNumWindow);
                pw.print(" mClientDead="); pw.print(mClientDead);
                pw.print(" mSurfaceSession="); pw.println(mSurfaceSession);
    }

    @Override
    public String toString() {
        return mStringName;
    }
}