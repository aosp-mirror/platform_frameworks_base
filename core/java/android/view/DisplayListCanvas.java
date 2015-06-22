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

import android.annotation.NonNull;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.NinePatch;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Pools.SynchronizedPool;

/**
 * An implementation of a GL canvas that records drawing operations.
 * This is intended for use with a DisplayList. This class keeps a list of all the Paint and
 * Bitmap objects that it draws, preventing the backing memory of Bitmaps from being freed while
 * the DisplayList is still holding a native reference to the memory.
 *
 * @hide
 */
public class DisplayListCanvas extends Canvas {
    // The recording canvas pool should be large enough to handle a deeply nested
    // view hierarchy because display lists are generated recursively.
    private static final int POOL_LIMIT = 25;

    private static final SynchronizedPool<DisplayListCanvas> sPool =
            new SynchronizedPool<DisplayListCanvas>(POOL_LIMIT);

    RenderNode mNode;
    private int mWidth;
    private int mHeight;

    static DisplayListCanvas obtain(@NonNull RenderNode node) {
        if (node == null) throw new IllegalArgumentException("node cannot be null");
        DisplayListCanvas canvas = sPool.acquire();
        if (canvas == null) {
            canvas = new DisplayListCanvas();
        }
        canvas.mNode = node;
        return canvas;
    }

    void recycle() {
        mNode = null;
        sPool.release(this);
    }

    long finishRecording() {
        return nFinishRecording(mNativeCanvasWrapper);
    }

    @Override
    public boolean isRecordingFor(Object o) {
        return o == mNode;
    }

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

    private DisplayListCanvas() {
        super(nCreateDisplayListCanvas());
        mDensity = 0; // disable bitmap density scaling
    }

    private static native long nCreateDisplayListCanvas();

    ///////////////////////////////////////////////////////////////////////////
    // Canvas management
    ///////////////////////////////////////////////////////////////////////////


    @Override
    public void setDensity(int density) {
        // drop silently, since DisplayListCanvas doesn't perform density scaling
    }

    @Override
    public boolean isHardwareAccelerated() {
        return true;
    }

