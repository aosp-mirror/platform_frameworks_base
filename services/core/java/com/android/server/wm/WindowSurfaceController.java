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
import static android.view.Surface.SCALING_MODE_SCALE_TO_WINDOW;
import static android.view.SurfaceControl.METADATA_OWNER_UID;
import static android.view.SurfaceControl.METADATA_WINDOW_TYPE;

import static com.android.server.wm.ProtoLogGroup.WM_SHOW_SURFACE_ALLOC;
import static com.android.server.wm.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowSurfaceControllerProto.LAYER;
import static com.android.server.wm.WindowSurfaceControllerProto.SHOWN;

import android.graphics.Rect;
import android.graphics.Region;
import android.os.Debug;
import android.os.Trace;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.view.WindowContentFrameStats;
import android.view.WindowManager;

import com.android.server.protolog.common.ProtoLog;

import java.io.PrintWriter;

class WindowSurfaceController {
    static final String TAG = TAG_WITH_CLASS_NAME ? "WindowSurfaceController" : TAG_WM;

    final WindowStateAnimator mAnimator;

    SurfaceControl mSurfaceControl;

    /**
     * WM only uses for deferred transactions.
     */
    SurfaceControl mBLASTSurfaceControl;

    // Should only be set from within setShown().
    private boolean mSurfaceShown = false;
    private float mSurfaceX = 0;
    private float mSurfaceY = 0;
    private int mSurfaceW = 0;
    private int mSurfaceH = 0;
    private Rect mSurfaceCrop = new Rect(0, 0, -1, -1);

    // Initialize to the identity matrix.
    private float mLastDsdx = 1;
    private float mLastDtdx = 0;
    private float mLastDsdy = 0;
    private float mLastDtdy = 1;

    private float mSurfaceAlpha = 0;

    private int mSurfaceLayer = 0;

    // Surface flinger doesn't support crop rectangles where width or height is non-positive.
    // However, we need to somehow handle the situation where the cropping would completely hide
    // the window. We achieve this by explicitly hiding the surface and not letting it be shown.
    private boolean mHiddenForCrop = false;

    // Initially a surface is hidden after just being created.
    private boolean mHiddenForOtherReasons = true;
    private final String title;

    private final WindowManagerService mService;

    private final int mWindowType;
    private final Session mWindowSession;

    private final SurfaceControl.Transaction mTmpTransaction;

    // Used to track whether we have called detach children on the way to invisibility.
    boolean mChildrenDetached;

    WindowSurfaceController(String name, int w, int h, int format,
            int flags, WindowStateAnimator animator, int windowType, int ownerUid) {
        mAnimator = animator;

        mSurfaceW = w;
        mSurfaceH = h;

        title = name;

        mService = animator.mService;
        final WindowState win = animator.mWin;
        mWindowType = windowType;
        mWindowSession = win.mSession;
        mTmpTransaction = mService.mTransactionFactory.get();

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "new SurfaceControl");
        final SurfaceControl.Builder b = win.makeSurface()
                .setParent(win.getSurfaceControl())
                .setName(name)
                .setBufferSize(w, h)
                .setFormat(format)
                .setFlags(flags)
                .setMetadata(METADATA_WINDOW_TYPE, windowType)
                .setMetadata(METADATA_OWNER_UID, ownerUid)
                .setCallsite("WindowSurfaceController");

        final boolean useBLAST = mService.mUseBLAST && ((win.getAttrs().privateFlags &
                WindowManager.LayoutParams.PRIVATE_FLAG_USE_BLAST) != 0);
        if (useBLAST) {
            b.setContainerLayer();
        }

        mSurfaceControl = b.build();

