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
import android.graphics.CanvasProperty;
import android.graphics.DrawFilter;
import android.graphics.Matrix;
import android.graphics.NinePatch;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.TemporaryBuffer;
import android.text.GraphicsOperations;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.TextUtils;

/**
 * An implementation of Canvas on top of OpenGL ES 2.0.
 */
class GLES20Canvas extends HardwareCanvas {
    private final boolean mOpaque;
    protected long mRenderer;

    // The native renderer will be destroyed when this object dies.
    // DO NOT overwrite this reference once it is set.
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private CanvasFinalizer mFinalizer;

    private int mWidth;
    private int mHeight;

    private float[] mPoint;
    private float[] mLine;

    private Rect mClipBounds;
    private RectF mPathBounds;

    private DrawFilter mFilter;

    ///////////////////////////////////////////////////////////////////////////
    // JNI
    ///////////////////////////////////////////////////////////////////////////

    private static native boolean nIsAvailable();
    private static boolean sIsAvailable = nIsAvailable();

    static boolean isAvailable() {
        return sIsAvailable;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Constructors
    ///////////////////////////////////////////////////////////////////////////

    // TODO: Merge with GLES20RecordingCanvas
    protected GLES20Canvas() {
        mOpaque = false;
        mRenderer = nCreateDisplayListRenderer();
        setupFinalizer();
    }

    private void setupFinalizer() {
        if (mRenderer == 0) {
            throw new IllegalStateException("Could not create GLES20Canvas renderer");
        } else {
            mFinalizer = new CanvasFinalizer(mRenderer);
        }
    }

    private static native long nCreateDisplayListRenderer();
    private static native void nResetDisplayListRenderer(long renderer);
    private static native void nDestroyRenderer(long renderer);

    private static final class CanvasFinalizer {
        private final long mRenderer;

        public CanvasFinalizer(long renderer) {
            mRenderer = renderer;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                nDestroyRenderer(mRenderer);
            } finally {
                super.finalize();
            }
        }
    }

    public static void setProperty(String name, String value) {
        nSetProperty(name, value);
    }

    private static native void nSetProperty(String name, String value);

    ///////////////////////////////////////////////////////////////////////////
    // Canvas management
    ///////////////////////////////////////////////////////////////////////////

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

    @Override
    public int getMaximumBitmapWidth() {
        return nGetMaximumTextureWidth();
    }

    @Override
    public int getMaximumBitmapHeight() {
        return nGetMaximumTextureHeight();
    }

    private static native int nGetMaximumTextureWidth();
    private static native int nGetMaximumTextureHeight();

