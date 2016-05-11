/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.layoutlib.bridge.android.graphics;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.NinePatch;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * Canvas implementation that does not do any rendering
 */
public class NopCanvas extends Canvas {
    public NopCanvas() {
        super();
    }

    @Override
    public boolean isHardwareAccelerated() {
        // Return true so there is no allocations for the software renderer in the constructor
        return true;
    }

    @Override
    public int save() {
        return 0;
    }

    @Override
    public int save(int saveFlags) {
        return 0;
    }

    @Override
    public int saveLayer(RectF bounds, Paint paint, int saveFlags) {
        return 0;
    }

    @Override
    public int saveLayer(RectF bounds, Paint paint) {
        return 0;
    }

    @Override
    public int saveLayer(float left, float top, float right, float bottom, Paint paint,
            int saveFlags) {
        return 0;
    }

    @Override
    public int saveLayer(float left, float top, float right, float bottom, Paint paint) {
        return 0;
    }

    @Override
    public int saveLayerAlpha(RectF bounds, int alpha, int saveFlags) {
        return 0;
    }

    @Override
    public int saveLayerAlpha(RectF bounds, int alpha) {
        return 0;
    }

    @Override
    public int saveLayerAlpha(float left, float top, float right, float bottom, int alpha,
            int saveFlags) {
        return 0;
    }

    @Override
    public int saveLayerAlpha(float left, float top, float right, float bottom, int alpha) {
        return 0;
    }

    @Override
    public void restore() {
    }

    @Override
    public int getSaveCount() {
        return 0;
    }

    @Override
    public void restoreToCount(int saveCount) {
    }

    @Override
    public void drawRGB(int r, int g, int b) {
    }

    @Override
    public void drawARGB(int a, int r, int g, int b) {
    }

    @Override
    public void drawColor(int color) {
    }

    @Override
    public void drawColor(int color, Mode mode) {
    }

    @Override
    public void drawPaint(Paint paint) {
    }

    @Override
    public void drawPoints(float[] pts, int offset, int count, Paint paint) {
    }

    @Override
    public void drawPoints(float[] pts, Paint paint) {
    }

    @Override
    public void drawPoint(float x, float y, Paint paint) {
    }

    @Override
    public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {
    }

    @Override
    public void drawLines(float[] pts, int offset, int count, Paint paint) {
    }

    @Override
    public void drawLines(float[] pts, Paint paint) {
    }

    @Override
    public void drawRect(RectF rect, Paint paint) {
    }

    @Override
    public void drawRect(Rect r, Paint paint) {
    }

    @Override
    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
    }

    @Override
    public void drawOval(RectF oval, Paint paint) {
    }

    @Override
    public void drawOval(float left, float top, float right, float bottom, Paint paint) {
    }

    @Override
    public void drawCircle(float cx, float cy, float radius, Paint paint) {
    }

    @Override
    public void drawArc(RectF oval, float startAngle, float sweepAngle, boolean useCenter,
            Paint paint) {
    }

    @Override
    public void drawArc(float left, float top, float right, float bottom, float startAngle,
            float sweepAngle, boolean useCenter, Paint paint) {
    }

    @Override
    public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
    }

    @Override
    public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry,
            Paint paint) {
    }

    @Override
    public void drawPath(Path path, Paint paint) {
    }

    @Override
    protected void throwIfCannotDraw(Bitmap bitmap) {
    }

    @Override
    public void drawPatch(NinePatch patch, Rect dst, Paint paint) {
    }

    @Override
    public void drawPatch(NinePatch patch, RectF dst, Paint paint) {
    }

    @Override
    public void drawBitmap(Bitmap bitmap, float left, float top, Paint paint) {
    }

    @Override
    public void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) {
    }

    @Override
    public void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) {
    }

    @Override
    public void drawBitmap(int[] colors, int offset, int stride, float x, float y, int width,
            int height, boolean hasAlpha, Paint paint) {
    }

    @Override
    public void drawBitmap(int[] colors, int offset, int stride, int x, int y, int width,
            int height, boolean hasAlpha, Paint paint) {
    }

    @Override
    public void drawBitmap(Bitmap bitmap, Matrix matrix, Paint paint) {
    }

    @Override
    public void drawBitmapMesh(Bitmap bitmap, int meshWidth, int meshHeight, float[] verts,
            int vertOffset, int[] colors, int colorOffset, Paint paint) {
    }

    @Override
    public void drawVertices(VertexMode mode, int vertexCount, float[] verts, int vertOffset,
            float[] texs, int texOffset, int[] colors, int colorOffset, short[] indices,
            int indexOffset, int indexCount, Paint paint) {
    }

    @Override
    public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
    }

    @Override
    public void drawText(String text, float x, float y, Paint paint) {
    }

    @Override
    public void drawText(String text, int start, int end, float x, float y, Paint paint) {
    }

    @Override
    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
    }

    @Override
    public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount,
            float x, float y, boolean isRtl, Paint paint) {
    }

    @Override
    public void drawTextRun(CharSequence text, int start, int end, int contextStart, int contextEnd,
            float x, float y, boolean isRtl, Paint paint) {
    }

    @Override
    public void drawPosText(char[] text, int index, int count, float[] pos, Paint paint) {
    }

    @Override
    public void drawPosText(String text, float[] pos, Paint paint) {
    }

    @Override
    public void drawTextOnPath(char[] text, int index, int count, Path path, float hOffset,
            float vOffset, Paint paint) {
    }

    @Override
    public void drawTextOnPath(String text, Path path, float hOffset, float vOffset, Paint paint) {
    }

    @Override
    public void drawPicture(Picture picture) {
    }

    @Override
    public void drawPicture(Picture picture, RectF dst) {
    }

    @Override
    public void drawPicture(Picture picture, Rect dst) {
    }
}