        if (useBLAST) {
            mBLASTSurfaceControl = win.makeSurface()
                .setParent(mSurfaceControl)
                .setName(name + "(BLAST)")
                .setHidden(false)
                .setBLASTLayer()
                .setCallsite("WindowSurfaceController")
                .build();
        }

        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    void reparentChildrenInTransaction(WindowSurfaceController other) {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "REPARENT from: %s to: %s", this, other);
        if ((mSurfaceControl != null) && (other.mSurfaceControl != null)) {
            mSurfaceControl.reparentChildren(other.mSurfaceControl);
        }
    }

    void detachChildren() {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SEVER CHILDREN");
        mChildrenDetached = true;
        if (mSurfaceControl != null) {
            mSurfaceControl.detachChildren();
        }
    }

    void hide(SurfaceControl.Transaction transaction, String reason) {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE HIDE ( %s ): %s", reason, title);
        mHiddenForOtherReasons = true;

        mAnimator.destroyPreservedSurfaceLocked();
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

    void destroyNotInTransaction() {
        ProtoLog.i(WM_SHOW_SURFACE_ALLOC,
                    "Destroying surface %s called by %s", this, Debug.getCallers(8));
        try {
            if (mSurfaceControl != null) {
                mTmpTransaction.remove(mSurfaceControl).apply();
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error destroying surface in: " + this, e);
        } finally {
            setShown(false);
            mSurfaceControl = null;
            if (mBLASTSurfaceControl != null) {
                mBLASTSurfaceControl.release();
            }
        }
    }

    void setCropInTransaction(Rect clipRect, boolean recoveringMemory) {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE CROP %s: %s", clipRect.toShortString(), title);
        try {
            if (clipRect.width() > 0 && clipRect.height() > 0) {
                if (!clipRect.equals(mSurfaceCrop)) {
                    mSurfaceControl.setWindowCrop(clipRect);
                    mSurfaceCrop.set(clipRect);
                }
                mHiddenForCrop = false;
                updateVisibility();
            } else {
                mHiddenForCrop = true;
                mAnimator.destroyPreservedSurfaceLocked();
                updateVisibility();
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error setting crop surface of " + this
                    + " crop=" + clipRect.toShortString(), e);
            if (!recoveringMemory) {
                mAnimator.reclaimSomeSurfaceMemory("crop", true);
            }
        }
    }

    void clearCropInTransaction(boolean recoveringMemory) {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE CLEAR CROP: %s", title);
        try {
            Rect clipRect = new Rect(0, 0, -1, -1);
            if (mSurfaceCrop.equals(clipRect)) {
                return;
            }
            mSurfaceControl.setWindowCrop(clipRect);
            mSurfaceCrop.set(clipRect);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error setting clearing crop of " + this, e);
            if (!recoveringMemory) {
                mAnimator.reclaimSomeSurfaceMemory("crop", true);
            }
        }
    }

    void setPositionInTransaction(float left, float top, boolean recoveringMemory) {
        setPosition(null, left, top, recoveringMemory);
    }

    void setPosition(SurfaceControl.Transaction t, float left, float top,
            boolean recoveringMemory) {
        final boolean surfaceMoved = mSurfaceX != left || mSurfaceY != top;
        if (surfaceMoved) {
            mSurfaceX = left;
            mSurfaceY = top;

            try {
                ProtoLog.i(WM_SHOW_TRANSACTIONS,
                        "SURFACE POS (setPositionInTransaction) @ (%f,%f): %s", left, top, title);

                if (t == null) {
                    mSurfaceControl.setPosition(left, top);
                } else {
                    t.setPosition(mSurfaceControl, left, top);
                }
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error positioning surface of " + this
                        + " pos=(" + left + "," + top + ")", e);
                if (!recoveringMemory) {
                    mAnimator.reclaimSomeSurfaceMemory("position", true);
                }
            }
        }
    }

    void setMatrixInTransaction(float dsdx, float dtdx, float dtdy, float dsdy,
            boolean recoveringMemory) {
        setMatrix(null, dsdx, dtdx, dtdy, dsdy, false);
    }

    void setMatrix(SurfaceControl.Transaction t, float dsdx, float dtdx,
            float dtdy, float dsdy, boolean recoveringMemory) {
        final boolean matrixChanged = mLastDsdx != dsdx || mLastDtdx != dtdx ||
                                      mLastDtdy != dtdy || mLastDsdy != dsdy;
        if (!matrixChanged) {
            return;
        }

        mLastDsdx = dsdx;
        mLastDtdx = dtdx;
        mLastDtdy = dtdy;
        mLastDsdy = dsdy;

        try {
            ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE MATRIX [%f,%f,%f,%f]: %s",
                    dsdx, dtdx, dtdy, dsdy, title);
            if (t == null) {
                mSurfaceControl.setMatrix(dsdx, dtdx, dtdy, dsdy);
            } else {
                t.setMatrix(mSurfaceControl, dsdx, dtdx, dtdy, dsdy);
            }
        } catch (RuntimeException e) {
            // If something goes wrong with the surface (such
            // as running out of memory), don't take down the
            // entire system.
            Slog.e(TAG, "Error setting matrix on surface surface" + title
                    + " MATRIX [" + dsdx + "," + dtdx + "," + dtdy + "," + dsdy + "]", null);
            if (!recoveringMemory) {
                mAnimator.reclaimSomeSurfaceMemory("matrix", true);
            }
        }
    }

    boolean setBufferSizeInTransaction(int width, int height, boolean recoveringMemory) {
        final boolean surfaceResized = mSurfaceW != width || mSurfaceH != height;
        if (surfaceResized) {
            mSurfaceW = width;
            mSurfaceH = height;

            try {
                ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE SIZE %dx%d: %s", width, height, title);
                mSurfaceControl.setBufferSize(width, height);
            } catch (RuntimeException e) {
                // If something goes wrong with the surface (such
                // as running out of memory), don't take down the
                // entire system.
                Slog.e(TAG, "Error resizing surface of " + title
                        + " size=(" + width + "x" + height + ")", e);
                if (!recoveringMemory) {
                    mAnimator.reclaimSomeSurfaceMemory("size", true);
                }
                return false;
            }
            return true;
        }
        return false;
    }

    boolean prepareToShowInTransaction(float alpha,
            float dsdx, float dtdx, float dsdy,
            float dtdy, boolean recoveringMemory) {
        if (mSurfaceControl != null) {
            try {
                mSurfaceAlpha = alpha;
                mSurfaceControl.setAlpha(alpha);
                mLastDsdx = dsdx;
                mLastDtdx = dtdx;
                mLastDsdy = dsdy;
                mLastDtdy = dtdy;
                mSurfaceControl.setMatrix(
                        dsdx, dtdx, dsdy, dtdy);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error updating surface in " + title, e);
                if (!recoveringMemory) {
                    mAnimator.reclaimSomeSurfaceMemory("update", true);
                }
                return false;
            }
        }
        return true;
    }

    void setTransparentRegionHint(final Region region) {
        if (mSurfaceControl == null) {
            Slog.w(TAG, "setTransparentRegionHint: null mSurface after mHasSurface true");
            return;
        }
        if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION setTransparentRegion");
        mService.openSurfaceTransaction();
        try {
            mSurfaceControl.setTransparentRegionHint(region);
        } finally {
            mService.closeSurfaceTransaction("setTransparentRegion");
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    "<<< CLOSE TRANSACTION setTransparentRegion");
        }
    }

    void setOpaque(boolean isOpaque) {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE isOpaque=%b: %s", isOpaque, title);

        if (mSurfaceControl == null) {
            return;
        }
        if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION setOpaqueLocked");
        mService.openSurfaceTransaction();
        try {
            mSurfaceControl.setOpaque(isOpaque);
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
            mSurfaceControl.setSecure(isSecure);
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
            mSurfaceControl.setColorSpaceAgnostic(agnostic);
        } finally {
            mService.closeSurfaceTransaction("setColorSpaceAgnostic");
            if (SHOW_LIGHT_TRANSACTIONS) {
                Slog.i(TAG, "<<< CLOSE TRANSACTION setColorSpaceAgnosticLocked");
            }
        }
    }

    void getContainerRect(Rect rect) {
        mAnimator.getContainerRect(rect);
    }

    boolean showRobustlyInTransaction() {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE SHOW (performLayout): %s", title);
        if (DEBUG_VISIBILITY) Slog.v(TAG, "Showing " + this
                + " during relayout");
        mHiddenForOtherReasons = false;
        return updateVisibility();
    }

    private boolean updateVisibility() {
        if (mHiddenForCrop || mHiddenForOtherReasons) {
            if (mSurfaceShown) {
                hideSurface(mTmpTransaction);
                SurfaceControl.mergeToGlobalTransaction(mTmpTransaction);
            }
            return false;
        } else {
            if (!mSurfaceShown) {
                return showSurface();
            } else {
                return true;
            }
        }
    }

    private boolean showSurface() {
        try {
            setShown(true);
            mSurfaceControl.show();
            return true;
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failure showing surface " + mSurfaceControl + " in " + this, e);
        }

        mAnimator.reclaimSomeSurfaceMemory("show", true);

        return false;
    }

    void deferTransactionUntil(SurfaceControl barrier, long frame) {
        // TODO: Logging
        mSurfaceControl.deferTransactionUntil(barrier, frame);
    }

    void forceScaleableInTransaction(boolean force) {
        // -1 means we don't override the default or client specified
        // scaling mode.
        int scalingMode = force ? SCALING_MODE_SCALE_TO_WINDOW : -1;
        mSurfaceControl.setOverrideScalingMode(scalingMode);
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

    void getBLASTSurfaceControl(SurfaceControl outSurfaceControl) {
        if (mBLASTSurfaceControl != null) {
            outSurfaceControl.copyFrom(mBLASTSurfaceControl, "WindowSurfaceController.getBLASTSurfaceControl");
        }
    }

    int getLayer() {
        return mSurfaceLayer;
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

    float getX() {
        return mSurfaceX;
    }

    float getY() {
        return mSurfaceY;
    }

    int getWidth() {
        return mSurfaceW;
    }

    int getHeight() {
        return mSurfaceH;
    }

    /**
     * Returns the Surface which the client-framework ViewRootImpl will be using.
     * This is either the WSA SurfaceControl or it's BLAST child surface.
     * This has too main uses:
     * 1. This is the Surface the client will add children to, we use this to make
     *    sure we don't reparent the BLAST surface itself when calling reparentChildren
     * 2. We use this as the barrier Surface for some deferTransaction operations.
     */
    SurfaceControl getClientViewRootSurface() {
        if (mBLASTSurfaceControl != null) {
            return mBLASTSurfaceControl;
        }
        return mSurfaceControl;
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
        pw.print(","); pw.print(mSurfaceY);
        pw.print(") "); pw.print(mSurfaceW);
        pw.print(" x "); pw.print(mSurfaceH);
        pw.print(" transform=("); pw.print(mLastDsdx); pw.print(", ");
        pw.print(mLastDtdx); pw.print(", "); pw.print(mLastDsdy);
        pw.print(", "); pw.print(mLastDtdy); pw.println(")");
    }

    @Override
    public String toString() {
        return mSurfaceControl.toString();
    }
}
