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

package android.view;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Pools.SynchronizedPool;

/**
 * An implementation of a GL canvas that records drawing operations.
 * This is intended for use with a DisplayList. This class keeps a list of all the Paint and
 * Bitmap objects that it draws, preventing the backing memory of Bitmaps from being freed while
 * the DisplayList is still holding a native reference to the memory.
 */
class GLES20RecordingCanvas extends GLES20Canvas {
    // The recording canvas pool should be large enough to handle a deeply nested
    // view hierarchy because display lists are generated recursively.
    private static final int POOL_LIMIT = 25;

    private static final SynchronizedPool<GLES20RecordingCanvas> sPool =
            new SynchronizedPool<GLES20RecordingCanvas>(POOL_LIMIT);

    private GLES20DisplayList mDisplayList;

    private GLES20RecordingCanvas() {
        super(true, true);
    }

    static GLES20RecordingCanvas obtain(GLES20DisplayList displayList) {
        GLES20RecordingCanvas canvas = sPool.acquire();
        if (canvas == null) {
            canvas = new GLES20RecordingCanvas();
        }
        canvas.mDisplayList = displayList;
        return canvas;
    }

    void recycle() {
        mDisplayList = null;
        resetDisplayListRenderer();
        sPool.release(this);
    }

    void start() {
        mDisplayList.mBitmaps.clear();
        mDisplayList.mChildDisplayLists.clear();
    }

    int end(int nativeDisplayList) {
        return getDisplayList(nativeDisplayList);
    }

    private void recordShaderBitmap(Paint paint) {
        if (paint != null) {
            final Shader shader = paint.getShader();
            if (shader instanceof BitmapShader) {
                mDisplayList.mBitmaps.add(((BitmapShader) shader).mBitmap);
            }
        }
    }

    @Override
    public void drawPatch(Bitmap bitmap, byte[] chunks, RectF dst, Paint paint) {
        super.drawPatch(bitmap, chunks, dst, paint);
        mDisplayList.mBitmaps.add(bitmap);
        // Shaders in the Paint are ignored when drawing a Bitmap
    }

    @Override
    public void drawBitmap(Bitmap bitmap, float left, float top, Paint paint) {
        super.drawBitmap(bitmap, left, top, paint);
        mDisplayList.mBitmaps.add(bitmap);
        // Shaders in the Paint are ignored when drawing a Bitmap
    }

    @Override
    public void drawBitmap(Bitmap bitmap, Matrix matrix, Paint paint) {
        super.drawBitmap(bitmap, matrix, paint);
        mDisplayList.mBitmaps.add(bitmap);
        // Shaders in the Paint are ignored when drawing a Bitmap
    }

    @Override
    public void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) {
        super.drawBitmap(bitmap, src, dst, paint);
        mDisplayList.mBitmaps.add(bitmap);
        // Shaders in the Paint are ignored when drawing a Bitmap
    }

    @Override
    public void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) {
        super.drawBitmap(bitmap, src, dst, paint);
        mDisplayList.mBitmaps.add(bitmap);
        // Shaders in the Paint are ignored when drawing a Bitmap
    }

    @Override
    public void drawBitmap(int[] colors, int offset, int stride, float x, float y, int width,
            int height, boolean hasAlpha, Paint paint) {
        super.drawBitmap(colors, offset, stride, x, y, width, height, hasAlpha, paint);
        // Shaders in the Paint are ignored when drawing a Bitmap
    }

    @Override
    public void drawBitmap(int[] colors, int offset, int stride, int x, int y, int width,
            int height, boolean hasAlpha, Paint paint) {
        super.drawBitmap(colors, offset, stride, x, y, width, height, hasAlpha, paint);
        // Shaders in the Paint are ignored when drawing a Bitmap
    }

    @Override
    public void drawBitmapMesh(Bitmap bitmap, int meshWidth, int meshHeight, float[] verts,
            int vertOffset, int[] colors, int colorOffset, Paint paint) {
        super.drawBitmapMesh(bitmap, meshWidth, meshHeight, verts, vertOffset,
                colors, colorOffset, paint);
        mDisplayList.mBitmaps.add(bitmap);
        // Shaders in the Paint are ignored when drawing a Bitmap
    }

    @Override
    public void drawCircle(float cx, float cy, float radius, Paint paint) {
        super.drawCircle(cx, cy, radius, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public int drawDisplayList(DisplayList displayList, Rect dirty, int flags) {
        int status = super.drawDisplayList(displayList, dirty, flags);
        mDisplayList.mChildDisplayLists.add(displayList);
        return status;
    }

    @Override
    public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {
        super.drawLine(startX, startY, stopX, stopY, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawLines(float[] pts, int offset, int count, Paint paint) {
        super.drawLines(pts, offset, count, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawLines(float[] pts, Paint paint) {
        super.drawLines(pts, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawOval(RectF oval, Paint paint) {
        super.drawOval(oval, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawPaint(Paint paint) {
        super.drawPaint(paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawPath(Path path, Paint paint) {
        super.drawPath(path, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawPoint(float x, float y, Paint paint) {
        super.drawPoint(x, y, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawPoints(float[] pts, int offset, int count, Paint paint) {
        super.drawPoints(pts, offset, count, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawPoints(float[] pts, Paint paint) {
        super.drawPoints(pts, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawPosText(char[] text, int index, int count, float[] pos, Paint paint) {
        super.drawPosText(text, index, count, pos, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawPosText(String text, float[] pos, Paint paint) {
        super.drawPosText(text, pos, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
        super.drawRect(left, top, right, bottom, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
        super.drawRoundRect(rect, rx, ry, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
        super.drawText(text, index, count, x, y, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
        super.drawText(text, start, end, x, y, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawText(String text, int start, int end, float x, float y, Paint paint) {
        super.drawText(text, start, end, x, y, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawText(String text, float x, float y, Paint paint) {
        super.drawText(text, x, y, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawTextOnPath(char[] text, int index, int count, Path path, float hOffset,
            float vOffset, Paint paint) {
        super.drawTextOnPath(text, index, count, path, hOffset, vOffset, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawTextOnPath(String text, Path path, float hOffset, float vOffset, Paint paint) {
        super.drawTextOnPath(text, path, hOffset, vOffset, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount,
            float x, float y, int dir, Paint paint) {
        super.drawTextRun(text, index, count, contextIndex, contextCount, x, y, dir, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawTextRun(CharSequence text, int start, int end, int contextStart,
            int contextEnd, float x, float y, int dir, Paint paint) {
        super.drawTextRun(text, start, end, contextStart, contextEnd, x, y, dir, paint);
        recordShaderBitmap(paint);
    }

    @Override
    public void drawVertices(VertexMode mode, int vertexCount, float[] verts, int vertOffset,
            float[] texs, int texOffset, int[] colors, int colorOffset, short[] indices,
            int indexOffset, int indexCount, Paint paint) {
        super.drawVertices(mode, vertexCount, verts, vertOffset, texs, texOffset, colors,
                colorOffset, indices, indexOffset, indexCount, paint);
        recordShaderBitmap(paint);
    }
}
