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
import android.graphics.Rect;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

class CircularDisplayMask {
    private static final String TAG = "CircularDisplayMask";

    private static final int STROKE_WIDTH = 2;

    private final SurfaceControl mSurfaceControl;
    private final Surface mSurface = new Surface();
    private int mLastDW;
    private int mLastDH;
    private boolean mDrawNeeded;
    private Paint mPaint;

    public CircularDisplayMask(Display display, SurfaceSession session, int zOrder) {
        SurfaceControl ctrl = null;
        try {
            ctrl = new SurfaceControl(session, "CircularDisplayMask",
                320, 290, PixelFormat.TRANSLUCENT, SurfaceControl.HIDDEN);
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
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setColor(Color.BLACK);
        mPaint.setStrokeWidth(STROKE_WIDTH);
    }

    private void drawIfNeeded() {
        if (!mDrawNeeded) {
            return;
        }
        mDrawNeeded = false;

        Rect dirty = new Rect(0, 0, mLastDW, mLastDH);
        Canvas c = null;
        try {
            c = mSurface.lockCanvas(dirty);
        } catch (IllegalArgumentException e) {
        } catch (Surface.OutOfResourcesException e) {
        }
        if (c == null) {
            return;
        }
        c.drawCircle(160, 160, 160, mPaint);

        mSurface.unlockCanvasAndPost(c);
    }

    // Note: caller responsible for being inside
    // Surface.openTransaction() / closeTransaction()
    public void setVisibility(boolean on) {
        if (mSurfaceControl == null) {
            return;
        }
        drawIfNeeded();
        if (on) {
            mSurfaceControl.show();
        } else {
            mSurfaceControl.hide();
        }
    }

    void positionSurface(int dw, int dh) {
        if (mLastDW == dw && mLastDH == dh) {
            return;
        }
        mLastDW = dw;
        mLastDH = dh;
        mSurfaceControl.setSize(dw, dh);
        mDrawNeeded = true;
    }

}
