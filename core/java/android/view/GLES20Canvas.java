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
import android.graphics.Canvas;
import android.graphics.DrawFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.TemporaryBuffer;
import android.text.GraphicsOperations;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.TextUtils;

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
    
    private final float[] mPoint = new float[2];
    private final float[] mLine = new float[4];
    
    private final Rect mClipBounds = new Rect();

    private DrawFilter mFilter;

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
        return nClipRect(mRenderer, left, top, right, bottom, Region.Op.INTERSECT.nativeInt);
    }
    
    private native boolean nClipRect(int renderer, float left, float top,
            float right, float bottom, int op);

    @Override
    public boolean clipRect(float left, float top, float right, float bottom, Region.Op op) {
        return nClipRect(mRenderer, left, top, right, bottom, op.nativeInt);
    }

    @Override
    public boolean clipRect(int left, int top, int right, int bottom) {
        return nClipRect(mRenderer, left, top, right, bottom, Region.Op.INTERSECT.nativeInt);        
    }
    
    private native boolean nClipRect(int renderer, int left, int top, int right, int bottom, int op);

    @Override
    public boolean clipRect(Rect rect) {
        return nClipRect(mRenderer, rect.left, rect.top, rect.right, rect.bottom,
                Region.Op.INTERSECT.nativeInt);        
    }

    @Override
    public boolean clipRect(Rect rect, Region.Op op) {
        return nClipRect(mRenderer, rect.left, rect.top, rect.right, rect.bottom, op.nativeInt);
    }

    @Override
    public boolean clipRect(RectF rect) {
        return nClipRect(mRenderer, rect.left, rect.top, rect.right, rect.bottom,
                Region.Op.INTERSECT.nativeInt);
    }

    @Override
    public boolean clipRect(RectF rect, Region.Op op) {
        return nClipRect(mRenderer, rect.left, rect.top, rect.right, rect.bottom, op.nativeInt);
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
        return nGetClipBounds(mRenderer, bounds);
    }

    private native boolean nGetClipBounds(int renderer, Rect bounds);

    @Override
    public boolean quickReject(float left, float top, float right, float bottom, EdgeType type) {
        return nQuickReject(mRenderer, left, top, right, bottom, type.nativeInt);
    }
    
    private native boolean nQuickReject(int renderer, float left, float top,
            float right, float bottom, int edge);

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
        nTranslate(mRenderer, dx, dy);
    }
    
    private native void nTranslate(int renderer, float dx, float dy);

    @Override
    public void skew(float sx, float sy) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void rotate(float degrees) {
        nRotate(mRenderer, degrees);
    }
    
    private native void nRotate(int renderer, float degrees);

    @Override
    public void scale(float sx, float sy) {
        nScale(mRenderer, sx, sy);
    }
    
    private native void nScale(int renderer, float sx, float sy);

    @Override
    public void setMatrix(Matrix matrix) {
        nSetMatrix(mRenderer, matrix.native_instance);
    }
    
    private native void nSetMatrix(int renderer, int matrix);

    @Override
    public void getMatrix(Matrix matrix) {
        nGetMatrix(mRenderer, matrix.native_instance);
    }
    
    private native void nGetMatrix(int renderer, int matrix);

    @Override
    public void concat(Matrix matrix) {
        nConcatMatrix(mRenderer, matrix.native_instance);
    }
    
    private native void nConcatMatrix(int renderer, int matrix);
    
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
        return saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, paint, saveFlags);
    }

    @Override
    public int saveLayer(float left, float top, float right, float bottom, Paint paint,
            int saveFlags) {
        int nativePaint = paint == null ? 0 : paint.mNativePaint;
        return nSaveLayer(mRenderer, left, top, right, bottom, nativePaint, saveFlags);
    }

    private native int nSaveLayer(int renderer, float left, float top, float right, float bottom,
            int paint, int saveFlags);

    @Override
    public int saveLayerAlpha(RectF bounds, int alpha, int saveFlags) {
        return saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom,
                alpha, saveFlags);
    }

    @Override
    public int saveLayerAlpha(float left, float top, float right, float bottom, int alpha,
            int saveFlags) {
        return nSaveLayerAlpha(mRenderer, left, top, right, bottom, alpha, saveFlags);
    }

    private native int nSaveLayerAlpha(int renderer, float left, float top, float right,
            float bottom, int alpha, int saveFlags);
    
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
        // TODO: Implement PaintDrawFilter
        mFilter = filter;
    }

    @Override
    public DrawFilter getDrawFilter() {
        return mFilter;
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
    public void drawPatch(Bitmap bitmap, byte[] chunks, RectF dst, Paint paint) {
        // Shaders are ignored when drawing patches
        final int nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawPatch(mRenderer, bitmap.mNativeBitmap, chunks, dst.left, dst.top,
                dst.right, dst.bottom, nativePaint);
    }

    private native void nDrawPatch(int renderer, int bitmap, byte[] chunks, float left, float top,
            float right, float bottom, int paint);

    @Override
    public void drawBitmap(Bitmap bitmap, float left, float top, Paint paint) {
        // Shaders are ignored when drawing bitmaps
        final int nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawBitmap(mRenderer, bitmap.mNativeBitmap, left, top, nativePaint);
    }

    private native void nDrawBitmap(int renderer, int bitmap, float left, float top, int paint);

    @Override
    public void drawBitmap(Bitmap bitmap, Matrix matrix, Paint paint) {
        // Shaders are ignored when drawing bitmaps
        final int nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawBitmap(mRenderer, bitmap.mNativeBitmap, matrix.native_instance, nativePaint);
    }

    private native void nDrawBitmap(int renderer, int bitmap, int matrix, int paint);

    @Override
    public void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) {
        // Shaders are ignored when drawing bitmaps
        final int nativePaint = paint == null ? 0 : paint.mNativePaint;

        int left, top, right, bottom;
        if (src == null) {
            left = top = 0;
            right = bitmap.getWidth();
            bottom = bitmap.getHeight();
        } else {
            left = src.left;
            right = src.right;
            top = src.top;
            bottom = src.bottom;
        }

        nDrawBitmap(mRenderer, bitmap.mNativeBitmap, left, top, right, bottom,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint);
    }

    @Override
    public void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) {
        // Shaders are ignored when drawing bitmaps
        final int nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawBitmap(mRenderer, bitmap.mNativeBitmap, src.left, src.top, src.right, src.bottom,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint);
    }

    private native void nDrawBitmap(int renderer, int bitmap,
            float srcLeft, float srcTop, float srcRight, float srcBottom,
            float left, float top, float right, float bottom, int paint);

    @Override
    public void drawBitmap(int[] colors, int offset, int stride, float x, float y,
            int width, int height, boolean hasAlpha, Paint paint) {
        // Shaders are ignored when drawing bitmaps
        final Bitmap.Config config = hasAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        final Bitmap b = Bitmap.createBitmap(colors, offset, stride, width, height, config);
        final int nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawBitmap(mRenderer, b.mNativeBitmap, x, y, nativePaint);
        b.recycle();
    }

    @Override
    public void drawBitmap(int[] colors, int offset, int stride, int x, int y,
            int width, int height, boolean hasAlpha, Paint paint) {
        // Shaders are ignored when drawing bitmaps
        drawBitmap(colors, offset, stride, (float) x, (float) y, width, height, hasAlpha, paint);
    }

    @Override
    public void drawBitmapMesh(Bitmap bitmap, int meshWidth, int meshHeight, float[] verts,
            int vertOffset, int[] colors, int colorOffset, Paint paint) {
        // TODO: Implement
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
        mLine[0] = startX;
        mLine[1] = startY;
        mLine[2] = stopX;
        mLine[3] = stopY;
        drawLines(mLine, 0, 1, paint);
    }

    @Override
    public void drawLines(float[] pts, int offset, int count, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawLines(float[] pts, Paint paint) {
        drawLines(pts, 0, pts.length / 4, paint);
    }

    @Override
    public void drawOval(RectF oval, Paint paint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawPaint(Paint paint) {
        final Rect r = mClipBounds;
        nGetClipBounds(mRenderer, r);
        drawRect(r.left, r.top, r.right, r.bottom, paint);
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
        mPoint[0] = x;
        mPoint[1] = y;
        drawPoints(mPoint, 0, 1, paint);
    }

    @Override
    public void drawPoints(float[] pts, int offset, int count, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawPoints(float[] pts, Paint paint) {
        drawPoints(pts, 0, pts.length / 2, paint);
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
        boolean hasShader = setupShader(paint);
        nDrawRect(mRenderer, left, top, right, bottom, paint.mNativePaint);
        if (hasShader) nResetShader(mRenderer);
    }

    private native void nDrawRect(int renderer, float left, float top, float right, float bottom,
            int paint);

    @Override
    public void drawRect(Rect r, Paint paint) {
        drawRect(r.left, r.top, r.right, r.bottom, paint);
    }

    @Override
    public void drawRect(RectF r, Paint paint) {
        drawRect(r.left, r.top, r.right, r.bottom, paint);
    }

    @Override
    public void drawRGB(int r, int g, int b) {
        drawColor(0xFF000000 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF));
    }

    @Override
    public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
        if ((index | count | (index + count) | (text.length - index - count)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        nDrawText(mRenderer, text, index, count, x, y, paint.mBidiFlags, paint.mNativePaint);
    }
    
    private native void nDrawText(int renderer, char[] text, int index, int count, float x, float y,
            int bidiFlags, int paint);

    @Override
    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
        if (text instanceof String || text instanceof SpannedString ||
                text instanceof SpannableString) {
            nDrawText(mRenderer, text.toString(), start, end, x, y, paint.mBidiFlags,
                    paint.mNativePaint);
        } else if (text instanceof GraphicsOperations) {
            ((GraphicsOperations) text).drawText(this, start, end, x, y,
                                                     paint);
        } else {
            char[] buf = TemporaryBuffer.obtain(end - start);
            TextUtils.getChars(text, start, end, buf, 0);
            nDrawText(mRenderer, buf, 0, end - start, x, y, paint.mBidiFlags, paint.mNativePaint);
            TemporaryBuffer.recycle(buf);
        }
    }

    @Override
    public void drawText(String text, int start, int end, float x, float y, Paint paint) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        nDrawText(mRenderer, text, start, end, x, y, paint.mBidiFlags, paint.mNativePaint);
    }

    private native void nDrawText(int renderer, String text, int start, int end, float x, float y,
            int bidiFlags, int paint);

    @Override
    public void drawText(String text, float x, float y, Paint paint) {
        nDrawText(mRenderer, text, 0, text.length(), x, y, paint.mBidiFlags, paint.mNativePaint);
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
        // TODO: Implement
    }

    private boolean setupShader(Paint paint) {
        final Shader shader = paint.getShader();
        if (shader != null) {
            if (shader instanceof BitmapShader) {
                final BitmapShader bs = (BitmapShader) shader;
                nSetupBitmapShader(mRenderer, bs.native_instance, bs.mBitmap.mNativeBitmap,
                        bs.mTileX, bs.mTileY, bs.mLocalMatrix);
                return true;
            } else if (shader instanceof LinearGradient) {
                final LinearGradient ls = (LinearGradient) shader;
                nSetupLinearShader(mRenderer, ls.native_instance, ls.bounds, ls.colors,
                        ls.positions, ls.count, ls.tileMode, ls.mLocalMatrix);
                return true;
            } else if (shader instanceof RadialGradient) {
                // TODO: Implement
            } else if (shader instanceof SweepGradient) {
                // TODO: Implement
            }
        }
        return false;
    }

    private native void nSetupLinearShader(int renderer, int shader, int bounds,
            int colors, int positions, int count, int tileMode, int localMatrix);
    private native void nSetupBitmapShader(int renderer, int shader, int bitmap,
            int tileX, int tileY, int matrix);
    private native void nResetShader(int renderer);
}
