/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.SurfaceControl.METADATA_OWNER_PID;
import static android.view.SurfaceControl.METADATA_OWNER_UID;
import static android.view.SurfaceControl.METADATA_WINDOW_TYPE;

import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_SURFACE_ALLOC;
import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowSurfaceControllerProto.SHOWN;

import android.os.Debug;
import android.os.Trace;
import android.util.EventLog;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.view.WindowContentFrameStats;

import com.android.internal.protolog.ProtoLog;

import java.io.PrintWriter;

class WindowSurfaceController {
    static final String TAG = TAG_WITH_CLASS_NAME ? "WindowSurfaceController" : TAG_WM;

    final WindowStateAnimator mAnimator;

    SurfaceControl mSurfaceControl;

    // Should only be set from within setShown().
    private boolean mSurfaceShown = false;

    private final String title;

    private final WindowManagerService mService;

    private final int mWindowType;
    private final Session mWindowSession;


    WindowSurfaceController(String name, int format, int flags, WindowStateAnimator animator,
            int windowType) {
        mAnimator = animator;

        title = name;

        mService = animator.mService;
        final WindowState win = animator.mWin;
        mWindowType = windowType;
        mWindowSession = win.mSession;

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "new SurfaceControl");
        mSurfaceControl = win.makeSurface()
                .setParent(win.getSurfaceControl())
                .setName(name)
                .setFormat(format)
                .setFlags(flags)
                .setMetadata(METADATA_WINDOW_TYPE, windowType)
                .setMetadata(METADATA_OWNER_UID, mWindowSession.mUid)
                .setMetadata(METADATA_OWNER_PID, mWindowSession.mPid)
                .setCallsite("WindowSurfaceController")
                .setBLASTLayer().build();

        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    void hide(SurfaceControl.Transaction transaction, String reason) {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE HIDE ( %s ): %s", reason, title);

        if (mSurfaceShown) {
            hideSurface(transaction);
        }
    }

    private void hideSurface(SurfaceControl.Transaction transaction) {
        if (mSurfaceControl == null) {
            return;
        }
        setShown(false);
        try {
            transaction.hide(mSurfaceControl);
            if (mAnimator.mIsWallpaper) {
                final DisplayContent dc = mAnimator.mWin.getDisplayContent();
                EventLog.writeEvent(EventLogTags.WM_WALLPAPER_SURFACE,
                        dc.mDisplayId, 0 /* request hidden */,
                        String.valueOf(dc.mWallpaperController.getWallpaperTarget()));
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception hiding surface in " + this);
        }
    }

    void destroy(SurfaceControl.Transaction t) {
        ProtoLog.i(WM_SHOW_SURFACE_ALLOC,
                "Destroying surface %s called by %s", this, Debug.getCallers(8));
        try {
            if (mSurfaceControl != null) {
                if (mAnimator.mIsWallpaper && !mAnimator.mWin.mWindowRemovalAllowed
                        && !mAnimator.mWin.mRemoveOnExit) {
                    // The wallpaper surface should have the same lifetime as its window.
                    Slog.e(TAG, "Unexpected removing wallpaper surface of " + mAnimator.mWin
                            + " by " + Debug.getCallers(8));
                }
                t.remove(mSurfaceControl);
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error destroying surface in: " + this, e);
        } finally {
            setShown(false);
            mSurfaceControl = null;
        }
    }

    boolean prepareToShowInTransaction(SurfaceControl.Transaction t, float alpha) {
        if (mSurfaceControl == null) {
            return false;
        }

        t.setAlpha(mSurfaceControl, alpha);
        return true;
    }

    void setOpaque(boolean isOpaque) {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE isOpaque=%b: %s", isOpaque, title);

        if (mSurfaceControl == null) {
            return;
        }

        mAnimator.mWin.getPendingTransaction().setOpaque(mSurfaceControl, isOpaque);
        mService.scheduleAnimationLocked();
    }

    void setColorSpaceAgnostic(SurfaceControl.Transaction t, boolean agnostic) {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE isColorSpaceAgnostic=%b: %s", agnostic, title);

        if (mSurfaceControl == null) {
            return;
        }
        t.setColorSpaceAgnostic(mSurfaceControl, agnostic);
    }

    void showRobustly(SurfaceControl.Transaction t) {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE SHOW (performLayout): %s", title);
        if (DEBUG_VISIBILITY) Slog.v(TAG, "Showing " + this
                + " during relayout");

        if (mSurfaceShown) {
            return;
        }

        setShown(true);
        t.show(mSurfaceControl);
        if (mAnimator.mIsWallpaper) {
            final DisplayContent dc = mAnimator.mWin.getDisplayContent();
            EventLog.writeEvent(EventLogTags.WM_WALLPAPER_SURFACE,
                    dc.mDisplayId, 1 /* request shown */,
                    String.valueOf(dc.mWallpaperController.getWallpaperTarget()));
        }
    }

    boolean clearWindowContentFrameStats() {
        if (mSurfaceControl == null) {
            return false;
        }
        return mSurfaceControl.clearContentFrameStats();
    }

    boolean getWindowContentFrameStats(WindowContentFrameStats outStats) {
        if (mSurfaceControl == null) {
            return false;
        }
        return mSurfaceControl.getContentFrameStats(outStats);
    }

    boolean hasSurface() {
        return mSurfaceControl != null;
    }

    void getSurfaceControl(SurfaceControl outSurfaceControl) {
        outSurfaceControl.copyFrom(mSurfaceControl, "WindowSurfaceController.getSurfaceControl");
    }

    boolean getShown() {
        return mSurfaceShown;
    }

    void setShown(boolean surfaceShown) {
        mSurfaceShown = surfaceShown;

        mService.updateNonSystemOverlayWindowsVisibilityIfNeeded(mAnimator.mWin, surfaceShown);

        mAnimator.mWin.onSurfaceShownChanged(surfaceShown);

        if (mWindowSession != null) {
            mWindowSession.onWindowSurfaceVisibilityChanged(this, mSurfaceShown, mWindowType);
        }
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(SHOWN, mSurfaceShown);
        proto.end(token);
    }

    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        if (dumpAll) {
            pw.print(prefix); pw.print("mSurface="); pw.println(mSurfaceControl);
        }
        pw.print(prefix); pw.print("Surface: shown="); pw.print(mSurfaceShown);
    }

    @Override
    public String toString() {
        return mSurfaceControl.toString();
    }
}