    /**
     * Returns the native OpenGLRenderer object.
     */
    long getRenderer() {
        return mRenderer;
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

    private static native void nSetViewport(long renderer,
            int width, int height);

    @Override
    public void setHighContrastText(boolean highContrastText) {
        nSetHighContrastText(mRenderer, highContrastText);
    }

    private static native void nSetHighContrastText(long renderer, boolean highContrastText);

    @Override
    public int onPreDraw(Rect dirty) {
        if (dirty != null) {
            return nPrepareDirty(mRenderer, dirty.left, dirty.top, dirty.right, dirty.bottom,
                    mOpaque);
        } else {
            return nPrepare(mRenderer, mOpaque);
        }
    }

    private static native int nPrepare(long renderer, boolean opaque);
    private static native int nPrepareDirty(long renderer, int left, int top, int right, int bottom,
            boolean opaque);

    @Override
    public void onPostDraw() {
        nFinish(mRenderer);
    }

    private static native void nFinish(long renderer);

    ///////////////////////////////////////////////////////////////////////////
    // Functor
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public int callDrawGLFunction(long drawGLFunction) {
        return nCallDrawGLFunction(mRenderer, drawGLFunction);
    }

    private static native int nCallDrawGLFunction(long renderer, long drawGLFunction);

    ///////////////////////////////////////////////////////////////////////////
    // Display list
    ///////////////////////////////////////////////////////////////////////////

    protected static native long nFinishRecording(long renderer);

    @Override
    public int drawRenderNode(RenderNode renderNode, Rect dirty, int flags) {
        return nDrawRenderNode(mRenderer, renderNode.getNativeDisplayList(), dirty, flags);
    }

    private static native int nDrawRenderNode(long renderer, long renderNode,
            Rect dirty, int flags);

    ///////////////////////////////////////////////////////////////////////////
    // Hardware layer
    ///////////////////////////////////////////////////////////////////////////

    void drawHardwareLayer(HardwareLayer layer, float x, float y, Paint paint) {
        layer.setLayerPaint(paint);
        nDrawLayer(mRenderer, layer.getLayer(), x, y);
    }

    private static native void nDrawLayer(long renderer, long layer, float x, float y);

    ///////////////////////////////////////////////////////////////////////////
    // Support
    ///////////////////////////////////////////////////////////////////////////

    private Rect getInternalClipBounds() {
        if (mClipBounds == null) mClipBounds = new Rect();
        return mClipBounds;
    }


    private RectF getPathBounds() {
        if (mPathBounds == null) mPathBounds = new RectF();
        return mPathBounds;
    }

    private float[] getPointStorage() {
        if (mPoint == null) mPoint = new float[2];
        return mPoint;
    }

    private float[] getLineStorage() {
        if (mLine == null) mLine = new float[4];
        return mLine;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Clipping
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public boolean clipPath(Path path) {
        return nClipPath(mRenderer, path.mNativePath, Region.Op.INTERSECT.nativeInt);
    }

    @Override
    public boolean clipPath(Path path, Region.Op op) {
        return nClipPath(mRenderer, path.mNativePath, op.nativeInt);
    }

    private static native boolean nClipPath(long renderer, long path, int op);

    @Override
    public boolean clipRect(float left, float top, float right, float bottom) {
        return nClipRect(mRenderer, left, top, right, bottom, Region.Op.INTERSECT.nativeInt);
    }

    private static native boolean nClipRect(long renderer, float left, float top,
            float right, float bottom, int op);

    @Override
    public boolean clipRect(float left, float top, float right, float bottom, Region.Op op) {
        return nClipRect(mRenderer, left, top, right, bottom, op.nativeInt);
    }

    @Override
    public boolean clipRect(int left, int top, int right, int bottom) {
        return nClipRect(mRenderer, left, top, right, bottom, Region.Op.INTERSECT.nativeInt);
    }

    private static native boolean nClipRect(long renderer, int left, int top,
            int right, int bottom, int op);

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
        return nClipRegion(mRenderer, region.mNativeRegion, Region.Op.INTERSECT.nativeInt);
    }

    @Override
    public boolean clipRegion(Region region, Region.Op op) {
        return nClipRegion(mRenderer, region.mNativeRegion, op.nativeInt);
    }

    private static native boolean nClipRegion(long renderer, long region, int op);

    @Override
    public boolean getClipBounds(Rect bounds) {
        return nGetClipBounds(mRenderer, bounds);
    }

    private static native boolean nGetClipBounds(long renderer, Rect bounds);

    @Override
    public boolean quickReject(float left, float top, float right, float bottom, EdgeType type) {
        return nQuickReject(mRenderer, left, top, right, bottom);
    }

    private static native boolean nQuickReject(long renderer, float left, float top,
            float right, float bottom);

    @Override
    public boolean quickReject(Path path, EdgeType type) {
        RectF pathBounds = getPathBounds();
        path.computeBounds(pathBounds, true);
        return nQuickReject(mRenderer, pathBounds.left, pathBounds.top,
                pathBounds.right, pathBounds.bottom);
    }

    @Override
    public boolean quickReject(RectF rect, EdgeType type) {
        return nQuickReject(mRenderer, rect.left, rect.top, rect.right, rect.bottom);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Transformations
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void translate(float dx, float dy) {
        if (dx != 0.0f || dy != 0.0f) nTranslate(mRenderer, dx, dy);
    }

    private static native void nTranslate(long renderer, float dx, float dy);

    @Override
    public void skew(float sx, float sy) {
        nSkew(mRenderer, sx, sy);
    }

    private static native void nSkew(long renderer, float sx, float sy);

    @Override
    public void rotate(float degrees) {
        nRotate(mRenderer, degrees);
    }

    private static native void nRotate(long renderer, float degrees);

    @Override
    public void scale(float sx, float sy) {
        nScale(mRenderer, sx, sy);
    }

    private static native void nScale(long renderer, float sx, float sy);

    @Override
    public void setMatrix(Matrix matrix) {
        nSetMatrix(mRenderer, matrix == null ? 0 : matrix.native_instance);
    }

    private static native void nSetMatrix(long renderer, long matrix);

    @SuppressWarnings("deprecation")
    @Override
    public void getMatrix(Matrix matrix) {
        nGetMatrix(mRenderer, matrix.native_instance);
    }

    private static native void nGetMatrix(long renderer, long matrix);

    @Override
    public void concat(Matrix matrix) {
        if (matrix != null) nConcatMatrix(mRenderer, matrix.native_instance);
    }

    private static native void nConcatMatrix(long renderer, long matrix);

    ///////////////////////////////////////////////////////////////////////////
    // State management
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public int save() {
        return nSave(mRenderer, Canvas.CLIP_SAVE_FLAG | Canvas.MATRIX_SAVE_FLAG);
    }

    @Override
    public int save(int saveFlags) {
        return nSave(mRenderer, saveFlags);
    }

    private static native int nSave(long renderer, int flags);

    @Override
    public int saveLayer(RectF bounds, Paint paint, int saveFlags) {
        if (bounds != null) {
            return saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, paint, saveFlags);
        }

        final long nativePaint = paint == null ? 0 : paint.mNativePaint;
        return nSaveLayer(mRenderer, nativePaint, saveFlags);
    }

    private static native int nSaveLayer(long renderer, long paint, int saveFlags);

    @Override
    public int saveLayer(float left, float top, float right, float bottom, Paint paint,
            int saveFlags) {
        if (left < right && top < bottom) {
            final long nativePaint = paint == null ? 0 : paint.mNativePaint;
            return nSaveLayer(mRenderer, left, top, right, bottom, nativePaint, saveFlags);
        }
        return save(saveFlags);
    }

    private static native int nSaveLayer(long renderer, float left, float top,
            float right, float bottom, long paint, int saveFlags);

    @Override
    public int saveLayerAlpha(RectF bounds, int alpha, int saveFlags) {
        if (bounds != null) {
            return saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    alpha, saveFlags);
        }
        return nSaveLayerAlpha(mRenderer, alpha, saveFlags);
    }

