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

import com.android.server.wm.WindowManagerService.H;

import android.content.ClipData;
import android.content.ClipDescription;
import android.graphics.Region;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.util.Slog;
import android.view.DragEvent;
import android.view.InputChannel;
import android.view.InputQueue;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;

import java.util.ArrayList;

/**
 * Drag/drop state
 */
class DragState {
    final WindowManagerService mService;
    IBinder mToken;
    Surface mSurface;
    int mFlags;
    IBinder mLocalWin;
    ClipData mData;
    ClipDescription mDataDescription;
    boolean mDragResult;
    float mCurrentX, mCurrentY;
    float mThumbOffsetX, mThumbOffsetY;
    InputChannel mServerChannel, mClientChannel;
    InputApplicationHandle mDragApplicationHandle;
    InputWindowHandle mDragWindowHandle;
    WindowState mTargetWindow;
    ArrayList<WindowState> mNotifiedWindows;
    boolean mDragInProgress;

    private final Region mTmpRegion = new Region();

    DragState(WindowManagerService service, IBinder token, Surface surface,
            int flags, IBinder localWin) {
        mService = service;
        mToken = token;
        mSurface = surface;
        mFlags = flags;
        mLocalWin = localWin;
        mNotifiedWindows = new ArrayList<WindowState>();
    }

    void reset() {
        if (mSurface != null) {
            mSurface.destroy();
        }
        mSurface = null;
        mFlags = 0;
        mLocalWin = null;
        mToken = null;
        mData = null;
        mThumbOffsetX = mThumbOffsetY = 0;
        mNotifiedWindows = null;
    }

    void register() {
        if (WindowManagerService.DEBUG_DRAG) Slog.d(WindowManagerService.TAG, "registering drag input channel");
        if (mClientChannel != null) {
            Slog.e(WindowManagerService.TAG, "Duplicate register of drag input channel");
        } else {
            InputChannel[] channels = InputChannel.openInputChannelPair("drag");
            mServerChannel = channels[0];
            mClientChannel = channels[1];
            mService.mInputManager.registerInputChannel(mServerChannel, null);
            InputQueue.registerInputChannel(mClientChannel, mService.mDragInputHandler,
                    mService.mH.getLooper().getQueue());

            mDragApplicationHandle = new InputApplicationHandle(null);
            mDragApplicationHandle.name = "drag";
            mDragApplicationHandle.dispatchingTimeoutNanos =
                    WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;

            mDragWindowHandle = new InputWindowHandle(mDragApplicationHandle, null);
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
            mDragWindowHandle.frameRight = mService.mCurDisplayWidth;
            mDragWindowHandle.frameBottom = mService.mCurDisplayHeight;
        }
    }

    void unregister() {
        if (WindowManagerService.DEBUG_DRAG) Slog.d(WindowManagerService.TAG, "unregistering drag input channel");
        if (mClientChannel == null) {
            Slog.e(WindowManagerService.TAG, "Unregister of nonexistent drag input channel");
        } else {
            mService.mInputManager.unregisterInputChannel(mServerChannel);
            InputQueue.unregisterInputChannel(mClientChannel);
            mClientChannel.dispose();
            mServerChannel.dispose();
            mClientChannel = null;
            mServerChannel = null;

            mDragWindowHandle = null;
            mDragApplicationHandle = null;
        }
    }

    int getDragLayerLw() {
        return mService.mPolicy.windowTypeToLayerLw(WindowManager.LayoutParams.TYPE_DRAG)
                * WindowManagerService.TYPE_LAYER_MULTIPLIER
                + WindowManagerService.TYPE_LAYER_OFFSET;
    }

    /* call out to each visible window/session informing it about the drag
     */
    void broadcastDragStartedLw(final float touchX, final float touchY) {
        // Cache a base-class instance of the clip metadata so that parceling
        // works correctly in calling out to the apps.
        mDataDescription = (mData != null) ? mData.getDescription() : null;
        mNotifiedWindows.clear();
        mDragInProgress = true;

        if (WindowManagerService.DEBUG_DRAG) {
            Slog.d(WindowManagerService.TAG, "broadcasting DRAG_STARTED at (" + touchX + ", " + touchY + ")");
        }

        final int N = mService.mWindows.size();
        for (int i = 0; i < N; i++) {
            sendDragStartedLw(mService.mWindows.get(i), touchX, touchY, mDataDescription);
        }
    }

