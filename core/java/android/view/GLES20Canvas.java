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
import android.graphics.Canvas;
import android.graphics.DrawFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;

import javax.microedition.khronos.opengles.GL;

/**
 * An implementation of Canvas on top of OpenGL ES 2.0.
 */
@SuppressWarnings({"deprecation"})
class GLES20Canvas extends Canvas {
    @SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"})
    private final GL mGl;
    private final boolean mOpaque;
    private final int mRenderer;
    
    private int mWidth;
    private int mHeight;

    ///////////////////////////////////////////////////////////////////////////
    // Constructors
    ///////////////////////////////////////////////////////////////////////////
    
    GLES20Canvas(GL gl, boolean translucent) {
        mGl = gl;
        mOpaque = !translucent;

        mRenderer = nCreateRenderer();
    }

    private native int nCreateRenderer();

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            nDestroyRenderer(mRenderer);
        }
    }
    
    private native void nDestroyRenderer(int renderer);

    ///////////////////////////////////////////////////////////////////////////
    // Canvas management
    ///////////////////////////////////////////////////////////////////////////
    
    @Override
    public boolean isHardwareAccelerated() {
        return true;
    }

    @Override
    public GL getGL() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setBitmap(Bitmap bitmap) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOpaque() {
        return mOpaque;
    }

    @Override
    public int getWidth() {
        return mWidth;
    }

    @Override
    public int getHeight() {
        return mHeight;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Setup
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void setViewport(int width, int height) {
        mWidth = width;
        mHeight = height;

        nSetViewport(mRenderer, width, height);
    }
    
    private native void nSetViewport(int renderer, int width, int height);

    void onPreDraw() {
        nPrepare(mRenderer);
    }
    
    private native void nPrepare(int renderer);

    ///////////////////////////////////////////////////////////////////////////
    // Clipping
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public boolean clipPath(Path path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean clipPath(Path path, Region.Op op) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean clipRect(float left, float top, float right, float bottom) {
        return nClipRect(mRenderer, left, top, right, bottom);
    }
    
    private native boolean nClipRect(int renderer, float left, float top, float right, float bottom);

    @Override
    public boolean clipRect(float left, float top, float right, float bottom, Region.Op op) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean clipRect(int left, int top, int right, int bottom) {
        return nClipRect(mRenderer, left, top, right, bottom);        
    }
    
    private native boolean nClipRect(int renderer, int left, int top, int right, int bottom);

    @Override
    public boolean clipRect(Rect rect) {
        return clipRect(rect.left, rect.top, rect.right, rect.bottom);        
    }

    @Override
    public boolean clipRect(Rect rect, Region.Op op) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean clipRect(RectF rect) {
        return clipRect(rect.left, rect.top, rect.right, rect.bottom);
    }

    @Override
    public boolean clipRect(RectF rect, Region.Op op) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean clipRegion(Region region) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean clipRegion(Region region, Region.Op op) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean getClipBounds(Rect bounds) {
        // TODO: Implement
        return false;
    }

    @Override
    public boolean quickReject(float left, float top, float right, float bottom, EdgeType type) {
        // TODO: Implement
        return false;
    }

    @Override
    public boolean quickReject(Path path, EdgeType type) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean quickReject(RectF rect, EdgeType type) {
        return quickReject(rect.left, rect.top, rect.right, rect.bottom, type);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Transformations
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void translate(float dx, float dy) {
        // TODO: Implement
    }

    @Override
    public void skew(float sx, float sy) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void rotate(float degrees) {
        // TODO: Implement
    }

    @Override
    public void scale(float sx, float sy) {
        // TODO: Implement
    }

    
    @Override
    public void setMatrix(Matrix matrix) {
        // TODO: Implement
    }

    @Override
    public void getMatrix(Matrix ctm) {
        // TODO: Implement
    }

    @Override
    public void concat(Matrix matrix) {
        // TODO: Implement
    }
    
    ///////////////////////////////////////////////////////////////////////////
    // State management
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public int save() {
        return nSave(mRenderer, 0);
    }
    
    @Override
    public int save(int saveFlags) {
        return nSave(mRenderer, saveFlags);
    }

    private native int nSave(int renderer, int flags);
    
    @Override
    public int saveLayer(RectF bounds, Paint paint, int saveFlags) {
        // TODO: Implement
        return 0;
    }

    @Override
    public int saveLayer(float left, float top, float right, float bottom, Paint paint,
            int saveFlags) {
        // TODO: Implement
        return 0;
    }

    @Override
    public int saveLayerAlpha(RectF bounds, int alpha, int saveFlags) {
        // TODO: Implement
        return 0;
    }

    @Override
    public int saveLayerAlpha(float left, float top, float right, float bottom, int alpha,
            int saveFlags) {
        // TODO: Implement
        return 0;
    }

    @Override
    public void restore() {
        nRestore(mRenderer);
    }
    
    private native void nRestore(int renderer);

    @Override
    public void restoreToCount(int saveCount) {
        nRestoreToCount(mRenderer, saveCount);
    }

    private native void nRestoreToCount(int renderer, int saveCount);
    
    @Override
    public int getSaveCount() {
        return nGetSaveCount(mRenderer);
    }
    
    private native int nGetSaveCount(int renderer);

    ///////////////////////////////////////////////////////////////////////////
    // Filtering
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void setDrawFilter(DrawFilter filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public DrawFilter getDrawFilter() {
        throw new UnsupportedOperationException();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Drawing
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void drawArc(RectF oval, float startAngle, float sweepAngle, boolean useCenter,
            Paint paint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawARGB(int a, int r, int g, int b) {
        drawColor((a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF));
    }

    @Override
    public void drawBitmap(Bitmap bitmap, float left, float top, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawBitmap(Bitmap bitmap, Matrix matrix, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawBitmap(int[] colors, int offset, int stride, float x, float y,
            int width, int height, boolean hasAlpha, Paint paint) {

        // TODO: Implement
    }

    @Override
    public void drawBitmap(int[] colors, int offset, int stride, int x, int y,
            int width, int height, boolean hasAlpha, Paint paint) {

        // TODO: Implement
    }

    @Override
    public void drawBitmapMesh(Bitmap bitmap, int meshWidth, int meshHeight, float[] verts,
            int vertOffset, int[] colors, int colorOffset, Paint paint) {

        throw new UnsupportedOperationException();
    }

    @Override
    public void drawCircle(float cx, float cy, float radius, Paint paint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawColor(int color) {
        drawColor(color, PorterDuff.Mode.SRC_OVER);
    }

    @Override
    public void drawColor(int color, PorterDuff.Mode mode) {
        nDrawColor(mRenderer, color, mode.nativeInt);
    }
    
    private native void nDrawColor(int renderer, int color, int mode);

    @Override
    public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawLines(float[] pts, int offset, int count, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawLines(float[] pts, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawOval(RectF oval, Paint paint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawPaint(Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawPath(Path path, Paint paint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawPicture(Picture picture) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawPicture(Picture picture, Rect dst) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawPicture(Picture picture, RectF dst) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawPoint(float x, float y, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawPoints(float[] pts, int offset, int count, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawPoints(float[] pts, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawPosText(char[] text, int index, int count, float[] pos, Paint paint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawPosText(String text, float[] pos, Paint paint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawRect(Rect r, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawRect(RectF rect, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawRGB(int r, int g, int b) {
        drawColor(0xFF000000 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF));
    }

    @Override
    public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawText(String text, int start, int end, float x, float y, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawText(String text, float x, float y, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawTextOnPath(char[] text, int index, int count, Path path, float hOffset,
            float vOffset, Paint paint) {

        throw new UnsupportedOperationException();
    }

    @Override
    public void drawTextOnPath(String text, Path path, float hOffset, float vOffset, Paint paint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount,
            float x, float y, int dir, Paint paint) {
        
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawTextRun(CharSequence text, int start, int end, int contextStart, int contextEnd,
            float x, float y, int dir, Paint paint) {

        throw new UnsupportedOperationException();
    }

    @Override
    public void drawVertices(VertexMode mode, int vertexCount, float[] verts, int vertOffset,
            float[] texs, int texOffset, int[] colors, int colorOffset, short[] indices,
            int indexOffset, int indexCount, Paint paint) {

        throw new UnsupportedOperationException();
    }
}
