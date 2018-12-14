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

package android.graphics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Pools.SynchronizedPool;
import android.view.DisplayListCanvas;
import android.view.TextureLayer;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

/**
 * A Canvas implementation that records view system drawing operations for deferred rendering.
 * This is used in combination with RenderNode. This class keeps a list of all the Paint and
 * Bitmap objects that it draws, preventing the backing memory of Bitmaps from being released while
 * the RecordingCanvas is still holding a native reference to the memory.
 *
 * This is obtained by calling {@link RenderNode#startRecording()} and is valid until the matching
 * {@link RenderNode#endRecording()} is called. It must not be retained beyond that as it is
 * internally reused.
 */
public final class RecordingCanvas extends DisplayListCanvas {
    // The recording canvas pool should be large enough to handle a deeply nested
    // view hierarchy because display lists are generated recursively.
    private static final int POOL_LIMIT = 25;

    /** @hide */
    public static final int MAX_BITMAP_SIZE = 100 * 1024 * 1024; // 100 MB

    private static final SynchronizedPool<RecordingCanvas> sPool =
            new SynchronizedPool<>(POOL_LIMIT);

    /**
     * TODO: Temporarily exposed for RenderNodeAnimator(Set)
     * @hide */
    public RenderNode mNode;
    private int mWidth;
    private int mHeight;

    /** @hide */
    static RecordingCanvas obtain(@NonNull RenderNode node, int width, int height) {
        if (node == null) throw new IllegalArgumentException("node cannot be null");
        RecordingCanvas canvas = sPool.acquire();
        if (canvas == null) {
            canvas = new RecordingCanvas(node, width, height);
        } else {
            nResetDisplayListCanvas(canvas.mNativeCanvasWrapper, node.mNativeRenderNode,
                    width, height);
        }
        canvas.mNode = node;
        canvas.mWidth = width;
        canvas.mHeight = height;
        return canvas;
    }

    /** @hide */
    void recycle() {
        mNode = null;
        sPool.release(this);
    }

    /** @hide */
    long finishRecording() {
        return nFinishRecording(mNativeCanvasWrapper);
    }

