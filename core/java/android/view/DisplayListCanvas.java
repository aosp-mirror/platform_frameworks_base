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
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Paint;
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

    private static final int MAX_BITMAP_SIZE = 100 * 1024 * 1024; // 100 MB

    private static final SynchronizedPool<DisplayListCanvas> sPool =
            new SynchronizedPool<DisplayListCanvas>(POOL_LIMIT);

    RenderNode mNode;
    private int mWidth;
    private int mHeight;

    static DisplayListCanvas obtain(@NonNull RenderNode node, int width, int height) {
        if (node == null) throw new IllegalArgumentException("node cannot be null");
        DisplayListCanvas canvas = sPool.acquire();
        if (canvas == null) {
            canvas = new DisplayListCanvas(width, height);
        } else {
            nResetDisplayListCanvas(canvas.mNativeCanvasWrapper, width, height);
        }
        canvas.mNode = node;
        canvas.mWidth = width;
        canvas.mHeight = height;
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

    private DisplayListCanvas(int width, int height) {
        super(nCreateDisplayListCanvas(width, height));
        mDensity = 0; // disable bitmap density scaling
    }

    private static native long nCreateDisplayListCanvas(int width, int height);
    private static native void nResetDisplayListCanvas(long canvas, int width, int height);

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

    ///////////////////////////////////////////////////////////////////////////
    // Setup
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void insertReorderBarrier() {
        nInsertReorderBarrier(mNativeCanvasWrapper, true);
    }

    @Override
    public void insertInorderBarrier() {
        nInsertReorderBarrier(mNativeCanvasWrapper, false);
    }

    private static native void nInsertReorderBarrier(long renderer, boolean enableReorder);

    ///////////////////////////////////////////////////////////////////////////
    // Functor
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Records the functor specified with the drawGLFunction function pointer. This is
     * functionality used by webview for calling into their renderer from our display lists.
     *
     * @param drawGLFunction A native function pointer
     */
    public void callDrawGLFunction2(long drawGLFunction) {
        nCallDrawGLFunction(mNativeCanvasWrapper, drawGLFunction, null);
    }

    /**
     * Records the functor specified with the drawGLFunction function pointer. This is
     * functionality used by webview for calling into their renderer from our display lists.
     *
     * @param drawGLFunction A native function pointer
     * @param releasedCallback Called when the display list is destroyed, and thus
     * the functor is no longer referenced by this canvas's display list.
     *
     * NOTE: The callback does *not* necessarily mean that there are no longer
     * any references to the functor, just that the reference from this specific
     * canvas's display list has been released.
     */
    public void drawGLFunctor2(long drawGLFunctor, @Nullable Runnable releasedCallback) {
        nCallDrawGLFunction(mNativeCanvasWrapper, drawGLFunctor, releasedCallback);
    }

    private static native void nCallDrawGLFunction(long renderer,
            long drawGLFunction, Runnable releasedCallback);

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
     */
    void drawHardwareLayer(HardwareLayer layer) {
        nDrawLayer(mNativeCanvasWrapper, layer.getLayerHandle());
    }

    private static native void nDrawLayer(long renderer, long layer);

    ///////////////////////////////////////////////////////////////////////////
    // Drawing
    ///////////////////////////////////////////////////////////////////////////

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

    @Override
    protected void throwIfCannotDraw(Bitmap bitmap) {
        super.throwIfCannotDraw(bitmap);
        int bitmapSize = bitmap.getByteCount();
        if (bitmapSize > MAX_BITMAP_SIZE) {
            throw new RuntimeException(
                    "Canvas: trying to draw too large(" + bitmapSize + "bytes) bitmap.");
        }
    }
}
