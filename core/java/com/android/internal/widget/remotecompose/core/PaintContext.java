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

    public abstract void drawTextRun(int textID,
                                     int start,
                                     int end,
                                     int contextStart,
                                     int contextEnd,
                                     float x,
                                     float y,
                                     boolean rtl);

    public abstract void drawTweenPath(int path1Id,
                                       int path2Id,
                                       float tween,
                                       float start,
                                       float stop);

    public abstract void applyPaint(PaintBundle mPaintData);

    public abstract void mtrixScale(float scaleX, float scaleY, float centerX, float centerY);

    public abstract void matrixTranslate(float translateX, float translateY);

    public abstract void matrixSkew(float skewX, float skewY);

    public abstract void matrixRotate(float rotate, float pivotX, float pivotY);

    public abstract void matrixSave();

    public abstract void matrixRestore();

    public abstract void clipRect(float left, float top, float right, float bottom);

    public abstract void clipPath(int pathId, int regionOp);

}

