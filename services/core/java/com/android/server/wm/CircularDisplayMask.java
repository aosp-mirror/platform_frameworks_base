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


import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SURFACE_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.Slog;
import android.view.Display;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

class CircularDisplayMask {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "CircularDisplayMask" : TAG_WM;

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
        if (mScreenSize.x != mScreenSize.y + screenOffset) {
            Slog.w(TAG, "Screen dimensions of displayId = " + display.getDisplayId() +
                    "are not equal, circularMask will not be drawn.");
            mDimensionsUnequal = true;
        }

        SurfaceControl ctrl = null;
        try {
            if (DEBUG_SURFACE_TRACE) {
                ctrl = new WindowSurfaceController.SurfaceTrace(session, "CircularDisplayMask",
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
        mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        mScreenOffset = screenOffset;
        mMaskThickness = maskThickness;
    }

    static private double distanceFromCenterSquared(double x, double y) {
        return x*x + y*y;
    }

    static private double distanceFromCenter(double x, double y) {
        return Math.sqrt(distanceFromCenterSquared(x, y));
    }

    static private double verticalLineIntersectsCircle(double x, double radius) {
        return Math.sqrt(radius*radius - x*x);
    }

    static private double  horizontalLineIntersectsCircle(double y, double radius) {
        return Math.sqrt(radius*radius - y*y);
    }

    static private double triangleArea(double width, double height) {
        return width * height / 2.0;
    }

    static private double trapezoidArea(double width, double height1, double height2) {
        return width * (height1 + height2) / 2.0;
    }

    static private double areaUnderChord(double radius, double chordLength) {
        double isocelesHeight = Math.sqrt(radius*radius - chordLength * chordLength / 4.0);
        double areaUnderIsoceles = isocelesHeight * chordLength / 2.0;
        double halfAngle = Math.asin(chordLength / (2.0 * radius));
        double areaUnderArc = halfAngle * radius * radius;

        return areaUnderArc - triangleArea(chordLength, isocelesHeight);
    }

    // Returns the fraction of the pixel at (px, py) covered by
    // the circle with center (cx, cy) and radius 'radius'
    static private double calcPixelShading(double cx, double cy, double px,
            double py, double radius) {
        // Translate so the center is at the origin
        px -= cx;
        py -= cy;

        // Reflect across the axis so the point is in the first quadrant
        px = Math.abs(px);
        py = Math.abs(py);

        // One more transformation which simplifies the logic later
        if (py > px) {
            double temp;

            temp = px;
            px = py;
            py = temp;
        }

        double left = px - 0.5;
        double right = px + 0.5;
        double bottom = py - 0.5;
        double top = py + 0.5;

        if (distanceFromCenterSquared(left, bottom) > radius*radius) {
            return 0.0;
        }

        if (distanceFromCenterSquared(right, top) < radius*radius) {
            return 1.0;
        }

        // Check if only the bottom-left corner of the pixel is inside the circle
        if (distanceFromCenterSquared(left, top) > radius*radius) {
            double triangleWidth = horizontalLineIntersectsCircle(bottom, radius) - left;
            double triangleHeight = verticalLineIntersectsCircle(left, radius) - bottom;
            double chordLength = distanceFromCenter(triangleWidth, triangleHeight);

            return triangleArea(triangleWidth, triangleHeight)
                   + areaUnderChord(radius, chordLength);

        }

        // Check if only the top-right corner of the pixel is outside the circle
        if (distanceFromCenterSquared(right, bottom) < radius*radius) {
            double triangleWidth = right - horizontalLineIntersectsCircle(top, radius);
            double triangleHeight = top - verticalLineIntersectsCircle(right, radius);
            double chordLength = distanceFromCenter(triangleWidth, triangleHeight);

            return 1 - triangleArea(triangleWidth, triangleHeight)
                   + areaUnderChord(radius, chordLength);
        }

        // It must be that the top-left and bottom-left corners are inside the circle
        double trapezoidWidth1 = horizontalLineIntersectsCircle(top, radius) - left;
        double trapezoidWidth2 = horizontalLineIntersectsCircle(bottom, radius) - left;
        double chordLength = distanceFromCenter(1, trapezoidWidth2 - trapezoidWidth1);
        double shading = trapezoidArea(1.0, trapezoidWidth1, trapezoidWidth2)
                         + areaUnderChord(radius, chordLength);

        // When top >= 0 and bottom <= 0 it's possible for the circle to intersect the pixel 4 times.
        // If so, remove the area of the section which crosses the right-hand edge.
        if (top >= 0 && bottom <= 0 && radius > right) {
            shading -= areaUnderChord(radius, 2 * verticalLineIntersectsCircle(right, radius));
        }

        return shading;
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

        c.drawColor(Color.BLACK);

        int maskWidth = mScreenSize.x - 2*mMaskThickness;
        int maskHeight;

        // Don't render the whole mask if it is partly offscreen.
        if (maskWidth > mScreenSize.y) {
            maskHeight = mScreenSize.y;
        } else {
            // To ensure the mask can be properly centered on the canvas the
            // bitmap dimensions must have the same parity as those of the canvas.
            maskHeight = mScreenSize.y - ((mScreenSize.y - maskWidth) & ~1);
        }

        double cx = (maskWidth - 1.0) / 2.0;
        double cy = (maskHeight - 1.0 + mScreenOffset) / 2.0;
        double radius = maskWidth / 2.0;
        int[] pixels = new int[maskWidth * maskHeight];

        for (int py=0; py<maskHeight; py++) {
            for (int px=0; px<maskWidth; px++) {
                double shading = calcPixelShading(cx, cy, px, py, radius);
                pixels[maskWidth*py + px] =
                    Color.argb(255 - (int)Math.round(255.0*shading), 0, 0, 0);
            }
        }

        Bitmap transparency = Bitmap.createBitmap(pixels, maskWidth, maskHeight,
            Bitmap.Config.ARGB_8888);

        c.drawBitmap(transparency,
                     (float)mMaskThickness,
                     (float)((mScreenSize.y - maskHeight) / 2),
                     mPaint);

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
