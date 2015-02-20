/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static com.android.server.wm.WindowManagerService.DEBUG_SURFACE_TRACE;
import static com.android.server.wm.WindowManagerService.SHOW_LIGHT_TRANSACTIONS;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.Slog;
import android.view.Display;
import android.view.Surface.OutOfResourcesException;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

import com.android.server.wm.WindowStateAnimator.SurfaceTrace;

class FocusedStackFrame {
    private static final String TAG = "FocusedStackFrame";
    private static final boolean DEBUG = false;
    private static final int THICKNESS = 2;
    private static final float ALPHA = 0.3f;

    private final SurfaceControl mSurfaceControl;
    private final Surface mSurface = new Surface();
    private final Paint mInnerPaint = new Paint();
    private final Paint mOuterPaint = new Paint();
    private final Rect mBounds = new Rect();
    private final Rect mLastBounds = new Rect();
    private int mLayer = -1;

    public FocusedStackFrame(Display display, SurfaceSession session) {
        SurfaceControl ctrl = null;
        try {
            if (DEBUG_SURFACE_TRACE) {
                ctrl = new SurfaceTrace(session, "FocusedStackFrame",
                    1, 1, PixelFormat.TRANSLUCENT, SurfaceControl.HIDDEN);
            } else {
                ctrl = new SurfaceControl(session, "FocusedStackFrame",
                    1, 1, PixelFormat.TRANSLUCENT, SurfaceControl.HIDDEN);
            }
            ctrl.setLayerStack(display.getLayerStack());
            ctrl.setAlpha(ALPHA);
            mSurface.copyFrom(ctrl);
        } catch (OutOfResourcesException e) {
        }
        mSurfaceControl = ctrl;

        mInnerPaint.setStyle(Paint.Style.STROKE);
        mInnerPaint.setStrokeWidth(THICKNESS);
        mInnerPaint.setColor(Color.WHITE);
        mOuterPaint.setStyle(Paint.Style.STROKE);
        mOuterPaint.setStrokeWidth(THICKNESS);
        mOuterPaint.setColor(Color.BLACK);
    }

    private void draw() {
        if (mLastBounds.isEmpty()) {
            // Currently unset. Set it.
            mLastBounds.set(mBounds);
        }

        if (DEBUG) Slog.i(TAG, "draw: mBounds=" + mBounds + " mLastBounds=" + mLastBounds);

        Canvas c = null;
        try {
            c = mSurface.lockCanvas(mLastBounds);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Unable to lock canvas", e);
        } catch (Surface.OutOfResourcesException e) {
            Slog.e(TAG, "Unable to lock canvas", e);
        }
        if (c == null) {
            if (DEBUG) Slog.w(TAG, "Canvas is null...");
            return;
        }

        c.drawRect(0, 0, mBounds.width(), mBounds.height(), mOuterPaint);
        c.drawRect(THICKNESS, THICKNESS, mBounds.width() - THICKNESS, mBounds.height() - THICKNESS,
                mInnerPaint);
        if (DEBUG) Slog.w(TAG, "c.width=" + c.getWidth() + " c.height=" + c.getHeight()
                + " c.clip=" + c .getClipBounds());
        mSurface.unlockCanvasAndPost(c);
        mLastBounds.set(mBounds);
    }

    private void setupSurface(boolean visible) {
        if (mSurfaceControl == null) {
            return;
        }
        if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION setupSurface");
        SurfaceControl.openTransaction();
        try {
            if (visible) {
                mSurfaceControl.setPosition(mBounds.left, mBounds.top);
                mSurfaceControl.setSize(mBounds.width(), mBounds.height());
                mSurfaceControl.show();
            } else {
                mSurfaceControl.hide();
            }
        } finally {
            SurfaceControl.closeTransaction();
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG, ">>> CLOSE TRANSACTION setupSurface");
        }
    }

    void setVisibility(TaskStack stack) {
        if (stack == null || stack.isFullscreen()) {
            setupSurface(false);
        } else {
            stack.getBounds(mBounds);
            setupSurface(true);
            if (!mBounds.equals(mLastBounds)) {
                draw();
            }
        }
    }

    // Note: caller responsible for being inside
    // Surface.openTransaction() / closeTransaction()
    void setLayer(int layer) {
        if (mLayer == layer) {
            return;
        }
        mLayer = layer;
        mSurfaceControl.setLayer(mLayer);
    }
}