    @Override
    public void setBitmap(Bitmap bitmap) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOpaque() {
        return false;
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
        return mNativeCanvasWrapper;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Setup
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void setViewport(int width, int height) {
        mWidth = width;
        mHeight = height;

        nSetViewport(mNativeCanvasWrapper, width, height);
    }

    private static native void nSetViewport(long renderer,
            int width, int height);

    @Override
    public void setHighContrastText(boolean highContrastText) {
        nSetHighContrastText(mNativeCanvasWrapper, highContrastText);
    }

    private static native void nSetHighContrastText(long renderer, boolean highContrastText);

    @Override
    public void insertReorderBarrier() {
        nInsertReorderBarrier(mNativeCanvasWrapper, true);
    }

    @Override
    public void insertInorderBarrier() {
        nInsertReorderBarrier(mNativeCanvasWrapper, false);
    }

    private static native void nInsertReorderBarrier(long renderer, boolean enableReorder);

    /**
     * Invoked before any drawing operation is performed in this canvas.
     *
     * @param dirty The dirty rectangle to update, can be null.
     */
    public void onPreDraw(Rect dirty) {
        if (dirty != null) {
            nPrepareDirty(mNativeCanvasWrapper, dirty.left, dirty.top, dirty.right, dirty.bottom);
        } else {
            nPrepare(mNativeCanvasWrapper);
        }
    }

    private static native void nPrepare(long renderer);
    private static native void nPrepareDirty(long renderer, int left, int top, int right, int bottom);

    /**
     * Invoked after all drawing operation have been performed.
     */
    public void onPostDraw() {
        nFinish(mNativeCanvasWrapper);
    }

    private static native void nFinish(long renderer);

    ///////////////////////////////////////////////////////////////////////////
    // Functor
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Calls the function specified with the drawGLFunction function pointer. This is
     * functionality used by webkit for calling into their renderer from our display lists.
     * This function may return true if an invalidation is needed after the call.
     *
     * @param drawGLFunction A native function pointer
     */
    public void callDrawGLFunction2(long drawGLFunction) {
        nCallDrawGLFunction(mNativeCanvasWrapper, drawGLFunction);
    }

    private static native void nCallDrawGLFunction(long renderer, long drawGLFunction);

    ///////////////////////////////////////////////////////////////////////////
    // Display list
    ///////////////////////////////////////////////////////////////////////////

    protected static native long nFinishRecording(long renderer);

    /**
     * Draws the specified display list onto this canvas. The display list can only
     * be drawn if {@link android.view.RenderNode#isValid()} returns true.
     *
     * @param renderNode The RenderNode to draw.
     */
    public void drawRenderNode(RenderNode renderNode) {
        nDrawRenderNode(mNativeCanvasWrapper, renderNode.getNativeDisplayList());
    }

    private static native void nDrawRenderNode(long renderer, long renderNode);

    ///////////////////////////////////////////////////////////////////////////
    // Hardware layer
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Draws the specified layer onto this canvas.
     *
     * @param layer The layer to composite on this canvas
     * @param x The left coordinate of the layer
     * @param y The top coordinate of the layer
     * @param paint The paint used to draw the layer
     */
    void drawHardwareLayer(HardwareLayer layer, float x, float y, Paint paint) {
        layer.setLayerPaint(paint);
        nDrawLayer(mNativeCanvasWrapper, layer.getLayerHandle(), x, y);
    }

    private static native void nDrawLayer(long renderer, long layer, float x, float y);

    ///////////////////////////////////////////////////////////////////////////
    // Drawing
    ///////////////////////////////////////////////////////////////////////////

    // TODO: move to Canvas.java
    @Override
    public void drawPatch(NinePatch patch, Rect dst, Paint paint) {
        Bitmap bitmap = patch.getBitmap();
        throwIfCannotDraw(bitmap);
        final long nativePaint = paint == null ? 0 : paint.getNativeInstance();
        nDrawPatch(mNativeCanvasWrapper, bitmap, patch.mNativeChunk,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint);
    }

    // TODO: move to Canvas.java
    @Override
    public void drawPatch(NinePatch patch, RectF dst, Paint paint) {
        Bitmap bitmap = patch.getBitmap();
        throwIfCannotDraw(bitmap);
        final long nativePaint = paint == null ? 0 : paint.getNativeInstance();
        nDrawPatch(mNativeCanvasWrapper, bitmap, patch.mNativeChunk,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint);
    }

    private static native void nDrawPatch(long renderer, Bitmap bitmap, long chunk,
            float left, float top, float right, float bottom, long paint);

    public void drawCircle(CanvasProperty<Float> cx, CanvasProperty<Float> cy,
            CanvasProperty<Float> radius, CanvasProperty<Paint> paint) {
        nDrawCircle(mNativeCanvasWrapper, cx.getNativeContainer(), cy.getNativeContainer(),
                radius.getNativeContainer(), paint.getNativeContainer());
    }

    private static native void nDrawCircle(long renderer, long propCx,
            long propCy, long propRadius, long propPaint);

    public void drawRoundRect(CanvasProperty<Float> left, CanvasProperty<Float> top,
            CanvasProperty<Float> right, CanvasProperty<Float> bottom, CanvasProperty<Float> rx,
            CanvasProperty<Float> ry, CanvasProperty<Paint> paint) {
        nDrawRoundRect(mNativeCanvasWrapper, left.getNativeContainer(), top.getNativeContainer(),
                right.getNativeContainer(), bottom.getNativeContainer(),
                rx.getNativeContainer(), ry.getNativeContainer(),
                paint.getNativeContainer());
    }

    private static native void nDrawRoundRect(long renderer, long propLeft, long propTop,
            long propRight, long propBottom, long propRx, long propRy, long propPaint);

    // TODO: move this optimization to Canvas.java
    @Override
    public void drawPath(Path path, Paint paint) {
        if (path.isSimplePath) {
            if (path.rects != null) {
                nDrawRects(mNativeCanvasWrapper, path.rects.mNativeRegion, paint.getNativeInstance());
            }
        } else {
            super.drawPath(path, paint);
        }
    }

    private static native void nDrawRects(long renderer, long region, long paint);
}
