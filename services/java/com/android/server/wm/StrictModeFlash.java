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


import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceSession;

class StrictModeFlash {
    private static final String TAG = "StrictModeFlash";

    Surface mSurface;
    int mLastDW;
    int mLastDH;
    boolean mDrawNeeded;
    final int mThickness = 20;

    public StrictModeFlash(Display display, SurfaceSession session) {
        try {
            mSurface = new Surface(session, "StrictModeFlash",
                1, 1, PixelFormat.TRANSLUCENT, Surface.HIDDEN);
        } catch (Surface.OutOfResourcesException e) {
            return;
        }

        mSurface.setLayerStack(display.getLayerStack());
        mSurface.setLayer(WindowManagerService.TYPE_LAYER_MULTIPLIER * 101);  // one more than Watermark? arbitrary.
        mSurface.setPosition(0, 0);
        mSurface.show();
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
        c.clipRect(new Rect(0, 0, dw, mThickness), Region.Op.REPLACE);
        c.drawColor(Color.RED);
        // Left
        c.clipRect(new Rect(0, 0, mThickness, dh), Region.Op.REPLACE);
        c.drawColor(Color.RED);
        // Right
        c.clipRect(new Rect(dw - mThickness, 0, dw, dh), Region.Op.REPLACE);
        c.drawColor(Color.RED);
        // Bottom
        c.clipRect(new Rect(0, dh - mThickness, dw, dh), Region.Op.REPLACE);
        c.drawColor(Color.RED);

        mSurface.unlockCanvasAndPost(c);
    }

    // Note: caller responsible for being inside
    // Surface.openTransaction() / closeTransaction()
    public void setVisibility(boolean on) {
        if (mSurface == null) {
            return;
        }
        drawIfNeeded();
        if (on) {
            mSurface.show();
        } else {
            mSurface.hide();
        }
    }

    void positionSurface(int dw, int dh) {
        if (mLastDW == dw && mLastDH == dh) {
            return;
        }
        mLastDW = dw;
        mLastDH = dh;
        mSurface.setSize(dw, dh);
        mDrawNeeded = true;
    }

}