    /* helper - send a caller-provided event, presumed to be DRAG_STARTED, if the
     * designated window is potentially a drop recipient.  There are race situations
     * around DRAG_ENDED broadcast, so we make sure that once we've declared that
     * the drag has ended, we never send out another DRAG_STARTED for this drag action.
     *
     * This method clones the 'event' parameter if it's being delivered to the same
     * process, so it's safe for the caller to call recycle() on the event afterwards.
     */
    private void sendDragStartedLw(WindowState newWin, float touchX, float touchY,
            ClipDescription desc) {
        // Don't actually send the event if the drag is supposed to be pinned
        // to the originating window but 'newWin' is not that window.
        if ((mFlags & View.DRAG_FLAG_GLOBAL) == 0) {
            final IBinder winBinder = newWin.mClient.asBinder();
            if (winBinder != mLocalWin) {
                if (WindowManagerService.DEBUG_DRAG) {
                    Slog.d(WindowManagerService.TAG, "Not dispatching local DRAG_STARTED to " + newWin);
                }
                return;
            }
        }

        if (mDragInProgress && newWin.isPotentialDragTarget()) {
            DragEvent event = obtainDragEvent(newWin, DragEvent.ACTION_DRAG_STARTED,
                    touchX, touchY, null, desc, null, false);
            try {
                newWin.mClient.dispatchDragEvent(event);
                // track each window that we've notified that the drag is starting
                mNotifiedWindows.add(newWin);
            } catch (RemoteException e) {
                Slog.w(WindowManagerService.TAG, "Unable to drag-start window " + newWin);
            } finally {
                // if the callee was local, the dispatch has already recycled the event
                if (Process.myPid() != newWin.mSession.mPid) {
                    event.recycle();
                }
            }
        }
    }

    /* helper - construct and send a DRAG_STARTED event only if the window has not
     * previously been notified, i.e. it became visible after the drag operation
     * was begun.  This is a rare case.
     */
    void sendDragStartedIfNeededLw(WindowState newWin) {
        if (mDragInProgress) {
            // If we have sent the drag-started, we needn't do so again
            for (WindowState ws : mNotifiedWindows) {
                if (ws == newWin) {
                    return;
                }
            }
            if (WindowManagerService.DEBUG_DRAG) {
                Slog.d(WindowManagerService.TAG, "need to send DRAG_STARTED to new window " + newWin);
            }
            sendDragStartedLw(newWin, mCurrentX, mCurrentY, mDataDescription);
        }
    }

    void broadcastDragEndedLw() {
        if (WindowManagerService.DEBUG_DRAG) {
            Slog.d(WindowManagerService.TAG, "broadcasting DRAG_ENDED");
        }
        DragEvent evt = DragEvent.obtain(DragEvent.ACTION_DRAG_ENDED,
                0, 0, null, null, null, mDragResult);
        for (WindowState ws: mNotifiedWindows) {
            try {
                ws.mClient.dispatchDragEvent(evt);
            } catch (RemoteException e) {
                Slog.w(WindowManagerService.TAG, "Unable to drag-end window " + ws);
            }
        }
        mNotifiedWindows.clear();
        mDragInProgress = false;
        evt.recycle();
    }

    void endDragLw() {
        mService.mDragState.broadcastDragEndedLw();

        // stop intercepting input
        mService.mDragState.unregister();
        mService.mInputMonitor.updateInputWindowsLw(true /*force*/);

        // free our resources and drop all the object references
        mService.mDragState.reset();
        mService.mDragState = null;

        if (WindowManagerService.DEBUG_ORIENTATION) Slog.d(WindowManagerService.TAG, "Performing post-drag rotation");
        boolean changed = mService.setRotationUncheckedLocked(
                WindowManagerPolicy.USE_LAST_ROTATION, 0, false);
        if (changed) {
            mService.mH.sendEmptyMessage(H.SEND_NEW_CONFIGURATION);
        }
    }

