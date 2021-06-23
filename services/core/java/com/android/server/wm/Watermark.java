/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.graphics.BLASTBufferQueue;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;

/**
 * Displays a watermark on top of the window manager's windows.
 */
class Watermark {
    private static final String TITLE = "WatermarkSurface";

    private final String mText;
    private final Paint mTextPaint;
    private final int mTextWidth;
    private final int mTextHeight;
    private final int mDeltaX;
    private final int mDeltaY;

    private final SurfaceControl mSurfaceControl;
    private final Surface mSurface;
    private final BLASTBufferQueue mBlastBufferQueue;

    private int mLastDW;
    private int mLastDH;
    private boolean mDrawNeeded;

    Watermark(DisplayContent dc, DisplayMetrics dm, String[] tokens, SurfaceControl.Transaction t) {
        if (false) {
            Log.i(TAG_WM, "*********************** WATERMARK");
            for (int i=0; i<tokens.length; i++) {
                Log.i(TAG_WM, "  TOKEN #" + i + ": " + tokens[i]);
            }
        }

        StringBuilder builder = new StringBuilder(32);
        int len = tokens[0].length();
        len = len & ~1;
        for (int i=0; i<len; i+=2) {
            int c1 = tokens[0].charAt(i);
            int c2 = tokens[0].charAt(i + 1);
            if (c1 >= 'a' && c1 <= 'f') c1 = c1 - 'a' + 10;
            else if (c1 >= 'A' && c1 <= 'F') c1 = c1 - 'A' + 10;
            else c1 -= '0';
            if (c2 >= 'a' && c2 <= 'f') c2 = c2 - 'a' + 10;
            else if (c2 >= 'A' && c2 <= 'F') c2 = c2 - 'A' + 10;
            else c2 -= '0';
            builder.append((char)(255-((c1*16)+c2)));
        }
        mText = builder.toString();
        if (false) {
            Log.i(TAG_WM, "Final text: " + mText);
        }

        int fontSize = WindowManagerService.getPropertyInt(tokens, 1,
                TypedValue.COMPLEX_UNIT_DIP, 20, dm);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setTextSize(fontSize);
        mTextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        FontMetricsInt fm = mTextPaint.getFontMetricsInt();
        mTextWidth = (int)mTextPaint.measureText(mText);
        mTextHeight = fm.descent - fm.ascent;

        mDeltaX = WindowManagerService.getPropertyInt(tokens, 2,
                TypedValue.COMPLEX_UNIT_PX, mTextWidth*2, dm);
        mDeltaY = WindowManagerService.getPropertyInt(tokens, 3,
                TypedValue.COMPLEX_UNIT_PX, mTextHeight*3, dm);
        int shadowColor = WindowManagerService.getPropertyInt(tokens, 4,
                TypedValue.COMPLEX_UNIT_PX, 0xb0000000, dm);
        int color = WindowManagerService.getPropertyInt(tokens, 5,
                TypedValue.COMPLEX_UNIT_PX, 0x60ffffff, dm);
        int shadowRadius = WindowManagerService.getPropertyInt(tokens, 6,
                TypedValue.COMPLEX_UNIT_PX, 7, dm);
        int shadowDx = WindowManagerService.getPropertyInt(tokens, 8,
                TypedValue.COMPLEX_UNIT_PX, 0, dm);
        int shadowDy = WindowManagerService.getPropertyInt(tokens, 9,
                TypedValue.COMPLEX_UNIT_PX, 0, dm);

        mTextPaint.setColor(color);
        mTextPaint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor);

        SurfaceControl ctrl = null;
        try {
            ctrl = dc.makeOverlay()
                    .setName(TITLE)
                    .setBLASTLayer()
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .setCallsite(TITLE)
                    .build();
            t.setLayer(ctrl, WindowManagerService.TYPE_LAYER_MULTIPLIER * 100)
                    .setPosition(ctrl, 0, 0)
                    .show(ctrl);
            // Ensure we aren't considered as obscuring for Input purposes.
            InputMonitor.setTrustedOverlayInputInfo(ctrl, t, dc.getDisplayId(), TITLE);
        } catch (OutOfResourcesException e) {
        }
        mSurfaceControl = ctrl;
        mBlastBufferQueue = new BLASTBufferQueue(TITLE, mSurfaceControl, 1 /* width */,
                1 /* height */, PixelFormat.RGBA_8888);
        mSurface = mBlastBufferQueue.createSurface();
    }

    void positionSurface(int dw, int dh, SurfaceControl.Transaction t) {
        if (mLastDW != dw || mLastDH != dh) {
            mLastDW = dw;
            mLastDH = dh;
            t.setBufferSize(mSurfaceControl, dw, dh);
            mDrawNeeded = true;
        }
    }

    void drawIfNeeded() {
        if (!mDrawNeeded) {
            return;
        }

        final int dw = mLastDW;
        final int dh = mLastDH;

        mDrawNeeded = false;
        mBlastBufferQueue.update(mSurfaceControl, dw, dh, PixelFormat.RGBA_8888);
        Canvas c = null;
        try {
            c = mSurface.lockCanvas(null);
        } catch (IllegalArgumentException | OutOfResourcesException e) {
        }
        if (c != null) {
            c.drawColor(0, PorterDuff.Mode.CLEAR);

            int deltaX = mDeltaX;
            int deltaY = mDeltaY;

            // deltaX shouldn't be close to a round fraction of our
            // x step, or else things will line up too much.
            int div = (dw + mTextWidth) / deltaX;
            int rem = (dw + mTextWidth) - (div * deltaX);
            int qdelta = deltaX / 4;
            if (rem < qdelta || rem > (deltaX - qdelta)) {
                deltaX += deltaX / 3;
            }

            int y = -mTextHeight;
            int x = -mTextWidth;
            while (y < (dh + mTextHeight)) {
                c.drawText(mText, x, y, mTextPaint);
                x += deltaX;
                if (x >= dw) {
                    x -= (dw + mTextWidth);
                    y += deltaY;
                }
            }
            mSurface.unlockCanvasAndPost(c);
        }
    }
}
