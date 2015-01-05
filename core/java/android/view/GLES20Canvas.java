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
import android.graphics.Matrix;
import android.graphics.NinePatch;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.GraphicsOperations;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.TextUtils;

/**
 * An implementation of Canvas on top of OpenGL ES 2.0.
 */
class GLES20Canvas extends HardwareCanvas {
    private int mWidth;
    private int mHeight;

    private float[] mPoint;
    private float[] mLine;

    private Rect mClipBounds;
    private RectF mPathBounds;

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
        super(nCreateDisplayListRenderer());
    }

    private static native long nCreateDisplayListRenderer();

    public static void setProperty(String name, String value) {
        nSetProperty(name, value);
    }

    private static native void nSetProperty(String name, String value);

    ///////////////////////////////////////////////////////////////////////////
    // Canvas management
    ///////////////////////////////////////////////////////////////////////////

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

    @Override
    public void onPreDraw(Rect dirty) {
        if (dirty != null) {
            nPrepareDirty(mNativeCanvasWrapper, dirty.left, dirty.top, dirty.right, dirty.bottom);
        } else {
            nPrepare(mNativeCanvasWrapper);
        }
    }

    private static native void nPrepare(long renderer);
    private static native void nPrepareDirty(long renderer, int left, int top, int right, int bottom);

    @Override
    public void onPostDraw() {
        nFinish(mNativeCanvasWrapper);
    }

    private static native void nFinish(long renderer);

    ///////////////////////////////////////////////////////////////////////////
    // Functor
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void callDrawGLFunction2(long drawGLFunction) {
        nCallDrawGLFunction(mNativeCanvasWrapper, drawGLFunction);
    }

    private static native void nCallDrawGLFunction(long renderer, long drawGLFunction);

    ///////////////////////////////////////////////////////////////////////////
    // Display list
    ///////////////////////////////////////////////////////////////////////////

    protected static native long nFinishRecording(long renderer);

    @Override
    public void drawRenderNode(RenderNode renderNode, int flags) {
        nDrawRenderNode(mNativeCanvasWrapper, renderNode.getNativeDisplayList(), flags);
    }

    private static native void nDrawRenderNode(long renderer, long renderNode,
            int flags);

    ///////////////////////////////////////////////////////////////////////////
    // Hardware layer
    ///////////////////////////////////////////////////////////////////////////

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
        nDrawPatch(mNativeCanvasWrapper, bitmap.mNativeBitmap, patch.mNativeChunk,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint);
    }

    // TODO: move to Canvas.java
    @Override
    public void drawPatch(NinePatch patch, RectF dst, Paint paint) {
        Bitmap bitmap = patch.getBitmap();
        throwIfCannotDraw(bitmap);
        final long nativePaint = paint == null ? 0 : paint.getNativeInstance();
        nDrawPatch(mNativeCanvasWrapper, bitmap.mNativeBitmap, patch.mNativeChunk,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint);
    }

    private static native void nDrawPatch(long renderer, long bitmap, long chunk,
            float left, float top, float right, float bottom, long paint);

    @Override
    public void drawCircle(CanvasProperty<Float> cx, CanvasProperty<Float> cy,
            CanvasProperty<Float> radius, CanvasProperty<Paint> paint) {
        nDrawCircle(mNativeCanvasWrapper, cx.getNativeContainer(), cy.getNativeContainer(),
                radius.getNativeContainer(), paint.getNativeContainer());
    }

    private static native void nDrawCircle(long renderer, long propCx,
            long propCy, long propRadius, long propPaint);

    @Override
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

    @Override
    public void drawPicture(Picture picture) {
        picture.endRecording();
        // TODO: Implement rendering
    }
}