    void notifyMoveLw(float x, float y) {
        final int myPid = Process.myPid();

        // Move the surface to the given touch
        if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG, ">>> OPEN TRANSACTION notifyMoveLw");
        Surface.openTransaction();
        try {
            mSurface.setPosition(x - mThumbOffsetX, y - mThumbOffsetY);
            if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG, "  DRAG "
                    + mSurface + ": pos=(" +
                    (int)(x - mThumbOffsetX) + "," + (int)(y - mThumbOffsetY) + ")");
        } finally {
            Surface.closeTransaction();
            if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG, "<<< CLOSE TRANSACTION notifyMoveLw");
        }

        // Tell the affected window
        WindowState touchedWin = getTouchedWinAtPointLw(x, y);
        if (touchedWin == null) {
            if (WindowManagerService.DEBUG_DRAG) Slog.d(WindowManagerService.TAG, "No touched win at x=" + x + " y=" + y);
            return;
        }
        if ((mFlags & View.DRAG_FLAG_GLOBAL) == 0) {
            final IBinder touchedBinder = touchedWin.mClient.asBinder();
            if (touchedBinder != mLocalWin) {
                // This drag is pinned only to the originating window, but the drag
                // point is outside that window.  Pretend it's over empty space.
                touchedWin = null;
            }
        }
        try {
            // have we dragged over a new window?
            if ((touchedWin != mTargetWindow) && (mTargetWindow != null)) {
                if (WindowManagerService.DEBUG_DRAG) {
                    Slog.d(WindowManagerService.TAG, "sending DRAG_EXITED to " + mTargetWindow);
                }
                // force DRAG_EXITED_EVENT if appropriate
                DragEvent evt = obtainDragEvent(mTargetWindow, DragEvent.ACTION_DRAG_EXITED,
                        x, y, null, null, null, false);
                mTargetWindow.mClient.dispatchDragEvent(evt);
                if (myPid != mTargetWindow.mSession.mPid) {
                    evt.recycle();
                }
            }
            if (touchedWin != null) {
                if (false && WindowManagerService.DEBUG_DRAG) {
                    Slog.d(WindowManagerService.TAG, "sending DRAG_LOCATION to " + touchedWin);
                }
                DragEvent evt = obtainDragEvent(touchedWin, DragEvent.ACTION_DRAG_LOCATION,
                        x, y, null, null, null, false);
                touchedWin.mClient.dispatchDragEvent(evt);
                if (myPid != touchedWin.mSession.mPid) {
                    evt.recycle();
                }
            }
        } catch (RemoteException e) {
            Slog.w(WindowManagerService.TAG, "can't send drag notification to windows");
        }
        mTargetWindow = touchedWin;
    }

    // Tell the drop target about the data.  Returns 'true' if we can immediately
    // dispatch the global drag-ended message, 'false' if we need to wait for a
    // result from the recipient.
    boolean notifyDropLw(float x, float y) {
        WindowState touchedWin = getTouchedWinAtPointLw(x, y);
        if (touchedWin == null) {
            // "drop" outside a valid window -- no recipient to apply a
            // timeout to, and we can send the drag-ended message immediately.
            mDragResult = false;
            return true;
        }

        if (WindowManagerService.DEBUG_DRAG) {
            Slog.d(WindowManagerService.TAG, "sending DROP to " + touchedWin);
        }
        final int myPid = Process.myPid();
        final IBinder token = touchedWin.mClient.asBinder();
        DragEvent evt = obtainDragEvent(touchedWin, DragEvent.ACTION_DROP, x, y,
                null, null, mData, false);
        try {
            touchedWin.mClient.dispatchDragEvent(evt);

            // 5 second timeout for this window to respond to the drop
            mService.mH.removeMessages(H.DRAG_END_TIMEOUT, token);
            Message msg = mService.mH.obtainMessage(H.DRAG_END_TIMEOUT, token);
            mService.mH.sendMessageDelayed(msg, 5000);
        } catch (RemoteException e) {
            Slog.w(WindowManagerService.TAG, "can't send drop notification to win " + touchedWin);
            return true;
        } finally {
            if (myPid != touchedWin.mSession.mPid) {
                evt.recycle();
            }
        }
        mToken = token;
        return false;
    }

    // Find the visible, touch-deliverable window under the given point
    private WindowState getTouchedWinAtPointLw(float xf, float yf) {
        WindowState touchedWin = null;
        final int x = (int) xf;
        final int y = (int) yf;
        final ArrayList<WindowState> windows = mService.mWindows;
        final int N = windows.size();
        for (int i = N - 1; i >= 0; i--) {
            WindowState child = windows.get(i);
            final int flags = child.mAttrs.flags;
            if (!child.isVisibleLw()) {
                // not visible == don't tell about drags
                continue;
            }
            if ((flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
                // not touchable == don't tell about drags
                continue;
            }

            child.getTouchableRegion(mTmpRegion);

            final int touchFlags = flags &
                    (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            if (mTmpRegion.contains(x, y) || touchFlags == 0) {
                // Found it
                touchedWin = child;
                break;
            }
        }

        return touchedWin;
    }

    private static DragEvent obtainDragEvent(WindowState win, int action,
            float x, float y, Object localState,
            ClipDescription description, ClipData data, boolean result) {
        float winX = x - win.mFrame.left;
        float winY = y - win.mFrame.top;
        if (win.mEnforceSizeCompat) {
            winX *= win.mGlobalScale;
            winY *= win.mGlobalScale;
        }
        return DragEvent.obtain(action, winX, winY, localState, description, data, result);
    }
}