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

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.content.Context;
import android.graphics.BLASTBufferQueue;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.Display;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;

class EmulatorDisplayOverlay {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "EmulatorDisplayOverlay" : TAG_WM;

    private static final String TITLE = "EmulatorDisplayOverlay";

    // Display dimensions
    private Point mScreenSize;

    private final SurfaceControl mSurfaceControl;
    private final Surface mSurface;
    private final BLASTBufferQueue mBlastBufferQueue;

    private int mLastDW;
    private int mLastDH;
    private boolean mDrawNeeded;
    private final Drawable mOverlay;
    private int mRotation;
    private boolean mVisible;

    EmulatorDisplayOverlay(Context context, DisplayContent dc, int zOrder,
            SurfaceControl.Transaction t) {
        final Display display = dc.getDisplay();
        mScreenSize = new Point();
        display.getSize(mScreenSize);

        SurfaceControl ctrl = null;
        try {
            ctrl = dc.makeOverlay()
                    .setName(TITLE)
                    .setBLASTLayer()
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .setCallsite(TITLE)
                    .build();
            t.setLayer(ctrl, zOrder);
            t.setPosition(ctrl, 0, 0);
            t.show(ctrl);
            // Ensure we aren't considered as obscuring for Input purposes.
            InputMonitor.setTrustedOverlayInputInfo(ctrl, t, dc.getDisplayId(), TITLE);
        } catch (OutOfResourcesException e) {
        }
        mSurfaceControl = ctrl;
        mDrawNeeded = true;
        mOverlay = context.getDrawable(
                com.android.internal.R.drawable.emulator_circular_window_overlay);

        mBlastBufferQueue = new BLASTBufferQueue(TITLE, mSurfaceControl, mScreenSize.x,
                mScreenSize.y, PixelFormat.RGBA_8888);
        mSurface = mBlastBufferQueue.createSurface();
    }

    private void drawIfNeeded(SurfaceControl.Transaction t) {
        if (!mDrawNeeded || !mVisible) {
            return;
        }
        mDrawNeeded = false;

        Canvas c = null;
        try {
            c = mSurface.lockCanvas(null);
        } catch (IllegalArgumentException | OutOfResourcesException e) {
        }
        if (c == null) {
            return;
        }
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC);
        t.setPosition(mSurfaceControl, 0, 0);
        // Always draw the overlay with square dimensions
        int size = Math.max(mScreenSize.x, mScreenSize.y);
        mOverlay.setBounds(0, 0, size, size);
        mOverlay.draw(c);
        mSurface.unlockCanvasAndPost(c);
    }

    // Note: caller responsible for being inside
    // Surface.openTransaction() / closeTransaction()
    public void setVisibility(boolean on, SurfaceControl.Transaction t) {
        if (mSurfaceControl == null) {
            return;
        }
        mVisible = on;
        drawIfNeeded(t);
        if (on) {
            t.show(mSurfaceControl);
        } else {
            t.hide(mSurfaceControl);
        }
    }

    void positionSurface(int dw, int dh, int rotation, SurfaceControl.Transaction t) {
        if (mLastDW == dw && mLastDH == dh && mRotation == rotation) {
            return;
        }
        mLastDW = dw;
        mLastDH = dh;
        mDrawNeeded = true;
        mRotation = rotation;
        drawIfNeeded(t);
    }

}
