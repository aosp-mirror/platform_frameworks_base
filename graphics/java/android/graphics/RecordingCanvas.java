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

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.os.SystemProperties;
import android.util.Pools.SynchronizedPool;

import dalvik.annotation.optimization.CriticalNative;

/**
 * A Canvas implementation that records view system drawing operations for deferred rendering.
 * This is used in combination with RenderNode. This class keeps a list of all the Paint and
 * Bitmap objects that it draws, preventing the backing memory of Bitmaps from being released while
 * the RecordingCanvas is still holding a native reference to the memory.
 *
 * This is obtained by calling {@link RenderNode#beginRecording()} and is valid until the matching
 * {@link RenderNode#endRecording()} is called. It must not be retained beyond that as it is
 * internally reused.
 */
public final class RecordingCanvas extends BaseRecordingCanvas {
    // The recording canvas pool should be large enough to handle a deeply nested
    // view hierarchy because display lists are generated recursively.
    private static final int POOL_LIMIT = 25;

    /** @hide */
    private static int getPanelFrameSize() {
        final int DefaultSize = 100 * 1024 * 1024; // 100 MB;
        return Math.max(SystemProperties.getInt("ro.hwui.max_texture_allocation_size", DefaultSize),
                DefaultSize);
    }

    /** @hide */
    public static final int MAX_BITMAP_SIZE = getPanelFrameSize();

    private static final SynchronizedPool<RecordingCanvas> sPool =
            new SynchronizedPool<>(POOL_LIMIT);

    /**
     * TODO: Temporarily exposed for RenderNodeAnimator(Set)
     * @hide */
    public RenderNode mNode;
    private int mWidth;
    private int mHeight;

    /*package*/
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

    /*package*/
    void recycle() {
        mNode = null;
        sPool.release(this);
    }

    /*package*/
    void finishRecording(RenderNode node) {
        nFinishRecording(mNativeCanvasWrapper, node.mNativeRenderNode);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Constructors
    ///////////////////////////////////////////////////////////////////////////

    private RecordingCanvas(@NonNull RenderNode node, int width, int height) {
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
        nEnableZ(mNativeCanvasWrapper, true);
    }

    @Override
    public void disableZ() {
        nEnableZ(mNativeCanvasWrapper, false);
    }

    ///////////////////////////////////////////////////////////////////////////
    // WebView
    ///////////////////////////////////////////////////////////////////////////

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
     * @hide TODO: Make this a SystemApi for b/155905258
     */
    public void drawTextureLayer(@NonNull TextureLayer layer) {
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
     * Draws a ripple
     *
     * @param cx
     * @param cy
     * @param radius
     * @param paint
     * @param progress
     * @param shader
     *
     * @hide
     */
    public void drawRipple(CanvasProperty<Float> cx, CanvasProperty<Float> cy,
            CanvasProperty<Float> radius, CanvasProperty<Paint> paint,
            CanvasProperty<Float> progress, CanvasProperty<Float> turbulencePhase,
            @ColorInt int color, RuntimeShader shader) {
        nDrawRipple(mNativeCanvasWrapper, cx.getNativeContainer(), cy.getNativeContainer(),
                radius.getNativeContainer(), paint.getNativeContainer(),
                progress.getNativeContainer(), turbulencePhase.getNativeContainer(),
                color, shader.getNativeShaderBuilder());
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


    // ------------------ Critical JNI ------------------------

    @CriticalNative
    private static native long nCreateDisplayListCanvas(long node, int width, int height);
    @CriticalNative
    private static native void nResetDisplayListCanvas(long canvas, long node,
            int width, int height);
    private static native int nGetMaximumTextureWidth();
    private static native int nGetMaximumTextureHeight();
    @CriticalNative
    private static native void nEnableZ(long renderer, boolean enableZ);
    @CriticalNative
    private static native void nFinishRecording(long renderer, long renderNode);
    @CriticalNative
    private static native void nDrawRenderNode(long renderer, long renderNode);
    @CriticalNative
    private static native void nDrawTextureLayer(long renderer, long layer);
    @CriticalNative
    private static native void nDrawCircle(long renderer, long propCx,
            long propCy, long propRadius, long propPaint);
    @CriticalNative
    private static native void nDrawRipple(long renderer, long propCx, long propCy, long propRadius,
            long propPaint, long propProgress, long turbulencePhase, int color, long runtimeEffect);
    @CriticalNative
    private static native void nDrawRoundRect(long renderer, long propLeft, long propTop,
            long propRight, long propBottom, long propRx, long propRy, long propPaint);
    @CriticalNative
    private static native void nDrawWebViewFunctor(long canvas, int functor);
}
