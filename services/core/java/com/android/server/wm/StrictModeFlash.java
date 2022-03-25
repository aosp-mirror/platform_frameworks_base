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

import android.graphics.BLASTBufferQueue;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.WindowManagerPolicyConstants;

class StrictModeFlash {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "StrictModeFlash" : TAG_WM;
    private static final String TITLE = "StrictModeFlash";

    private final SurfaceControl mSurfaceControl;
    private final Surface mSurface;
    private final BLASTBufferQueue mBlastBufferQueue;

    private int mLastDW;
    private int mLastDH;
    private boolean mDrawNeeded;
    private final int mThickness = 20;

    StrictModeFlash(DisplayContent dc, SurfaceControl.Transaction t) {
        SurfaceControl ctrl = null;
        try {
            ctrl = dc.makeOverlay()
                    .setName(TITLE)
                    .setBLASTLayer()
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .setCallsite(TITLE)
                    .build();

            // one more than Watermark? arbitrary.
            t.setLayer(ctrl, WindowManagerPolicyConstants.STRICT_MODE_LAYER);
            t.setPosition(ctrl, 0, 0);
            t.show(ctrl);
            // Ensure we aren't considered as obscuring for Input purposes.
            InputMonitor.setTrustedOverlayInputInfo(ctrl, t, dc.getDisplayId(), TITLE);
        } catch (OutOfResourcesException e) {
        }
        mSurfaceControl = ctrl;
        mDrawNeeded = true;

        mBlastBufferQueue = new BLASTBufferQueue(TITLE, mSurfaceControl, 1 /* width */,
                1 /* height */, PixelFormat.RGBA_8888);
        mSurface = mBlastBufferQueue.createSurface();
    }

    private void drawIfNeeded() {
        if (!mDrawNeeded) {
            return;
        }
        mDrawNeeded = false;
        final int dw = mLastDW;
        final int dh = mLastDH;
        mBlastBufferQueue.update(mSurfaceControl, dw, dh, PixelFormat.RGBA_8888);

        Canvas c = null;
        try {
            c = mSurface.lockCanvas(null);
        } catch (IllegalArgumentException | OutOfResourcesException e) {
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
    public void setVisibility(boolean on, SurfaceControl.Transaction t) {
        if (mSurfaceControl == null) {
            return;
        }
        drawIfNeeded();
        if (on) {
            t.show(mSurfaceControl);
        } else {
            t.hide(mSurfaceControl);
        }
    }

    void positionSurface(int dw, int dh, SurfaceControl.Transaction t) {
        if (mLastDW == dw && mLastDH == dh) {
            return;
        }
        mLastDW = dw;
        mLastDH = dh;
        t.setBufferSize(mSurfaceControl, dw, dh);
        mDrawNeeded = true;
    }

}