    private static native int nSaveLayerAlpha(long renderer, int alpha, int saveFlags);

    @Override
    public int saveLayerAlpha(float left, float top, float right, float bottom, int alpha,
            int saveFlags) {
        if (left < right && top < bottom) {
            return nSaveLayerAlpha(mRenderer, left, top, right, bottom, alpha, saveFlags);
        }
        return save(saveFlags);
    }

    private static native int nSaveLayerAlpha(long renderer, float left, float top, float right,
            float bottom, int alpha, int saveFlags);

    @Override
    public void restore() {
        nRestore(mRenderer);
    }

    private static native void nRestore(long renderer);

    @Override
    public void restoreToCount(int saveCount) {
        nRestoreToCount(mRenderer, saveCount);
    }

    private static native void nRestoreToCount(long renderer, int saveCount);

    @Override
    public int getSaveCount() {
        return nGetSaveCount(mRenderer);
    }

    private static native int nGetSaveCount(long renderer);

    ///////////////////////////////////////////////////////////////////////////
    // Filtering
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void setDrawFilter(DrawFilter filter) {
        mFilter = filter;
        if (filter == null) {
            nResetPaintFilter(mRenderer);
        } else if (filter instanceof PaintFlagsDrawFilter) {
            PaintFlagsDrawFilter flagsFilter = (PaintFlagsDrawFilter) filter;
            nSetupPaintFilter(mRenderer, flagsFilter.clearBits, flagsFilter.setBits);
        }
    }

