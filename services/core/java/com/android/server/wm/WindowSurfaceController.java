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
import static android.view.SurfaceControl.getGlobalTransaction;

import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_SURFACE_ALLOC;
import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowSurfaceControllerProto.LAYER;
import static com.android.server.wm.WindowSurfaceControllerProto.SHOWN;

import android.graphics.Region;
import android.os.Debug;
import android.os.Trace;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.view.WindowContentFrameStats;
import android.view.WindowManager;

import com.android.internal.protolog.common.ProtoLog;

import java.io.PrintWriter;

class WindowSurfaceController {
    static final String TAG = TAG_WITH_CLASS_NAME ? "WindowSurfaceController" : TAG_WM;

    final WindowStateAnimator mAnimator;

    SurfaceControl mSurfaceControl;

    // Should only be set from within setShown().
    private boolean mSurfaceShown = false;
    private float mSurfaceX = 0;
    private float mSurfaceY = 0;

    // Initialize to the identity matrix.
    private float mLastDsdx = 1;
    private float mLastDtdx = 0;
    private float mLastDsdy = 0;
    private float mLastDtdy = 1;

    private float mSurfaceAlpha = 0;

    private int mSurfaceLayer = 0;

    private final String title;

    private final WindowManagerService mService;

    private final int mWindowType;
    private final Session mWindowSession;

    // Used to track whether we have called detach children on the way to invisibility.
    boolean mChildrenDetached;

    WindowSurfaceController(String name, int w, int h, int format,
            int flags, WindowStateAnimator animator, int windowType) {
        mAnimator = animator;

        title = name;

        mService = animator.mService;
        final WindowState win = animator.mWin;
        mWindowType = windowType;
        mWindowSession = win.mSession;

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "new SurfaceControl");
        final SurfaceControl.Builder b = win.makeSurface()
                .setParent(win.getSurfaceControl())
                .setName(name)
                .setBufferSize(w, h)
                .setFormat(format)
                .setFlags(flags)
                .setMetadata(METADATA_WINDOW_TYPE, windowType)
                .setMetadata(METADATA_OWNER_UID, mWindowSession.mUid)
                .setMetadata(METADATA_OWNER_PID, mWindowSession.mPid)
                .setCallsite("WindowSurfaceController");

        final boolean useBLAST = mService.mUseBLAST && ((win.getAttrs().privateFlags
                & WindowManager.LayoutParams.PRIVATE_FLAG_USE_BLAST) != 0);

        if (useBLAST) {
            b.setBLASTLayer();
        }

        mSurfaceControl = b.build();

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
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception hiding surface in " + this);
        }
    }

    void destroy(SurfaceControl.Transaction t) {
        ProtoLog.i(WM_SHOW_SURFACE_ALLOC,
                "Destroying surface %s called by %s", this, Debug.getCallers(8));
        try {
            if (mSurfaceControl != null) {
                t.remove(mSurfaceControl);
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error destroying surface in: " + this, e);
        } finally {
            setShown(false);
            mSurfaceControl = null;
        }
    }

    void setPosition(SurfaceControl.Transaction t, float left, float top) {
        final boolean surfaceMoved = mSurfaceX != left || mSurfaceY != top;
        if (!surfaceMoved) {
            return;
        }

        mSurfaceX = left;
        mSurfaceY = top;

        ProtoLog.i(WM_SHOW_TRANSACTIONS,
                "SURFACE POS (setPositionInTransaction) @ (%f,%f): %s", left, top, title);

        t.setPosition(mSurfaceControl, left, top);
    }

    void setMatrix(SurfaceControl.Transaction t, float dsdx, float dtdx, float dtdy, float dsdy) {
        final boolean matrixChanged = mLastDsdx != dsdx || mLastDtdx != dtdx ||
                                      mLastDtdy != dtdy || mLastDsdy != dsdy;
        if (!matrixChanged) {
            return;
        }

        mLastDsdx = dsdx;
        mLastDtdx = dtdx;
        mLastDtdy = dtdy;
        mLastDsdy = dsdy;

        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE MATRIX [%f,%f,%f,%f]: %s",
                dsdx, dtdx, dtdy, dsdy, title);
        t.setMatrix(mSurfaceControl, dsdx, dtdx, dtdy, dsdy);
    }

    boolean prepareToShowInTransaction(SurfaceControl.Transaction t, float alpha) {
        if (mSurfaceControl == null) {
            return false;
        }

        mSurfaceAlpha = alpha;
        t.setAlpha(mSurfaceControl, alpha);
        return true;
    }

    void setOpaque(boolean isOpaque) {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE isOpaque=%b: %s", isOpaque, title);

        if (mSurfaceControl == null) {
            return;
        }
        if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION setOpaqueLocked");
        mService.openSurfaceTransaction();
        try {
            getGlobalTransaction().setOpaque(mSurfaceControl, isOpaque);
        } finally {
            mService.closeSurfaceTransaction("setOpaqueLocked");
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG, "<<< CLOSE TRANSACTION setOpaqueLocked");
        }
    }

    void setSecure(boolean isSecure) {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE isSecure=%b: %s", isSecure, title);

        if (mSurfaceControl == null) {
            return;
        }
        if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION setSecureLocked");
        mService.openSurfaceTransaction();
        try {
            getGlobalTransaction().setSecure(mSurfaceControl, isSecure);
        } finally {
            mService.closeSurfaceTransaction("setSecure");
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG, "<<< CLOSE TRANSACTION setSecureLocked");
        }
    }

    void setColorSpaceAgnostic(boolean agnostic) {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE isColorSpaceAgnostic=%b: %s", agnostic, title);

        if (mSurfaceControl == null) {
            return;
        }
        if (SHOW_LIGHT_TRANSACTIONS) {
            Slog.i(TAG, ">>> OPEN TRANSACTION setColorSpaceAgnosticLocked");
        }
        mService.openSurfaceTransaction();
        try {
            getGlobalTransaction().setColorSpaceAgnostic(mSurfaceControl, agnostic);
        } finally {
            mService.closeSurfaceTransaction("setColorSpaceAgnostic");
            if (SHOW_LIGHT_TRANSACTIONS) {
                Slog.i(TAG, "<<< CLOSE TRANSACTION setColorSpaceAgnosticLocked");
            }
        }
    }

    boolean showRobustly(SurfaceControl.Transaction t) {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE SHOW (performLayout): %s", title);
        if (DEBUG_VISIBILITY) Slog.v(TAG, "Showing " + this
                + " during relayout");

        if (mSurfaceShown) {
            return true;
        }

        setShown(true);
        t.show(mSurfaceControl);
        return true;
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
        proto.write(LAYER, mSurfaceLayer);
        proto.end(token);
    }

    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        if (dumpAll) {
            pw.print(prefix); pw.print("mSurface="); pw.println(mSurfaceControl);
        }
        pw.print(prefix); pw.print("Surface: shown="); pw.print(mSurfaceShown);
        pw.print(" layer="); pw.print(mSurfaceLayer);
        pw.print(" alpha="); pw.print(mSurfaceAlpha);
        pw.print(" rect=("); pw.print(mSurfaceX);
        pw.print(","); pw.print(mSurfaceY); pw.print(") ");
        pw.print(" transform=("); pw.print(mLastDsdx); pw.print(", ");
        pw.print(mLastDtdx); pw.print(", "); pw.print(mLastDsdy);
        pw.print(", "); pw.print(mLastDtdy); pw.println(")");
    }

    @Override
    public String toString() {
        return mSurfaceControl.toString();
    }
}
