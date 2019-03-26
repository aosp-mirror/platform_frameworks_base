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

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_SURFACE_ALLOC;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowSurfaceControllerProto.LAYER;
import static com.android.server.wm.WindowSurfaceControllerProto.SHOWN;

import android.graphics.Rect;
import android.graphics.Region;
import android.os.Debug;
import android.os.IBinder;
import android.os.Trace;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowContentFrameStats;

import java.io.PrintWriter;

class WindowSurfaceController {
    static final String TAG = TAG_WITH_CLASS_NAME ? "WindowSurfaceController" : TAG_WM;

    final WindowStateAnimator mAnimator;

    SurfaceControl mSurfaceControl;

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

    private final SurfaceControl.Transaction mTmpTransaction = new SurfaceControl.Transaction();

    public WindowSurfaceController(SurfaceSession s, String name, int w, int h, int format,
            int flags, WindowStateAnimator animator, int windowType, int ownerUid) {
        mAnimator = animator;

        mSurfaceW = w;
        mSurfaceH = h;

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
                .setMetadata(METADATA_OWNER_UID, ownerUid);
        mSurfaceControl = b.build();
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    private void logSurface(String msg, RuntimeException where) {
        String str = "  SURFACE " + msg + ": " + title;
        if (where != null) {
            Slog.i(TAG, str, where);
        } else {
            Slog.i(TAG, str);
        }
    }

    void reparentChildrenInTransaction(WindowSurfaceController other) {
        if (SHOW_TRANSACTIONS) Slog.i(TAG, "REPARENT from: " + this + " to: " + other);
        if ((mSurfaceControl != null) && (other.mSurfaceControl != null)) {
            mSurfaceControl.reparentChildren(other.getHandle());
        }
    }

    void detachChildren() {
        if (SHOW_TRANSACTIONS) Slog.i(TAG, "SEVER CHILDREN");
        if (mSurfaceControl != null) {
            mSurfaceControl.detachChildren();
        }
    }

    void hide(SurfaceControl.Transaction transaction, String reason) {
        if (SHOW_TRANSACTIONS) logSurface("HIDE ( " + reason + " )", null);
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
        if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
            Slog.i(TAG, "Destroying surface " + this + " called by " + Debug.getCallers(8));
        }
        try {
            if (mSurfaceControl != null) {
                mSurfaceControl.remove();
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error destroying surface in: " + this, e);
        } finally {
            setShown(false);
            mSurfaceControl = null;
        }
    }

    void setCropInTransaction(Rect clipRect, boolean recoveringMemory) {
        if (SHOW_TRANSACTIONS) logSurface(
                "CROP " + clipRect.toShortString(), null);
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
        if (SHOW_TRANSACTIONS) logSurface(
                "CLEAR CROP", null);
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
                if (SHOW_TRANSACTIONS) logSurface(
                        "POS (setPositionInTransaction) @ (" + left + "," + top + ")", null);

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

    void setGeometryAppliesWithResizeInTransaction(boolean recoveringMemory) {
        mSurfaceControl.setGeometryAppliesWithResize();
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
            if (SHOW_TRANSACTIONS) logSurface(
                    "MATRIX [" + dsdx + "," + dtdx + "," + dtdy + "," + dsdy + "]", null);
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
                if (SHOW_TRANSACTIONS) logSurface(
                        "SIZE " + width + "x" + height, null);
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
        if (SHOW_TRANSACTIONS) logSurface("isOpaque=" + isOpaque,
                null);

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
        if (SHOW_TRANSACTIONS) logSurface("isSecure=" + isSecure,
                null);

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
        if (SHOW_TRANSACTIONS) {
            logSurface("isColorSpaceAgnostic=" + agnostic, null);
        }

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
        if (SHOW_TRANSACTIONS) logSurface(
                "SHOW (performLayout)", null);
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

    void deferTransactionUntil(IBinder handle, long frame) {
        // TODO: Logging
        mSurfaceControl.deferTransactionUntil(handle, frame);
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

    IBinder getHandle() {
        if (mSurfaceControl == null) {
            return null;
        }
        return mSurfaceControl.getHandle();
    }

    void getSurfaceControl(SurfaceControl outSurfaceControl) {
        outSurfaceControl.copyFrom(mSurfaceControl);
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

    void writeToProto(ProtoOutputStream proto, long fieldId) {
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