    private static native void nResetPaintFilter(long renderer);
    private static native void nSetupPaintFilter(long renderer, int clearBits, int setBits);

    @Override
    public DrawFilter getDrawFilter() {
        return mFilter;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Drawing
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, boolean useCenter, Paint paint) {
        nDrawArc(mRenderer, left, top, right, bottom,
                startAngle, sweepAngle, useCenter, paint.mNativePaint);
    }

    private static native void nDrawArc(long renderer, float left, float top,
            float right, float bottom, float startAngle, float sweepAngle,
            boolean useCenter, long paint);

    @Override
    public void drawARGB(int a, int r, int g, int b) {
        drawColor((a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF));
    }

    @Override
    public void drawPatch(NinePatch patch, Rect dst, Paint paint) {
        Bitmap bitmap = patch.getBitmap();
        throwIfCannotDraw(bitmap);
        final long nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawPatch(mRenderer, bitmap.mNativeBitmap, bitmap.mBuffer, patch.mNativeChunk,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint);
    }

    @Override
    public void drawPatch(NinePatch patch, RectF dst, Paint paint) {
        Bitmap bitmap = patch.getBitmap();
        throwIfCannotDraw(bitmap);
        final long nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawPatch(mRenderer, bitmap.mNativeBitmap, bitmap.mBuffer, patch.mNativeChunk,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint);
    }

    private static native void nDrawPatch(long renderer, long bitmap, byte[] buffer, long chunk,
            float left, float top, float right, float bottom, long paint);

    @Override
    public void drawBitmap(Bitmap bitmap, float left, float top, Paint paint) {
        throwIfCannotDraw(bitmap);
        final long nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawBitmap(mRenderer, bitmap.mNativeBitmap, bitmap.mBuffer, left, top, nativePaint);
    }

    private static native void nDrawBitmap(long renderer, long bitmap, byte[] buffer,
            float left, float top, long paint);

    @Override
    public void drawBitmap(Bitmap bitmap, Matrix matrix, Paint paint) {
        throwIfCannotDraw(bitmap);
        final long nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawBitmap(mRenderer, bitmap.mNativeBitmap, bitmap.mBuffer,
                matrix.native_instance, nativePaint);
    }

    private static native void nDrawBitmap(long renderer, long bitmap, byte[] buffer,
            long matrix, long paint);

