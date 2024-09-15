/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core;

import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;

/**
 * Specify an abstract paint context used by RemoteCompose commands to draw
 */
public abstract class PaintContext {
    protected RemoteContext mContext;

    public PaintContext(RemoteContext context) {
        this.mContext = context;
    }

    public void setContext(RemoteContext context) {
        this.mContext = context;
    }

    public abstract void drawBitmap(int imageId,
                                    int srcLeft, int srcTop, int srcRight, int srcBottom,
                                    int dstLeft, int dstTop, int dstRight, int dstBottom,
                                    int cdId);

    public abstract void scale(float scaleX, float scaleY);

    public abstract void translate(float translateX, float translateY);

    public abstract void drawArc(float left,
                                 float top,
                                 float right,
                                 float bottom,
                                 float startAngle,
                                 float sweepAngle);

    public abstract void drawBitmap(int id, float left, float top, float right, float bottom);

    public abstract void drawCircle(float centerX, float centerY, float radius);

    public abstract void drawLine(float x1, float y1, float x2, float y2);

    public abstract void drawOval(float left, float top, float right, float bottom);

    public abstract void drawPath(int id, float start, float end);

    public abstract void drawRect(float left, float top, float right, float bottom);

    public abstract void drawRoundRect(float left,
                                       float top,
                                       float right,
                                       float bottom,
                                       float radiusX,
                                       float radiusY);

    public abstract void drawTextOnPath(int textId, int pathId, float hOffset, float vOffset);

    /**
     * Return the dimensions (left, top, right, bottom).
     * Relative to a drawTextRun x=0, y=0;
     *
     * @param textId
     * @param start
     * @param end    if end is -1 it means the whole string
     * @param monospace measure with better support for monospace
     * @param bounds the bounds (left, top, right, bottom)
     */
    public abstract void getTextBounds(int textId,
                                       int start,
                                       int end,
                                       boolean monospace,
                                       float[]bounds);

    /**
     * Draw a text starting ast x,y
     *
     * @param textId reference to the text
     * @param start
     * @param end
     * @param contextStart
     * @param contextEnd
     * @param x
     * @param y
     * @param rtl
     */
    public abstract void drawTextRun(int textId,
                                     int start,
                                     int end,
                                     int contextStart,
                                     int contextEnd,
                                     float x,
                                     float y,
                                     boolean rtl);

    /**
     * Draw an interpolation between two paths
     * @param path1Id
     * @param path2Id
     * @param tween  0.0 = is path1 1.0 is path2
     * @param start
     * @param stop
     */
    public abstract void drawTweenPath(int path1Id,
                                       int path2Id,
                                       float tween,
                                       float start,
                                       float stop);

    public abstract void applyPaint(PaintBundle mPaintData);

    /**
     * Scale the rendering by scaleX and saleY (1.0 = no scale).
     * Scaling is done about centerX,centerY.
     *
     * @param scaleX
     * @param scaleY
     * @param centerX
     * @param centerY
     */
    public abstract void matrixScale(float scaleX, float scaleY, float centerX, float centerY);

    /**
     * Translate the rendering
     * @param translateX
     * @param translateY
     */
    public abstract void matrixTranslate(float translateX, float translateY);

    /**
     * Skew the rendering
     * @param skewX
     * @param skewY
     */
    public abstract void matrixSkew(float skewX, float skewY);

    /**
     * Rotate the rendering.
     * Note rotates are cumulative.
     * @param rotate angle to rotate
     * @param pivotX x-coordinate about which to rotate
     * @param pivotY y-coordinate about which to rotate
     */
    public abstract void matrixRotate(float rotate, float pivotX, float pivotY);

    /**
     * Save the current state of the transform
     */
    public abstract void matrixSave();

    /**
     * Restore the previously saved state of the transform
     */
    public abstract void matrixRestore();

    /**
     * Set the clip to a rectangle.
     * Drawing outside the current clip region will have no effect
     * @param left
     * @param top
     * @param right
     * @param bottom
     */
    public abstract void clipRect(float left, float top, float right, float bottom);

    /**
     * Clip based on a path.
     * @param pathId
     * @param regionOp
     */
    public abstract void clipPath(int pathId, int regionOp);

    /**
     * Reset the paint
     */
    public abstract void reset();
}

