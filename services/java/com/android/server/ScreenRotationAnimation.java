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

package com.android.server;  // TODO: use com.android.server.wm, once things move there

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceSession;

class ScreenRotationAnimation {
    private static final String TAG = "ScreenRotationAnimation";

    Surface mSurface;
    int mWidth, mHeight;

    int mBaseRotation;
    int mCurRotation;
    int mDeltaRotation;

    final Matrix mMatrix = new Matrix();
    final float[] mTmpFloats = new float[9];

    public ScreenRotationAnimation(Display display, SurfaceSession session) {
        final DisplayMetrics dm = new DisplayMetrics();
        display.getMetrics(dm);

        Bitmap screenshot = Surface.screenshot(0, 0);

        if (screenshot != null) {
            // Screenshot does NOT include rotation!
            mBaseRotation = 0;
            mWidth = screenshot.getWidth();
            mHeight = screenshot.getHeight();
        } else {
            // Just in case.
            mBaseRotation = display.getRotation();
            mWidth = dm.widthPixels;
            mHeight = dm.heightPixels;
        }

        Surface.openTransaction();
        if (mSurface != null) {
            mSurface.destroy();
            mSurface = null;
        }
        try {
            mSurface = new Surface(session, 0, "FreezeSurface",
                    -1, mWidth, mHeight, PixelFormat.OPAQUE, 0);
        } catch (Surface.OutOfResourcesException e) {
            Slog.w(TAG, "Unable to allocate freeze surface", e);
        }
        mSurface.setLayer(WindowManagerService.TYPE_LAYER_MULTIPLIER * 200);
        setRotation(display.getRotation());

        Rect dirty = new Rect(0, 0, mWidth, mHeight);
        Canvas c = null;
        try {
            c = mSurface.lockCanvas(dirty);
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Unable to lock surface", e);
            return;
        } catch (Surface.OutOfResourcesException e) {
            Slog.w(TAG, "Unable to lock surface", e);
            return;
        }
        if (c == null) {
            Slog.w(TAG, "Null surface");
            return;
        }

        if (screenshot != null) {
            c.drawBitmap(screenshot, 0, 0, new Paint(0));
        } else {
            c.drawColor(Color.GREEN);
        }

        mSurface.unlockCanvasAndPost(c);
        Surface.closeTransaction();

        screenshot.recycle();
    }

    // Must be called while in a transaction.
    public void setRotation(int rotation) {
        mCurRotation = rotation;
        int delta = mCurRotation - mBaseRotation;
        if (delta < 0) delta += 4;
        mDeltaRotation = delta;

        switch (delta) {
            case Surface.ROTATION_0:
                mMatrix.reset();
                break;
            case Surface.ROTATION_90:
                mMatrix.setRotate(90, 0, 0);
                mMatrix.postTranslate(0, mWidth);
                break;
            case Surface.ROTATION_180:
                mMatrix.setRotate(180, 0, 0);
                mMatrix.postTranslate(mWidth, mHeight);
                break;
            case Surface.ROTATION_270:
                mMatrix.setRotate(270, 0, 0);
                mMatrix.postTranslate(mHeight, 0);
                break;
        }

        mMatrix.getValues(mTmpFloats);
        mSurface.setPosition((int)mTmpFloats[Matrix.MTRANS_X],
                (int)mTmpFloats[Matrix.MTRANS_Y]);
        mSurface.setMatrix(
                mTmpFloats[Matrix.MSCALE_X], mTmpFloats[Matrix.MSKEW_X],
                mTmpFloats[Matrix.MSKEW_Y], mTmpFloats[Matrix.MSCALE_Y]);

        if (false) {
            float[] srcPnts = new float[] { 0, 0, mWidth, mHeight };
            float[] dstPnts = new float[8];
            mMatrix.mapPoints(dstPnts, srcPnts);
            Slog.i(TAG, "**** ROTATION: " + delta);
            Slog.i(TAG, "Original  : (" + srcPnts[0] + "," + srcPnts[1]
                    + ")-(" + srcPnts[2] + "," + srcPnts[3] + ")");
            Slog.i(TAG, "Transformed: (" + dstPnts[0] + "," + dstPnts[1]
                    + ")-(" + dstPnts[2] + "," + dstPnts[3] + ")");
        }
    }

    public void dismiss() {
        mSurface.destroy();
    }
}