    @Override
    public void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) {
        throwIfCannotDraw(bitmap);
        final long nativePaint = paint == null ? 0 : paint.mNativePaint;

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

        nDrawBitmap(mRenderer, bitmap.mNativeBitmap, bitmap.mBuffer, left, top, right, bottom,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint);
    }

    @Override
    public void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) {
        throwIfCannotDraw(bitmap);
        final long nativePaint = paint == null ? 0 : paint.mNativePaint;

        float left, top, right, bottom;
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

        nDrawBitmap(mRenderer, bitmap.mNativeBitmap, bitmap.mBuffer, left, top, right, bottom,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint);
    }

    private static native void nDrawBitmap(long renderer, long bitmap, byte[] buffer,
            float srcLeft, float srcTop, float srcRight, float srcBottom,
            float left, float top, float right, float bottom, long paint);

    @Override
    public void drawBitmap(int[] colors, int offset, int stride, float x, float y,
            int width, int height, boolean hasAlpha, Paint paint) {
        if (width < 0) {
            throw new IllegalArgumentException("width must be >= 0");
        }

        if (height < 0) {
            throw new IllegalArgumentException("height must be >= 0");
        }

        if (Math.abs(stride) < width) {
            throw new IllegalArgumentException("abs(stride) must be >= width");
        }

        int lastScanline = offset + (height - 1) * stride;
        int length = colors.length;

        if (offset < 0 || (offset + width > length) || lastScanline < 0 ||
                (lastScanline + width > length)) {
            throw new ArrayIndexOutOfBoundsException();
        }

        final long nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawBitmap(mRenderer, colors, offset, stride, x, y,
                width, height, hasAlpha, nativePaint);
    }

    private static native void nDrawBitmap(long renderer, int[] colors, int offset, int stride,
            float x, float y, int width, int height, boolean hasAlpha, long nativePaint);

    @Override
    public void drawBitmap(int[] colors, int offset, int stride, int x, int y,
            int width, int height, boolean hasAlpha, Paint paint) {
        drawBitmap(colors, offset, stride, (float) x, (float) y, width, height, hasAlpha, paint);
    }

    @Override
    public void drawBitmapMesh(Bitmap bitmap, int meshWidth, int meshHeight, float[] verts,
            int vertOffset, int[] colors, int colorOffset, Paint paint) {
        throwIfCannotDraw(bitmap);
        if (meshWidth < 0 || meshHeight < 0 || vertOffset < 0 || colorOffset < 0) {
            throw new ArrayIndexOutOfBoundsException();
        }

        if (meshWidth == 0 || meshHeight == 0) {
            return;
        }

        final int count = (meshWidth + 1) * (meshHeight + 1);
        checkRange(verts.length, vertOffset, count * 2);

        if (colors != null) {
            checkRange(colors.length, colorOffset, count);
        }

        final long nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawBitmapMesh(mRenderer, bitmap.mNativeBitmap, bitmap.mBuffer, meshWidth, meshHeight,
                verts, vertOffset, colors, colorOffset, nativePaint);
    }

    private static native void nDrawBitmapMesh(long renderer, long bitmap, byte[] buffer,
            int meshWidth, int meshHeight, float[] verts, int vertOffset,
            int[] colors, int colorOffset, long paint);

    @Override
    public void drawCircle(float cx, float cy, float radius, Paint paint) {
        nDrawCircle(mRenderer, cx, cy, radius, paint.mNativePaint);
    }

    private static native void nDrawCircle(long renderer, float cx, float cy,
            float radius, long paint);

    @Override
    public void drawCircle(CanvasProperty<Float> cx, CanvasProperty<Float> cy,
            CanvasProperty<Float> radius, CanvasProperty<Paint> paint) {
        nDrawCircle(mRenderer, cx.getNativeContainer(), cy.getNativeContainer(),
                radius.getNativeContainer(), paint.getNativeContainer());
    }

    private static native void nDrawCircle(long renderer, long propCx,
            long propCy, long propRadius, long propPaint);

    @Override
    public void drawColor(int color) {
        drawColor(color, PorterDuff.Mode.SRC_OVER);
    }

    @Override
    public void drawColor(int color, PorterDuff.Mode mode) {
        nDrawColor(mRenderer, color, mode.nativeInt);
    }

    private static native void nDrawColor(long renderer, int color, int mode);

    @Override
    public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {
        float[] line = getLineStorage();
        line[0] = startX;
        line[1] = startY;
        line[2] = stopX;
        line[3] = stopY;
        drawLines(line, 0, 4, paint);
    }

    @Override
    public void drawLines(float[] pts, int offset, int count, Paint paint) {
        if (count < 4) return;

        if ((offset | count) < 0 || offset + count > pts.length) {
            throw new IllegalArgumentException("The lines array must contain 4 elements per line.");
        }
        nDrawLines(mRenderer, pts, offset, count, paint.mNativePaint);
    }

    private static native void nDrawLines(long renderer, float[] points,
            int offset, int count, long paint);

    @Override
    public void drawLines(float[] pts, Paint paint) {
        drawLines(pts, 0, pts.length, paint);
    }

    @Override
    public void drawOval(float left, float top, float right, float bottom, Paint paint) {
        nDrawOval(mRenderer, left, top, right, bottom, paint.mNativePaint);
    }

    private static native void nDrawOval(long renderer, float left, float top,
            float right, float bottom, long paint);

    @Override
    public void drawPaint(Paint paint) {
        final Rect r = getInternalClipBounds();
        nGetClipBounds(mRenderer, r);
        drawRect(r.left, r.top, r.right, r.bottom, paint);
    }

    @Override
    public void drawPath(Path path, Paint paint) {
        if (path.isSimplePath) {
            if (path.rects != null) {
                nDrawRects(mRenderer, path.rects.mNativeRegion, paint.mNativePaint);
            }
        } else {
            nDrawPath(mRenderer, path.mNativePath, paint.mNativePaint);
        }
    }

    private static native void nDrawPath(long renderer, long path, long paint);
    private static native void nDrawRects(long renderer, long region, long paint);

    @Override
    public void drawPicture(Picture picture) {
        picture.endRecording();
        // TODO: Implement rendering
    }

    @Override
    public void drawPicture(Picture picture, Rect dst) {
        save();
        translate(dst.left, dst.top);
        if (picture.getWidth() > 0 && picture.getHeight() > 0) {
            scale(dst.width() / picture.getWidth(), dst.height() / picture.getHeight());
        }
        drawPicture(picture);
        restore();
    }

    @Override
    public void drawPicture(Picture picture, RectF dst) {
        save();
        translate(dst.left, dst.top);
        if (picture.getWidth() > 0 && picture.getHeight() > 0) {
            scale(dst.width() / picture.getWidth(), dst.height() / picture.getHeight());
        }
        drawPicture(picture);
        restore();
    }

    @Override
    public void drawPoint(float x, float y, Paint paint) {
        float[] point = getPointStorage();
        point[0] = x;
        point[1] = y;
        drawPoints(point, 0, 2, paint);
    }

    @Override
    public void drawPoints(float[] pts, Paint paint) {
        drawPoints(pts, 0, pts.length, paint);
    }

    @Override
    public void drawPoints(float[] pts, int offset, int count, Paint paint) {
        if (count < 2) return;

        nDrawPoints(mRenderer, pts, offset, count, paint.mNativePaint);
    }

    private static native void nDrawPoints(long renderer, float[] points,
            int offset, int count, long paint);

    // Note: drawPosText just uses implementation in Canvas

    @Override
    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
        if (left == right || top == bottom) return;
        nDrawRect(mRenderer, left, top, right, bottom, paint.mNativePaint);
    }

    private static native void nDrawRect(long renderer, float left, float top,
            float right, float bottom, long paint);

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
    public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry,
            Paint paint) {
        nDrawRoundRect(mRenderer, left, top, right, bottom, rx, ry, paint.mNativePaint);
    }

    private static native void nDrawRoundRect(long renderer, float left, float top,
            float right, float bottom, float rx, float y, long paint);

    @Override
    public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
        if ((index | count | (index + count) | (text.length - index - count)) < 0) {
            throw new IndexOutOfBoundsException();
        }

        nDrawText(mRenderer, text, index, count, x, y,
                paint.mBidiFlags, paint.mNativePaint, paint.mNativeTypeface);
    }

    private static native void nDrawText(long renderer, char[] text, int index, int count,
            float x, float y, int bidiFlags, long paint, long typeface);

    @Override
    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
        if (text instanceof String || text instanceof SpannedString ||
                text instanceof SpannableString) {
            nDrawText(mRenderer, text.toString(), start, end, x, y, paint.mBidiFlags,
                    paint.mNativePaint, paint.mNativeTypeface);
        } else if (text instanceof GraphicsOperations) {
            ((GraphicsOperations) text).drawText(this, start, end, x, y, paint);
        } else {
            char[] buf = TemporaryBuffer.obtain(end - start);
            TextUtils.getChars(text, start, end, buf, 0);
            nDrawText(mRenderer, buf, 0, end - start, x, y,
                    paint.mBidiFlags, paint.mNativePaint, paint.mNativeTypeface);
            TemporaryBuffer.recycle(buf);
        }
    }

    @Override
    public void drawText(String text, int start, int end, float x, float y, Paint paint) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }

        nDrawText(mRenderer, text, start, end, x, y,
                paint.mBidiFlags, paint.mNativePaint, paint.mNativeTypeface);
    }

    private static native void nDrawText(long renderer, String text, int start, int end,
            float x, float y, int bidiFlags, long paint, long typeface);

    @Override
    public void drawText(String text, float x, float y, Paint paint) {
        nDrawText(mRenderer, text, 0, text.length(), x, y,
                paint.mBidiFlags, paint.mNativePaint, paint.mNativeTypeface);
    }

    @Override
    public void drawTextOnPath(char[] text, int index, int count, Path path, float hOffset,
            float vOffset, Paint paint) {
        if (index < 0 || index + count > text.length) {
            throw new ArrayIndexOutOfBoundsException();
        }

        nDrawTextOnPath(mRenderer, text, index, count, path.mNativePath, hOffset, vOffset,
                paint.mBidiFlags, paint.mNativePaint, paint.mNativeTypeface);
    }

    private static native void nDrawTextOnPath(long renderer, char[] text, int index, int count,
            long path, float hOffset, float vOffset, int bidiFlags, long nativePaint,
            long typeface);

    @Override
    public void drawTextOnPath(String text, Path path, float hOffset, float vOffset, Paint paint) {
        if (text.length() == 0) return;

        nDrawTextOnPath(mRenderer, text, 0, text.length(), path.mNativePath, hOffset, vOffset,
                paint.mBidiFlags, paint.mNativePaint, paint.mNativeTypeface);
    }

    private static native void nDrawTextOnPath(long renderer, String text, int start, int end,
            long path, float hOffset, float vOffset, int bidiFlags, long nativePaint,
            long typeface);

    @Override
    public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount,
            float x, float y, boolean isRtl, Paint paint) {
        if ((index | count | text.length - index - count) < 0) {
            throw new IndexOutOfBoundsException();
        }

        nDrawTextRun(mRenderer, text, index, count, contextIndex, contextCount, x, y, isRtl,
                paint.mNativePaint, paint.mNativeTypeface);
    }

    private static native void nDrawTextRun(long renderer, char[] text, int index, int count,
            int contextIndex, int contextCount, float x, float y, boolean isRtl, long nativePaint, long nativeTypeface);

    @Override
    public void drawTextRun(CharSequence text, int start, int end, int contextStart, int contextEnd,
            float x, float y, boolean isRtl, Paint paint) {
        if ((start | end | end - start | text.length() - end) < 0) {
            throw new IndexOutOfBoundsException();
        }

        if (text instanceof String || text instanceof SpannedString ||
                text instanceof SpannableString) {
            nDrawTextRun(mRenderer, text.toString(), start, end, contextStart,
                    contextEnd, x, y, isRtl, paint.mNativePaint, paint.mNativeTypeface);
        } else if (text instanceof GraphicsOperations) {
            ((GraphicsOperations) text).drawTextRun(this, start, end,
                    contextStart, contextEnd, x, y, isRtl, paint);
        } else {
            int contextLen = contextEnd - contextStart;
            int len = end - start;
            char[] buf = TemporaryBuffer.obtain(contextLen);
            TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
            nDrawTextRun(mRenderer, buf, start - contextStart, len, 0, contextLen,
                    x, y, isRtl, paint.mNativePaint, paint.mNativeTypeface);
            TemporaryBuffer.recycle(buf);
        }
    }

    private static native void nDrawTextRun(long renderer, String text, int start, int end,
            int contextStart, int contextEnd, float x, float y, boolean isRtl, long nativePaint, long nativeTypeface);

    @Override
    public void drawVertices(VertexMode mode, int vertexCount, float[] verts, int vertOffset,
            float[] texs, int texOffset, int[] colors, int colorOffset, short[] indices,
            int indexOffset, int indexCount, Paint paint) {
        // TODO: Implement
    }
}
