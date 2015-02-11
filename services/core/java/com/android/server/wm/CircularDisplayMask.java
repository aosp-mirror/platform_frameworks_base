/*
 * Copyright (C) 2014 The Android Open Source Project
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



import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.view.Display;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.util.Slog;

class CircularDisplayMask {
    private static final String TAG = "CircularDisplayMask";

    // size of the chin
    private int mScreenOffset = 0;
    // Display dimensions
    private Point mScreenSize;

    private final SurfaceControl mSurfaceControl;
    private final Surface mSurface = new Surface();
    private int mLastDW;
    private int mLastDH;
    private boolean mDrawNeeded;
    private Paint mPaint;
    private int mRotation;
    private boolean mVisible;
    private boolean mDimensionsUnequal = false;
    private int mMaskThickness;

    public CircularDisplayMask(Display display, SurfaceSession session, int zOrder,
            int screenOffset, int maskThickness) {
        mScreenSize = new Point();
        display.getSize(mScreenSize);
        if (mScreenSize.x != mScreenSize.y) {
            Slog.w(TAG, "Screen dimensions of displayId = " + display.getDisplayId() +
                    "are not equal, circularMask will not be drawn.");
            mDimensionsUnequal = true;
        }

        SurfaceControl ctrl = null;
        try {
            if (WindowManagerService.DEBUG_SURFACE_TRACE) {
                ctrl = new WindowStateAnimator.SurfaceTrace(session, "CircularDisplayMask",
                        mScreenSize.x, mScreenSize.y, PixelFormat.TRANSLUCENT,
                        SurfaceControl.HIDDEN);
            } else {
                ctrl = new SurfaceControl(session, "CircularDisplayMask", mScreenSize.x,
                        mScreenSize.y, PixelFormat.TRANSLUCENT, SurfaceControl.HIDDEN);
            }
            ctrl.setLayerStack(display.getLayerStack());
            ctrl.setLayer(zOrder);
            ctrl.setPosition(0, 0);
            ctrl.show();
            mSurface.copyFrom(ctrl);
        } catch (OutOfResourcesException e) {
        }
        mSurfaceControl = ctrl;
        mDrawNeeded = true;
        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        mScreenOffset = screenOffset;
        mMaskThickness = maskThickness;
    }

    private void drawIfNeeded() {
        if (!mDrawNeeded || !mVisible || mDimensionsUnequal) {
            return;
        }
        mDrawNeeded = false;

        Rect dirty = new Rect(0, 0, mScreenSize.x, mScreenSize.y);
        Canvas c = null;
        try {
            c = mSurface.lockCanvas(dirty);
        } catch (IllegalArgumentException e) {
        } catch (Surface.OutOfResourcesException e) {
        }
        if (c == null) {
            return;
        }
        switch (mRotation) {
        case Surface.ROTATION_0:
        case Surface.ROTATION_90:
            // chin bottom or right
            mSurfaceControl.setPosition(0, 0);
            break;
        case Surface.ROTATION_180:
            // chin top
            mSurfaceControl.setPosition(0, -mScreenOffset);
            break;
        case Surface.ROTATION_270:
            // chin left
            mSurfaceControl.setPosition(-mScreenOffset, 0);
            break;
        }

        int circleRadius = mScreenSize.x / 2;
        c.drawColor(Color.BLACK);

        // The radius is reduced by mMaskThickness to provide an anti aliasing effect on the display edges.
        c.drawCircle(circleRadius, circleRadius, circleRadius - mMaskThickness, mPaint);
        mSurface.unlockCanvasAndPost(c);
    }

    // Note: caller responsible for being inside
    // Surface.openTransaction() / closeTransaction()
    public void setVisibility(boolean on) {
        if (mSurfaceControl == null) {
            return;
        }
        mVisible = on;
        drawIfNeeded();
        if (on) {
            mSurfaceControl.show();
        } else {
            mSurfaceControl.hide();
        }
    }

    void positionSurface(int dw, int dh, int rotation) {
        if (mLastDW == dw && mLastDH == dh && mRotation == rotation) {
            return;
        }
        mLastDW = dw;
        mLastDH = dh;
        mDrawNeeded = true;
        mRotation = rotation;
        drawIfNeeded();
    }

}
