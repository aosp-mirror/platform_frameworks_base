/*
 * Copyright (C) 2010 The Android Open Source Project
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

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;

class StrictModeFlash {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "StrictModeFlash" : TAG_WM;

    private final SurfaceControl mSurfaceControl;
    private final Surface mSurface = new Surface();
    private int mLastDW;
    private int mLastDH;
    private boolean mDrawNeeded;
    private final int mThickness = 20;

    public StrictModeFlash(DisplayContent dc) {
        SurfaceControl ctrl = null;
        try {
            ctrl = dc.makeOverlay()
                    .setName("StrictModeFlash")
                    .setBufferSize(1, 1)
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .build();
            ctrl.setLayer(WindowManagerService.TYPE_LAYER_MULTIPLIER * 101);  // one more than Watermark? arbitrary.
            ctrl.setPosition(0, 0);
            ctrl.show();
            mSurface.copyFrom(ctrl);
        } catch (OutOfResourcesException e) {
        }
        mSurfaceControl = ctrl;
        mDrawNeeded = true;
    }

    private void drawIfNeeded() {
        if (!mDrawNeeded) {
            return;
        }
        mDrawNeeded = false;
        final int dw = mLastDW;
        final int dh = mLastDH;

        Rect dirty = new Rect(0, 0, dw, dh);
        Canvas c = null;
        try {
            c = mSurface.lockCanvas(dirty);
        } catch (IllegalArgumentException e) {
        } catch (Surface.OutOfResourcesException e) {
        }
        if (c == null) {
            return;
        }

        // Top
        c.save();
        c.clipRect(new Rect(0, 0, dw, mThickness));
        c.drawColor(Color.RED);
        c.restore();
        // Left
        c.save();
        c.clipRect(new Rect(0, 0, mThickness, dh));
        c.drawColor(Color.RED);
        c.restore();
        // Right
        c.save();
        c.clipRect(new Rect(dw - mThickness, 0, dw, dh));
        c.drawColor(Color.RED);
        c.restore();
        // Bottom
        c.save();
        c.clipRect(new Rect(0, dh - mThickness, dw, dh));
        c.drawColor(Color.RED);
        c.restore();

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
        mSurfaceControl.setBufferSize(dw, dh);
        mDrawNeeded = true;
    }

}
