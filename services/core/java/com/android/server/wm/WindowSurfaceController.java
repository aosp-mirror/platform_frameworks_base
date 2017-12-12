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
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_SURFACE_ALLOC;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SURFACE_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static android.view.Surface.SCALING_MODE_SCALE_TO_WINDOW;

import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;
import android.os.Debug;
import android.os.Trace;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowContentFrameStats;
import android.view.Surface.OutOfResourcesException;

import android.util.Slog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

class WindowSurfaceController {
    static final String TAG = TAG_WITH_CLASS_NAME ? "WindowSurfaceController" : TAG_WM;

    final WindowStateAnimator mAnimator;

    private SurfaceControlWithBackground mSurfaceControl;

    // Should only be set from within setShown().
    private boolean mSurfaceShown = false;
    private float mSurfaceX = 0;
    private float mSurfaceY = 0;
    private float mSurfaceW = 0;
    private float mSurfaceH = 0;

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
        mSurfaceControl = new SurfaceControlWithBackground(
                s, name, w, h, format, flags, windowType, ownerUid, this);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);

        if (mService.mRoot.mSurfaceTraceEnabled) {
            mSurfaceControl = new RemoteSurfaceTrace(
                    mService.mRoot.mSurfaceTraceFd.getFileDescriptor(), mSurfaceControl, win);
        }
    }

    void installRemoteTrace(FileDescriptor fd) {
        mSurfaceControl = new RemoteSurfaceTrace(fd, mSurfaceControl, mAnimator.mWin);
    }

    void removeRemoteTrace() {
        mSurfaceControl = new SurfaceControlWithBackground(mSurfaceControl);
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

    void hideInTransaction(String reason) {
        if (SHOW_TRANSACTIONS) logSurface("HIDE ( " + reason + " )", null);
        mHiddenForOtherReasons = true;

        mAnimator.destroyPreservedSurfaceLocked();
        updateVisibility();
    }

    private void hideSurface() {
        if (mSurfaceControl == null) {
            return;
        }
        setShown(false);
        try {
            mSurfaceControl.hide();
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception hiding surface in " + this);
        }
    }

    void destroyInTransaction() {
        if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
            Slog.i(TAG, "Destroying surface " + this + " called by " + Debug.getCallers(8));
        }
        try {
            if (mSurfaceControl != null) {
                mSurfaceControl.destroy();
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error destroying surface in: " + this, e);
        } finally {
            setShown(false);
            mSurfaceControl = null;
        }
    }

    void disconnectInTransaction() {
        if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
            Slog.i(TAG, "Disconnecting client: " + this);
        }

        try {
            if (mSurfaceControl != null) {
                mSurfaceControl.disconnect();
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error disconnecting surface in: " + this, e);
        }
    }

    void setCropInTransaction(Rect clipRect, boolean recoveringMemory) {
        if (SHOW_TRANSACTIONS) logSurface(
                "CROP " + clipRect.toShortString(), null);
        try {
            if (clipRect.width() > 0 && clipRect.height() > 0) {
                mSurfaceControl.setWindowCrop(clipRect);
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
            mSurfaceControl.setWindowCrop(clipRect);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error setting clearing crop of " + this, e);
            if (!recoveringMemory) {
                mAnimator.reclaimSomeSurfaceMemory("crop", true);
            }
        }
    }

    void setFinalCropInTransaction(Rect clipRect) {
        if (SHOW_TRANSACTIONS) logSurface(
                "FINAL CROP " + clipRect.toShortString(), null);
        try {
            mSurfaceControl.setFinalCrop(clipRect);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error disconnecting surface in: " + this, e);
        }
    }

    void setLayer(int layer) {
        if (mSurfaceControl != null) {
            mService.openSurfaceTransaction();
            try {
                if (mAnimator.mWin.usesRelativeZOrdering()) {
                    mSurfaceControl.setRelativeLayer(
                            mAnimator.mWin.getParentWindow()
                            .mWinAnimator.mSurfaceController.mSurfaceControl.getHandle(),
                            -1);
                } else {
                    mSurfaceLayer = layer;
                    mSurfaceControl.setLayer(layer);
                }
            } finally {
                mService.closeSurfaceTransaction();
            }
        }
    }

    void setLayerStackInTransaction(int layerStack) {
        if (mSurfaceControl != null) {
            mSurfaceControl.setLayerStack(layerStack);
        }
    }

    void setPositionInTransaction(float left, float top, boolean recoveringMemory) {
        final boolean surfaceMoved = mSurfaceX != left || mSurfaceY != top;
        if (surfaceMoved) {
            mSurfaceX = left;
            mSurfaceY = top;

            try {
                if (SHOW_TRANSACTIONS) logSurface(
                        "POS (setPositionInTransaction) @ (" + left + "," + top + ")", null);

                mSurfaceControl.setPosition(left, top);
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
            mSurfaceControl.setMatrix(
                    dsdx, dtdx, dtdy, dsdy);
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

    boolean setSizeInTransaction(int width, int height, boolean recoveringMemory) {
        final boolean surfaceResized = mSurfaceW != width || mSurfaceH != height;
        if (surfaceResized) {
            mSurfaceW = width;
            mSurfaceH = height;

            try {
                if (SHOW_TRANSACTIONS) logSurface(
                        "SIZE " + width + "x" + height, null);
                mSurfaceControl.setSize(width, height);
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
            mService.closeSurfaceTransaction();
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
            mService.closeSurfaceTransaction();
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
            mService.closeSurfaceTransaction();
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG, "<<< CLOSE TRANSACTION setSecureLocked");
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
                hideSurface();
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

    void getSurface(Surface outSurface) {
        outSurface.copyFrom(mSurfaceControl);
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

    float getWidth() {
        return mSurfaceW;
    }

    float getHeight() {
        return mSurfaceH;
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

    static class SurfaceTrace extends SurfaceControl {
        private final static String SURFACE_TAG = TAG_WITH_CLASS_NAME ? "SurfaceTrace" : TAG_WM;
        private final static boolean LOG_SURFACE_TRACE = DEBUG_SURFACE_TRACE;
        final static ArrayList<SurfaceTrace> sSurfaces = new ArrayList<SurfaceTrace>();

        private float mSurfaceTraceAlpha = 0;
        private int mLayer;
        private final PointF mPosition = new PointF();
        private final Point mSize = new Point();
        private final Rect mWindowCrop = new Rect();
        private final Rect mFinalCrop = new Rect();
        private boolean mShown = false;
        private int mLayerStack;
        private boolean mIsOpaque;
        private float mDsdx, mDtdx, mDsdy, mDtdy;
        private final String mName;

        public SurfaceTrace(SurfaceSession s, String name, int w, int h, int format, int flags,
                        int windowType, int ownerUid)
                    throws OutOfResourcesException {
            super(s, name, w, h, format, flags, windowType, ownerUid);
            mName = name != null ? name : "Not named";
            mSize.set(w, h);
            if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "ctor: " + this + ". Called by "
                    + Debug.getCallers(3));
            synchronized (sSurfaces) {
                sSurfaces.add(0, this);
            }
        }

        public SurfaceTrace(SurfaceSession s,
                        String name, int w, int h, int format, int flags) {
            super(s, name, w, h, format, flags);
            mName = name != null ? name : "Not named";
            mSize.set(w, h);
            if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "ctor: " + this + ". Called by "
                    + Debug.getCallers(3));
            synchronized (sSurfaces) {
                sSurfaces.add(0, this);
            }
        }

        @Override
        public void setAlpha(float alpha) {
            if (mSurfaceTraceAlpha != alpha) {
                if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "setAlpha(" + alpha + "): OLD:" + this +
                        ". Called by " + Debug.getCallers(3));
                mSurfaceTraceAlpha = alpha;
            }
            super.setAlpha(alpha);
        }

        @Override
        public void setLayer(int zorder) {
            if (zorder != mLayer) {
                if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "setLayer(" + zorder + "): OLD:" + this
                        + ". Called by " + Debug.getCallers(3));
                mLayer = zorder;
            }
            super.setLayer(zorder);

            synchronized (sSurfaces) {
                sSurfaces.remove(this);
                int i;
                for (i = sSurfaces.size() - 1; i >= 0; i--) {
                    SurfaceTrace s = sSurfaces.get(i);
                    if (s.mLayer < zorder) {
                        break;
                    }
                }
                sSurfaces.add(i + 1, this);
            }
        }

        @Override
        public void setPosition(float x, float y) {
            if (x != mPosition.x || y != mPosition.y) {
                if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "setPosition(" + x + "," + y + "): OLD:"
                        + this + ". Called by " + Debug.getCallers(3));
                mPosition.set(x, y);
            }
            super.setPosition(x, y);
        }

        @Override
        public void setGeometryAppliesWithResize() {
            if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "setGeometryAppliesWithResize(): OLD: "
                    + this + ". Called by" + Debug.getCallers(3));
            super.setGeometryAppliesWithResize();
        }

        @Override
        public void setSize(int w, int h) {
            if (w != mSize.x || h != mSize.y) {
                if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "setSize(" + w + "," + h + "): OLD:"
                        + this + ". Called by " + Debug.getCallers(3));
                mSize.set(w, h);
            }
            super.setSize(w, h);
        }

        @Override
        public void setWindowCrop(Rect crop) {
            if (crop != null) {
                if (!crop.equals(mWindowCrop)) {
                    if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "setWindowCrop("
                            + crop.toShortString() + "): OLD:" + this + ". Called by "
                            + Debug.getCallers(3));
                    mWindowCrop.set(crop);
                }
            }
            super.setWindowCrop(crop);
        }

        @Override
        public void setFinalCrop(Rect crop) {
            if (crop != null) {
                if (!crop.equals(mFinalCrop)) {
                    if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "setFinalCrop("
                            + crop.toShortString() + "): OLD:" + this + ". Called by "
                            + Debug.getCallers(3));
                    mFinalCrop.set(crop);
                }
            }
            super.setFinalCrop(crop);
        }

        @Override
        public void setLayerStack(int layerStack) {
            if (layerStack != mLayerStack) {
                if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "setLayerStack(" + layerStack + "): OLD:"
                        + this + ". Called by " + Debug.getCallers(3));
                mLayerStack = layerStack;
            }
            super.setLayerStack(layerStack);
        }

        @Override
        public void setOpaque(boolean isOpaque) {
            if (isOpaque != mIsOpaque) {
                if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "setOpaque(" + isOpaque + "): OLD:"
                        + this + ". Called by " + Debug.getCallers(3));
                mIsOpaque = isOpaque;
            }
            super.setOpaque(isOpaque);
        }

        @Override
        public void setSecure(boolean isSecure) {
            super.setSecure(isSecure);
        }

        @Override
        public void setMatrix(float dsdx, float dtdx, float dsdy, float dtdy) {
            if (dsdx != mDsdx || dtdx != mDtdx || dsdy != mDsdy || dtdy != mDtdy) {
                if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "setMatrix(" + dsdx + "," + dtdx + ","
                        + dsdy + "," + dtdy + "): OLD:" + this + ". Called by "
                        + Debug.getCallers(3));
                mDsdx = dsdx;
                mDtdx = dtdx;
                mDsdy = dsdy;
                mDtdy = dtdy;
            }
            super.setMatrix(dsdx, dtdx, dsdy, dtdy);
        }

        @Override
        public void hide() {
            if (mShown) {
                if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "hide: OLD:" + this + ". Called by "
                        + Debug.getCallers(3));
                mShown = false;
            }
            super.hide();
        }

        @Override
        public void show() {
            if (!mShown) {
                if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "show: OLD:" + this + ". Called by "
                        + Debug.getCallers(3));
                mShown = true;
            }
            super.show();
        }

        @Override
        public void destroy() {
            super.destroy();
            if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "destroy: " + this + ". Called by "
                    + Debug.getCallers(3));
            synchronized (sSurfaces) {
                sSurfaces.remove(this);
            }
        }

        @Override
        public void release() {
            super.release();
            if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "release: " + this + ". Called by "
                    + Debug.getCallers(3));
            synchronized (sSurfaces) {
                sSurfaces.remove(this);
            }
        }

        @Override
        public void setTransparentRegionHint(Region region) {
            if (LOG_SURFACE_TRACE) Slog.v(SURFACE_TAG, "setTransparentRegionHint(" + region
                    + "): OLD: " + this + " . Called by " + Debug.getCallers(3));
            super.setTransparentRegionHint(region);
        }

        static void dumpAllSurfaces(PrintWriter pw, String header) {
            synchronized (sSurfaces) {
                final int N = sSurfaces.size();
                if (N <= 0) {
                    return;
                }
                if (header != null) {
                    pw.println(header);
                }
                pw.println("WINDOW MANAGER SURFACES (dumpsys window surfaces)");
                for (int i = 0; i < N; i++) {
                    SurfaceTrace s = sSurfaces.get(i);
                    pw.print("  Surface #"); pw.print(i); pw.print(": #");
                            pw.print(Integer.toHexString(System.identityHashCode(s)));
                            pw.print(" "); pw.println(s.mName);
                    pw.print("    mLayerStack="); pw.print(s.mLayerStack);
                            pw.print(" mLayer="); pw.println(s.mLayer);
                    pw.print("    mShown="); pw.print(s.mShown); pw.print(" mAlpha=");
                            pw.print(s.mSurfaceTraceAlpha); pw.print(" mIsOpaque=");
                            pw.println(s.mIsOpaque);
                    pw.print("    mPosition="); pw.print(s.mPosition.x); pw.print(",");
                            pw.print(s.mPosition.y);
                            pw.print(" mSize="); pw.print(s.mSize.x); pw.print("x");
                            pw.println(s.mSize.y);
                    pw.print("    mCrop="); s.mWindowCrop.printShortString(pw); pw.println();
                    pw.print("    mFinalCrop="); s.mFinalCrop.printShortString(pw); pw.println();
                    pw.print("    Transform: ("); pw.print(s.mDsdx); pw.print(", ");
                            pw.print(s.mDtdx); pw.print(", "); pw.print(s.mDsdy);
                            pw.print(", "); pw.print(s.mDtdy); pw.println(")");
                }
            }
        }

        @Override
        public String toString() {
            return "Surface " + Integer.toHexString(System.identityHashCode(this)) + " "
                    + mName + " (" + mLayerStack + "): shown=" + mShown + " layer=" + mLayer
                    + " alpha=" + mSurfaceTraceAlpha + " " + mPosition.x + "," + mPosition.y
                    + " " + mSize.x + "x" + mSize.y
                    + " crop=" + mWindowCrop.toShortString()
                    + " opaque=" + mIsOpaque
                    + " (" + mDsdx + "," + mDtdx + "," + mDsdy + "," + mDtdy + ")";
        }
    }
}
