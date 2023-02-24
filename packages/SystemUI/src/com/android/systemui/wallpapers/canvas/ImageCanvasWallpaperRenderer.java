/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.wallpapers.canvas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;
import android.view.SurfaceHolder;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Helper to draw a wallpaper on a surface.
 * It handles the geometry regarding the dimensions of the display and the wallpaper,
 * and rescales the surface and the wallpaper accordingly.
 */
public class ImageCanvasWallpaperRenderer {

    private static final String TAG = ImageCanvasWallpaperRenderer.class.getSimpleName();
    private static final boolean DEBUG = false;

    private SurfaceHolder mSurfaceHolder;
    //private Bitmap mBitmap = null;

    @VisibleForTesting
    static final int MIN_SURFACE_WIDTH = 128;
    @VisibleForTesting
    static final int MIN_SURFACE_HEIGHT = 128;

    private boolean mSurfaceRedrawNeeded;

    private int mLastSurfaceWidth = -1;
    private int mLastSurfaceHeight = -1;

    public ImageCanvasWallpaperRenderer(SurfaceHolder surfaceHolder) {
        mSurfaceHolder = surfaceHolder;
    }

    /**
     * Set the surface holder on which to draw.
     * Should be called when the surface holder is created or changed
     * @param surfaceHolder the surface on which to draw the wallpaper
     */
    public void setSurfaceHolder(SurfaceHolder surfaceHolder) {
        mSurfaceHolder = surfaceHolder;
    }

    /**
     * Check if a surface holder is loaded
     * @return true if a valid surfaceHolder has been set.
     */
    public boolean isSurfaceHolderLoaded() {
        return mSurfaceHolder != null;
    }

    /**
     * Computes and set the surface dimensions, by using the play and the bitmap dimensions.
     * The Bitmap must be loaded before any call to this function
     */
    private boolean updateSurfaceSize(Bitmap bitmap) {
        int surfaceWidth = Math.max(MIN_SURFACE_WIDTH, bitmap.getWidth());
        int surfaceHeight = Math.max(MIN_SURFACE_HEIGHT, bitmap.getHeight());
        boolean surfaceChanged =
                surfaceWidth != mLastSurfaceWidth || surfaceHeight != mLastSurfaceHeight;
        if (surfaceChanged) {
            /*
             Used a fixed size surface, because we are special.  We can do
             this because we know the current design of window animations doesn't
             cause this to break.
            */
            mSurfaceHolder.setFixedSize(surfaceWidth, surfaceHeight);
            mLastSurfaceWidth = surfaceWidth;
            mLastSurfaceHeight = surfaceHeight;
        }
        return surfaceChanged;
    }

    /**
     * Draw a the wallpaper on the surface.
     * The bitmap and the surface must be loaded before calling
     * this function.
     * @param forceRedraw redraw the wallpaper even if no changes are detected
     */
    public void drawFrame(Bitmap bitmap, boolean forceRedraw) {

        if (bitmap == null || bitmap.isRecycled()) {
            Log.e(TAG, "Attempt to draw frame before background is loaded:");
            return;
        }

        if (bitmap.getWidth() < 1 || bitmap.getHeight() < 1) {
            Log.e(TAG, "Attempt to set an invalid wallpaper of length "
                    + bitmap.getWidth() + "x" + bitmap.getHeight());
            return;
        }

        mSurfaceRedrawNeeded |= forceRedraw;
        boolean surfaceChanged = updateSurfaceSize(bitmap);

        boolean redrawNeeded = surfaceChanged || mSurfaceRedrawNeeded;
        mSurfaceRedrawNeeded = false;

        if (!redrawNeeded) {
            if (DEBUG) {
                Log.d(TAG, "Suppressed drawFrame since redraw is not needed ");
            }
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "Redrawing wallpaper");
        }
        drawWallpaperWithCanvas(bitmap);
    }

    @VisibleForTesting
    void drawWallpaperWithCanvas(Bitmap bitmap) {
        Canvas c = mSurfaceHolder.lockHardwareCanvas();
        if (c != null) {
            Rect dest = mSurfaceHolder.getSurfaceFrame();
            Log.i(TAG, "Redrawing in rect: " + dest + " with surface size: "
                    + mLastSurfaceWidth + "x" + mLastSurfaceHeight);
            try {
                c.drawBitmap(bitmap, null, dest, null);
            } finally {
                mSurfaceHolder.unlockCanvasAndPost(c);
            }
        }
    }
}