    /** @hide */
    @Override
    public boolean isRecordingFor(Object o) {
        return o == mNode;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Constructors
    ///////////////////////////////////////////////////////////////////////////

    /** @hide */
    protected RecordingCanvas(@NonNull RenderNode node, int width, int height) {
        super(nCreateDisplayListCanvas(node.mNativeRenderNode, width, height));
        mDensity = 0; // disable bitmap density scaling
    }

    ///////////////////////////////////////////////////////////////////////////
    // Canvas management
    ///////////////////////////////////////////////////////////////////////////


    @Override
    public void setDensity(int density) {
        // drop silently, since RecordingCanvas doesn't perform density scaling
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

    ///////////////////////////////////////////////////////////////////////////
    // Setup
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void enableZ() {
        nInsertReorderBarrier(mNativeCanvasWrapper, true);
    }

    @Override
    public void disableZ() {
        nInsertReorderBarrier(mNativeCanvasWrapper, false);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Functor
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Records the functor specified with the drawGLFunction function pointer. This is
     * functionality used by webview for calling into their renderer from our display lists.
     *
     * @param drawGLFunction A native function pointer
     *
     * @hide
     */
    public void callDrawGLFunction2(long drawGLFunction) {
        nCallDrawGLFunction(mNativeCanvasWrapper, drawGLFunction, null);
    }

    /**
     * Records the functor specified with the drawGLFunction function pointer. This is
     * functionality used by webview for calling into their renderer from our display lists.
     *
     * @param drawGLFunctor A native function pointer
     * @param releasedCallback Called when the display list is destroyed, and thus
     * the functor is no longer referenced by this canvas's display list.
     *
     * NOTE: The callback does *not* necessarily mean that there are no longer
     * any references to the functor, just that the reference from this specific
     * canvas's display list has been released.
     *
     * @hide
     */
    public void drawGLFunctor2(long drawGLFunctor, @Nullable Runnable releasedCallback) {
        nCallDrawGLFunction(mNativeCanvasWrapper, drawGLFunctor, releasedCallback);
    }

    /**
     * Calls the provided functor that was created via WebViewFunctor_create()
     * @hide
     */
    public void drawWebViewFunctor(int functor) {
        nDrawWebViewFunctor(mNativeCanvasWrapper, functor);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Display list
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Draws the specified display list onto this canvas.
     *
     * @param renderNode The RenderNode to draw.
     */
    @Override
    public void drawRenderNode(@NonNull RenderNode renderNode) {
        nDrawRenderNode(mNativeCanvasWrapper, renderNode.mNativeRenderNode);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Hardware layer
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Draws the specified layer onto this canvas.
     *
     * @param layer The layer to composite on this canvas
     * @hide
     */
    public void drawTextureLayer(TextureLayer layer) {
        nDrawTextureLayer(mNativeCanvasWrapper, layer.getLayerHandle());
    }

    ///////////////////////////////////////////////////////////////////////////
    // Drawing
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Draws a circle
     *
     * @param cx
     * @param cy
     * @param radius
     * @param paint
     *
     * @hide
     */
    public void drawCircle(CanvasProperty<Float> cx, CanvasProperty<Float> cy,
            CanvasProperty<Float> radius, CanvasProperty<Paint> paint) {
        nDrawCircle(mNativeCanvasWrapper, cx.getNativeContainer(), cy.getNativeContainer(),
                radius.getNativeContainer(), paint.getNativeContainer());
    }

    /**
     * Draws a round rect
     *
     * @param left
     * @param top
     * @param right
     * @param bottom
     * @param rx
     * @param ry
     * @param paint
     *
     * @hide
     */
    public void drawRoundRect(CanvasProperty<Float> left, CanvasProperty<Float> top,
            CanvasProperty<Float> right, CanvasProperty<Float> bottom, CanvasProperty<Float> rx,
            CanvasProperty<Float> ry, CanvasProperty<Paint> paint) {
        nDrawRoundRect(mNativeCanvasWrapper, left.getNativeContainer(), top.getNativeContainer(),
                right.getNativeContainer(), bottom.getNativeContainer(),
                rx.getNativeContainer(), ry.getNativeContainer(),
                paint.getNativeContainer());
    }

    /** @hide */
    @Override
    protected void throwIfCannotDraw(Bitmap bitmap) {
        super.throwIfCannotDraw(bitmap);
        int bitmapSize = bitmap.getByteCount();
        if (bitmapSize > MAX_BITMAP_SIZE) {
            throw new RuntimeException(
                    "Canvas: trying to draw too large(" + bitmapSize + "bytes) bitmap.");
        }
    }


    // ------------------ Fast JNI ------------------------

    @FastNative
    private static native void nCallDrawGLFunction(long renderer,
            long drawGLFunction, Runnable releasedCallback);


    // ------------------ Critical JNI ------------------------

    @CriticalNative
    private static native long nCreateDisplayListCanvas(long node, int width, int height);
    @CriticalNative
    private static native void nResetDisplayListCanvas(long canvas, long node,
            int width, int height);
    @CriticalNative
    private static native int nGetMaximumTextureWidth();
    @CriticalNative
    private static native int nGetMaximumTextureHeight();
    @CriticalNative
    private static native void nInsertReorderBarrier(long renderer, boolean enableReorder);
    @CriticalNative
    private static native long nFinishRecording(long renderer);
    @CriticalNative
    private static native void nDrawRenderNode(long renderer, long renderNode);
    @CriticalNative
    private static native void nDrawTextureLayer(long renderer, long layer);
    @CriticalNative
    private static native void nDrawCircle(long renderer, long propCx,
            long propCy, long propRadius, long propPaint);
    @CriticalNative
    private static native void nDrawRoundRect(long renderer, long propLeft, long propTop,
            long propRight, long propBottom, long propRx, long propRy, long propPaint);
    @CriticalNative
    private static native void nDrawWebViewFunctor(long canvas, int functor);
}
