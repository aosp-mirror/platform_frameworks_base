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
import android.graphics.ColorFilter;
import android.graphics.DrawFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.TemporaryBuffer;
import android.text.GraphicsOperations;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.TextUtils;

/**
 * An implementation of Canvas on top of OpenGL ES 2.0.
 */
class GLES20Canvas extends HardwareCanvas {
    // Must match modifiers used in the JNI layer
    private static final int MODIFIER_NONE = 0;
    private static final int MODIFIER_SHADOW = 1;
    private static final int MODIFIER_SHADER = 2;
    private static final int MODIFIER_COLOR_FILTER = 4;

    private final boolean mOpaque;
    private int mRenderer;

    // The native renderer will be destroyed when this object dies.
    // DO NOT overwrite this reference once it is set.
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private CanvasFinalizer mFinalizer;

    private int mWidth;
    private int mHeight;
    
    private final float[] mPoint = new float[2];
    private final float[] mLine = new float[4];
    
    private final Rect mClipBounds = new Rect();
    private final RectF mPathBounds = new RectF();

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

    /**
     * Creates a canvas to render directly on screen.
     */
    GLES20Canvas(boolean translucent) {
        this(false, translucent);
    }

    /**
     * Creates a canvas to render into an FBO.
     */
    GLES20Canvas(int layer, boolean translucent) {
        mOpaque = !translucent;
        mRenderer = nCreateLayerRenderer(layer);
        setupFinalizer();
    }
    
    protected GLES20Canvas(boolean record, boolean translucent) {
        mOpaque = !translucent;

        if (record) {
            mRenderer = nCreateDisplayListRenderer();
        } else {
            mRenderer = nCreateRenderer();
        }

        setupFinalizer();
    }

    private void setupFinalizer() {
        if (mRenderer == 0) {
            throw new IllegalStateException("Could not create GLES20Canvas renderer");
        } else {
            mFinalizer = new CanvasFinalizer(mRenderer);
        }
    }

    protected void resetDisplayListRenderer() {
        nResetDisplayListRenderer(mRenderer);
    }

    private static native int nCreateRenderer();
    private static native int nCreateLayerRenderer(int layer);
    private static native int nCreateDisplayListRenderer();
    private static native void nResetDisplayListRenderer(int renderer);
    private static native void nDestroyRenderer(int renderer);

    private static final class CanvasFinalizer {
        private final int mRenderer;

        public CanvasFinalizer(int renderer) {
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

    ///////////////////////////////////////////////////////////////////////////
    // Hardware layers
    ///////////////////////////////////////////////////////////////////////////
    
    static native int nCreateTextureLayer(boolean opaque, int[] layerInfo);
    static native int nCreateLayer(int width, int height, boolean isOpaque, int[] layerInfo);
    static native void nResizeLayer(int layerId, int width, int height, int[] layerInfo);
    static native void nUpdateTextureLayer(int layerId, int width, int height, boolean opaque,
            SurfaceTexture surface);
    static native void nSetTextureLayerTransform(int layerId, int matrix);
    static native void nDestroyLayer(int layerId);
    static native void nDestroyLayerDeferred(int layerId);
    static native void nFlushLayer(int layerId);
    static native boolean nCopyLayer(int layerId, int bitmap);

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

    ///////////////////////////////////////////////////////////////////////////
    // Setup
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void setViewport(int width, int height) {
        mWidth = width;
        mHeight = height;

        nSetViewport(mRenderer, width, height);
    }
    
    private static native void nSetViewport(int renderer, int width, int height);

    /**
     * Preserves the back buffer of the current surface after a buffer swap.
     * Calling this method sets the EGL_SWAP_BEHAVIOR attribute of the current
     * surface to EGL_BUFFER_PRESERVED. Calling this method requires an EGL
     * config that supports EGL_SWAP_BEHAVIOR_PRESERVED_BIT.
     * 
     * @return True if the swap behavior was successfully changed,
     *         false otherwise.
     * 
     * @hide
     */
    public static boolean preserveBackBuffer() {
        return nPreserveBackBuffer();
    }

    private static native boolean nPreserveBackBuffer();    

    /**
     * Indicates whether the current surface preserves its back buffer
     * after a buffer swap.
     * 
     * @return True, if the surface's EGL_SWAP_BEHAVIOR is EGL_BUFFER_PRESERVED,
     *         false otherwise
     *         
     * @hide
     */
    public static boolean isBackBufferPreserved() {
        return nIsBackBufferPreserved();
    }

    private static native boolean nIsBackBufferPreserved();

    /**
     * Disables v-sync. For performance testing only.
     * 
     * @hide
     */
    public static void disableVsync() {
        nDisableVsync();
    }

    private static native void nDisableVsync();

    @Override
    public void onPreDraw(Rect dirty) {
        if (dirty != null) {
            nPrepareDirty(mRenderer, dirty.left, dirty.top, dirty.right, dirty.bottom, mOpaque);
        } else {
            nPrepare(mRenderer, mOpaque);
        }
    }

    private static native void nPrepare(int renderer, boolean opaque);
    private static native void nPrepareDirty(int renderer, int left, int top, int right, int bottom,
            boolean opaque);

    @Override
    public void onPostDraw() {
        nFinish(mRenderer);
    }

    private static native void nFinish(int renderer);

    @Override
    public boolean callDrawGLFunction(int drawGLFunction) {
        return nCallDrawGLFunction(mRenderer, drawGLFunction);
    }

    private static native boolean nCallDrawGLFunction(int renderer, int drawGLFunction);


    ///////////////////////////////////////////////////////////////////////////
    // Memory
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Must match Caches::FlushMode values
     * 
     * @see #flushCaches(int) 
     */
    public static final int FLUSH_CACHES_LAYERS = 0;
    
    /**
     * Must match Caches::FlushMode values
     * 
     * @see #flushCaches(int) 
     */
    public static final int FLUSH_CACHES_MODERATE = 1;

    /**
     * Must match Caches::FlushMode values
     * 
     * @see #flushCaches(int) 
     */
    public static final int FLUSH_CACHES_FULL = 2;

    /**
     * Flush caches to reclaim as much memory as possible. The amount of memory
     * to reclaim is indicate by the level parameter.
     * 
     * The level can be one of {@link #FLUSH_CACHES_MODERATE} or
     * {@link #FLUSH_CACHES_FULL}.
     * 
     * @param level Hint about the amount of memory to reclaim
     * 
     * @hide
     */
    public static void flushCaches(int level) {
        nFlushCaches(level);
    }

    private static native void nFlushCaches(int level);

    /**
     * Release all resources associated with the underlying caches. This should
     * only be called after a full flushCaches().
     * 
     * @hide
     */
    public static void terminateCaches() {
        nTerminateCaches();
    }

    private static native void nTerminateCaches();

    /**
     * @hide
     */
    public static void initCaches() {
        nInitCaches();
    }

    private static native void nInitCaches();
    
    ///////////////////////////////////////////////////////////////////////////
    // Display list
    ///////////////////////////////////////////////////////////////////////////

    int getDisplayList(int displayList) {
        return nGetDisplayList(mRenderer, displayList);
    }

    private static native int nGetDisplayList(int renderer, int displayList);
    
    static void destroyDisplayList(int displayList) {
        nDestroyDisplayList(displayList);
    }

    private static native void nDestroyDisplayList(int displayList);

    static int getDisplayListSize(int displayList) {
        return nGetDisplayListSize(displayList);
    }

    private static native int nGetDisplayListSize(int displayList);

    @Override
    public boolean drawDisplayList(DisplayList displayList, int width, int height, Rect dirty) {
        return nDrawDisplayList(mRenderer,
                ((GLES20DisplayList) displayList).getNativeDisplayList(), width, height, dirty);
    }

    private static native boolean nDrawDisplayList(int renderer, int displayList,
            int width, int height, Rect dirty);

    @Override
    void outputDisplayList(DisplayList displayList) {
        nOutputDisplayList(mRenderer, ((GLES20DisplayList) displayList).getNativeDisplayList());
    }

    private static native void nOutputDisplayList(int renderer, int displayList);

    ///////////////////////////////////////////////////////////////////////////
    // Hardware layer
    ///////////////////////////////////////////////////////////////////////////
    
    void drawHardwareLayer(HardwareLayer layer, float x, float y, Paint paint) {
        final GLES20Layer glLayer = (GLES20Layer) layer;
        int modifier = paint != null ? setupColorFilter(paint) : MODIFIER_NONE;
        final int nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawLayer(mRenderer, glLayer.getLayer(), x, y, nativePaint);
        if (modifier != MODIFIER_NONE) nResetModifiers(mRenderer, modifier);
    }

    private static native void nDrawLayer(int renderer, int layer, float x, float y, int paint);

    void interrupt() {
        nInterrupt(mRenderer);
    }
    
    void resume() {
        nResume(mRenderer);
    }

    private static native void nInterrupt(int renderer);
    private static native void nResume(int renderer);

    ///////////////////////////////////////////////////////////////////////////
    // Clipping
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public boolean clipPath(Path path) {
        // TODO: Implement
        path.computeBounds(mPathBounds, true);
        return nClipRect(mRenderer, mPathBounds.left, mPathBounds.top,
                mPathBounds.right, mPathBounds.bottom, Region.Op.INTERSECT.nativeInt);
    }

    @Override
    public boolean clipPath(Path path, Region.Op op) {
        // TODO: Implement
        path.computeBounds(mPathBounds, true);
        return nClipRect(mRenderer, mPathBounds.left, mPathBounds.top,
                mPathBounds.right, mPathBounds.bottom, op.nativeInt);
    }

    @Override
    public boolean clipRect(float left, float top, float right, float bottom) {
        return nClipRect(mRenderer, left, top, right, bottom, Region.Op.INTERSECT.nativeInt);
    }
    
    private static native boolean nClipRect(int renderer, float left, float top,
            float right, float bottom, int op);

    @Override
    public boolean clipRect(float left, float top, float right, float bottom, Region.Op op) {
        return nClipRect(mRenderer, left, top, right, bottom, op.nativeInt);
    }

    @Override
    public boolean clipRect(int left, int top, int right, int bottom) {
        return nClipRect(mRenderer, left, top, right, bottom, Region.Op.INTERSECT.nativeInt);
    }
    
    private static native boolean nClipRect(int renderer, int left, int top, int right, int bottom,
            int op);

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
        // TODO: Implement
        region.getBounds(mClipBounds);
        return nClipRect(mRenderer, mClipBounds.left, mClipBounds.top,
                mClipBounds.right, mClipBounds.bottom, Region.Op.INTERSECT.nativeInt);
    }

    @Override
    public boolean clipRegion(Region region, Region.Op op) {
        // TODO: Implement
        region.getBounds(mClipBounds);
        return nClipRect(mRenderer, mClipBounds.left, mClipBounds.top,
                mClipBounds.right, mClipBounds.bottom, op.nativeInt);
    }

    @Override
    public boolean getClipBounds(Rect bounds) {
        return nGetClipBounds(mRenderer, bounds);
    }

    private static native boolean nGetClipBounds(int renderer, Rect bounds);

    @Override
    public boolean quickReject(float left, float top, float right, float bottom, EdgeType type) {
        return nQuickReject(mRenderer, left, top, right, bottom, type.nativeInt);
    }
    
    private static native boolean nQuickReject(int renderer, float left, float top,
            float right, float bottom, int edge);

    @Override
    public boolean quickReject(Path path, EdgeType type) {
        path.computeBounds(mPathBounds, true);
        return nQuickReject(mRenderer, mPathBounds.left, mPathBounds.top,
                mPathBounds.right, mPathBounds.bottom, type.nativeInt);
    }

    @Override
    public boolean quickReject(RectF rect, EdgeType type) {
        return nQuickReject(mRenderer, rect.left, rect.top, rect.right, rect.bottom, type.nativeInt);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Transformations
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void translate(float dx, float dy) {
        if (dx != 0.0f || dy != 0.0f) nTranslate(mRenderer, dx, dy);
    }
    
    private static native void nTranslate(int renderer, float dx, float dy);

    @Override
    public void skew(float sx, float sy) {
        nSkew(mRenderer, sx, sy);
    }

    private static native void nSkew(int renderer, float sx, float sy);

    @Override
    public void rotate(float degrees) {
        nRotate(mRenderer, degrees);
    }
    
    private static native void nRotate(int renderer, float degrees);

    @Override
    public void scale(float sx, float sy) {
        nScale(mRenderer, sx, sy);
    }
    
    private static native void nScale(int renderer, float sx, float sy);

    @Override
    public void setMatrix(Matrix matrix) {
        nSetMatrix(mRenderer, matrix == null ? 0 : matrix.native_instance);
    }
    
    private static native void nSetMatrix(int renderer, int matrix);

    @Override
    public void getMatrix(Matrix matrix) {
        nGetMatrix(mRenderer, matrix.native_instance);
    }
    
    private static native void nGetMatrix(int renderer, int matrix);

    @Override
    public void concat(Matrix matrix) {
        nConcatMatrix(mRenderer, matrix.native_instance);
    }
    
    private static native void nConcatMatrix(int renderer, int matrix);
    
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

    private static native int nSave(int renderer, int flags);
    
    @Override
    public int saveLayer(RectF bounds, Paint paint, int saveFlags) {
        if (bounds != null) {
            return saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, paint, saveFlags);
        }

        int modifier = paint != null ? setupColorFilter(paint) : MODIFIER_NONE;
        final int nativePaint = paint == null ? 0 : paint.mNativePaint;
        int count = nSaveLayer(mRenderer, nativePaint, saveFlags);
        if (modifier != MODIFIER_NONE) nResetModifiers(mRenderer, modifier);
        return count;
    }

    private static native int nSaveLayer(int renderer, int paint, int saveFlags);    

    @Override
    public int saveLayer(float left, float top, float right, float bottom, Paint paint,
            int saveFlags) {
        if (left < right && top < bottom) {
            int modifier = paint != null ? setupColorFilter(paint) : MODIFIER_NONE;
            final int nativePaint = paint == null ? 0 : paint.mNativePaint;
            int count = nSaveLayer(mRenderer, left, top, right, bottom, nativePaint, saveFlags);
            if (modifier != MODIFIER_NONE) nResetModifiers(mRenderer, modifier);
            return count;
        }
        return save(saveFlags);
    }

    private static native int nSaveLayer(int renderer, float left, float top,
            float right, float bottom, int paint, int saveFlags);

    @Override
    public int saveLayerAlpha(RectF bounds, int alpha, int saveFlags) {
        if (bounds != null) {
            return saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    alpha, saveFlags);
        }
        return nSaveLayerAlpha(mRenderer, alpha, saveFlags);
    }

    private static native int nSaveLayerAlpha(int renderer, int alpha, int saveFlags);    

    @Override
    public int saveLayerAlpha(float left, float top, float right, float bottom, int alpha,
            int saveFlags) {
        if (left < right && top < bottom) {
            return nSaveLayerAlpha(mRenderer, left, top, right, bottom, alpha, saveFlags);
        }
        return save(saveFlags);
    }

    private static native int nSaveLayerAlpha(int renderer, float left, float top, float right,
            float bottom, int alpha, int saveFlags);
    
    @Override
    public void restore() {
        nRestore(mRenderer);
    }
    
    private static native void nRestore(int renderer);

    @Override
    public void restoreToCount(int saveCount) {
        nRestoreToCount(mRenderer, saveCount);
    }

    private static native void nRestoreToCount(int renderer, int saveCount);
    
    @Override
    public int getSaveCount() {
        return nGetSaveCount(mRenderer);
    }
    
    private static native int nGetSaveCount(int renderer);

    ///////////////////////////////////////////////////////////////////////////
    // Filtering
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void setDrawFilter(DrawFilter filter) {
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
        int modifiers = setupModifiers(paint);
        nDrawArc(mRenderer, oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle,
                useCenter, paint.mNativePaint);
        if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
    }

    private static native void nDrawArc(int renderer, float left, float top,
            float right, float bottom, float startAngle, float sweepAngle,
            boolean useCenter, int paint);

    @Override
    public void drawARGB(int a, int r, int g, int b) {
        drawColor((a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF));
    }

    @Override
    public void drawPatch(Bitmap bitmap, byte[] chunks, RectF dst, Paint paint) {
        // Shaders are ignored when drawing patches
        int modifier = paint != null ? setupColorFilter(paint) : MODIFIER_NONE;
        final int nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawPatch(mRenderer, bitmap.mNativeBitmap, bitmap.mBuffer, chunks,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint);
        if (modifier != MODIFIER_NONE) nResetModifiers(mRenderer, modifier);
    }

    private static native void nDrawPatch(int renderer, int bitmap, byte[] buffer, byte[] chunks,
            float left, float top, float right, float bottom, int paint);

    @Override
    public void drawBitmap(Bitmap bitmap, float left, float top, Paint paint) {
        // Shaders are ignored when drawing bitmaps
        int modifiers = paint != null ? setupModifiers(bitmap, paint) : MODIFIER_NONE;
        final int nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawBitmap(mRenderer, bitmap.mNativeBitmap, bitmap.mBuffer, left, top, nativePaint);
        if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
    }

    private static native void nDrawBitmap(
            int renderer, int bitmap, byte[] buffer, float left, float top, int paint);

    @Override
    public void drawBitmap(Bitmap bitmap, Matrix matrix, Paint paint) {
        // Shaders are ignored when drawing bitmaps
        int modifiers = paint != null ? setupModifiers(bitmap, paint) : MODIFIER_NONE;
        final int nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawBitmap(mRenderer, bitmap.mNativeBitmap, bitmap.mBuffer,
                matrix.native_instance, nativePaint);
        if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
    }

    private static native void nDrawBitmap(int renderer, int bitmap, byte[] buff,
            int matrix, int paint);

    @Override
    public void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) {
        // Shaders are ignored when drawing bitmaps
        int modifiers = paint != null ? setupModifiers(bitmap, paint) : MODIFIER_NONE;
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

        nDrawBitmap(mRenderer, bitmap.mNativeBitmap, bitmap.mBuffer, left, top, right, bottom,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint);
        if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
    }

    @Override
    public void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) {
        // Shaders are ignored when drawing bitmaps
        int modifiers = paint != null ? setupModifiers(bitmap, paint) : MODIFIER_NONE;
        final int nativePaint = paint == null ? 0 : paint.mNativePaint;

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
        if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
    }

    private static native void nDrawBitmap(int renderer, int bitmap, byte[] buffer,
            float srcLeft, float srcTop, float srcRight, float srcBottom,
            float left, float top, float right, float bottom, int paint);

    @Override
    public void drawBitmap(int[] colors, int offset, int stride, float x, float y,
            int width, int height, boolean hasAlpha, Paint paint) {
        // Shaders are ignored when drawing bitmaps
        int modifier = paint != null ? setupColorFilter(paint) : MODIFIER_NONE;
        final Bitmap.Config config = hasAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        final Bitmap b = Bitmap.createBitmap(colors, offset, stride, width, height, config);
        final int nativePaint = paint == null ? 0 : paint.mNativePaint;
        nDrawBitmap(mRenderer, b.mNativeBitmap, b.mBuffer, x, y, nativePaint);
        b.recycle();
        if (modifier != MODIFIER_NONE) nResetModifiers(mRenderer, modifier);
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
        if (meshWidth < 0 || meshHeight < 0 || vertOffset < 0 || colorOffset < 0) {
            throw new ArrayIndexOutOfBoundsException();
        }

        if (meshWidth == 0 || meshHeight == 0) {
            return;
        }

        final int count = (meshWidth + 1) * (meshHeight + 1);
        checkRange(verts.length, vertOffset, count * 2);

        // TODO: Colors are ignored for now
        colors = null;
        colorOffset = 0;

        int modifiers = paint != null ? setupModifiers(bitmap, paint) : MODIFIER_NONE;
        final int nativePaint = paint == null ? 0 : paint.mNativePaint;        
        nDrawBitmapMesh(mRenderer, bitmap.mNativeBitmap, bitmap.mBuffer, meshWidth, meshHeight,
                verts, vertOffset, colors, colorOffset, nativePaint);
        if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
    }

    private static native void nDrawBitmapMesh(int renderer, int bitmap, byte[] buffer,
            int meshWidth, int meshHeight, float[] verts, int vertOffset,
            int[] colors, int colorOffset, int paint);

    @Override
    public void drawCircle(float cx, float cy, float radius, Paint paint) {
        int modifiers = setupModifiers(paint);
        nDrawCircle(mRenderer, cx, cy, radius, paint.mNativePaint);
        if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);        
    }

    private static native void nDrawCircle(int renderer, float cx, float cy,
            float radius, int paint);

    @Override
    public void drawColor(int color) {
        drawColor(color, PorterDuff.Mode.SRC_OVER);
    }

    @Override
    public void drawColor(int color, PorterDuff.Mode mode) {
        nDrawColor(mRenderer, color, mode.nativeInt);
    }
    
    private static native void nDrawColor(int renderer, int color, int mode);

    @Override
    public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {
        mLine[0] = startX;
        mLine[1] = startY;
        mLine[2] = stopX;
        mLine[3] = stopY;
        drawLines(mLine, 0, 4, paint);
    }

    @Override
    public void drawLines(float[] pts, int offset, int count, Paint paint) {
        if ((offset | count) < 0 || offset + count > pts.length) {
            throw new IllegalArgumentException("The lines array must contain 4 elements per line.");
        }
        int modifiers = setupModifiers(paint);
        nDrawLines(mRenderer, pts, offset, count, paint.mNativePaint);
        if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
    }

    private static native void nDrawLines(int renderer, float[] points,
            int offset, int count, int paint);

    @Override
    public void drawLines(float[] pts, Paint paint) {
        drawLines(pts, 0, pts.length, paint);
    }

    @Override
    public void drawOval(RectF oval, Paint paint) {
        int modifiers = setupModifiers(paint);
        nDrawOval(mRenderer, oval.left, oval.top, oval.right, oval.bottom, paint.mNativePaint);
        if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers); 
    }

    private static native void nDrawOval(int renderer, float left, float top,
            float right, float bottom, int paint);

    @Override
    public void drawPaint(Paint paint) {
        final Rect r = mClipBounds;
        nGetClipBounds(mRenderer, r);
        drawRect(r.left, r.top, r.right, r.bottom, paint);
    }

    @Override
    public void drawPath(Path path, Paint paint) {
        int modifiers = setupModifiers(paint);
        if (path.isSimplePath) {
            if (path.rects != null) {
                nDrawRects(mRenderer, path.rects.mNativeRegion, paint.mNativePaint);
            }
        } else {
            nDrawPath(mRenderer, path.mNativePath, paint.mNativePaint);
        }
        if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
    }

    private static native void nDrawPath(int renderer, int path, int paint);
    private static native void nDrawRects(int renderer, int region, int paint);

    @Override
    public void drawPicture(Picture picture) {
        // TODO: Implement
    }

    @Override
    public void drawPicture(Picture picture, Rect dst) {
        // TODO: Implement
    }

    @Override
    public void drawPicture(Picture picture, RectF dst) {
        // TODO: Implement
    }

    @Override
    public void drawPoint(float x, float y, Paint paint) {
        mPoint[0] = x;
        mPoint[1] = y;
        drawPoints(mPoint, 0, 2, paint);
    }

    @Override
    public void drawPoints(float[] pts, Paint paint) {
        drawPoints(pts, 0, pts.length, paint);
    }

    @Override
    public void drawPoints(float[] pts, int offset, int count, Paint paint) {
        int modifiers = setupModifiers(paint);
        nDrawPoints(mRenderer, pts, offset, count, paint.mNativePaint);
        if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
    }

    private static native void nDrawPoints(int renderer, float[] points,
            int offset, int count, int paint);

    @Override
    public void drawPosText(char[] text, int index, int count, float[] pos, Paint paint) {
        if (index < 0 || index + count > text.length || count * 2 > pos.length) {
            throw new IndexOutOfBoundsException();
        }

        int modifiers = setupModifiers(paint);
        try {
            nDrawPosText(mRenderer, text, index, count, pos, paint.mNativePaint);
        } finally {
            if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
        }
    }

    private static native void nDrawPosText(int renderer, char[] text, int index, int count,
            float[] pos, int paint);

    @Override
    public void drawPosText(String text, float[] pos, Paint paint) {
        if (text.length() * 2 > pos.length) {
            throw new ArrayIndexOutOfBoundsException();
        }

        int modifiers = setupModifiers(paint);
        try {
            nDrawPosText(mRenderer, text, 0, text.length(), pos, paint.mNativePaint);
        } finally {
            if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
        }
    }

    private static native void nDrawPosText(int renderer, String text, int start, int end,
            float[] pos, int paint);

    @Override
    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
        int modifiers = setupModifiers(paint);
        nDrawRect(mRenderer, left, top, right, bottom, paint.mNativePaint);
        if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
    }

    private static native void nDrawRect(int renderer, float left, float top,
            float right, float bottom, int paint);

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
        int modifiers = setupModifiers(paint);
        nDrawRoundRect(mRenderer, rect.left, rect.top, rect.right, rect.bottom,
                rx, ry, paint.mNativePaint);
        if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);        
    }

    private static native void nDrawRoundRect(int renderer, float left, float top,
            float right, float bottom, float rx, float y, int paint);

    @Override
    public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
        if ((index | count | (index + count) | (text.length - index - count)) < 0) {
            throw new IndexOutOfBoundsException();
        }

        int modifiers = setupModifiers(paint);
        try {
            nDrawText(mRenderer, text, index, count, x, y, paint.mBidiFlags, paint.mNativePaint);
        } finally {
            if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
        }
    }
    
    private static native void nDrawText(int renderer, char[] text, int index, int count,
            float x, float y, int bidiFlags, int paint);

    @Override
    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
        int modifiers = setupModifiers(paint);
        try {
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
                nDrawText(mRenderer, buf, 0, end - start, x, y,
                        paint.mBidiFlags, paint.mNativePaint);
                TemporaryBuffer.recycle(buf);
            }
        } finally {
            if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
        }
    }

    @Override
    public void drawText(String text, int start, int end, float x, float y, Paint paint) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }

        int modifiers = setupModifiers(paint);
        try {
            nDrawText(mRenderer, text, start, end, x, y, paint.mBidiFlags, paint.mNativePaint);
        } finally {
            if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
        }
    }

    private static native void nDrawText(int renderer, String text, int start, int end,
            float x, float y, int bidiFlags, int paint);

    @Override
    public void drawText(String text, float x, float y, Paint paint) {
        int modifiers = setupModifiers(paint);
        try {
            nDrawText(mRenderer, text, 0, text.length(), x, y, paint.mBidiFlags,
                    paint.mNativePaint);
        } finally {
            if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
        }
    }

    @Override
    public void drawTextOnPath(char[] text, int index, int count, Path path, float hOffset,
            float vOffset, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawTextOnPath(String text, Path path, float hOffset, float vOffset, Paint paint) {
        // TODO: Implement
    }

    @Override
    public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount,
            float x, float y, int dir, Paint paint) {
        if ((index | count | text.length - index - count) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (dir != DIRECTION_LTR && dir != DIRECTION_RTL) {
            throw new IllegalArgumentException("Unknown direction: " + dir);
        }

        int modifiers = setupModifiers(paint);
        try {
            nDrawTextRun(mRenderer, text, index, count, contextIndex, contextCount, x, y, dir,
                    paint.mNativePaint);
        } finally {
            if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
        }
    }

    private static native void nDrawTextRun(int renderer, char[] text, int index, int count,
            int contextIndex, int contextCount, float x, float y, int dir, int nativePaint);

    @Override
    public void drawTextRun(CharSequence text, int start, int end, int contextStart, int contextEnd,
            float x, float y, int dir, Paint paint) {
        if ((start | end | end - start | text.length() - end) < 0) {
            throw new IndexOutOfBoundsException();
        }

        int modifiers = setupModifiers(paint);
        try {
            int flags = dir == 0 ? 0 : 1;
            if (text instanceof String || text instanceof SpannedString ||
                    text instanceof SpannableString) {
                nDrawTextRun(mRenderer, text.toString(), start, end, contextStart,
                        contextEnd, x, y, flags, paint.mNativePaint);
            } else if (text instanceof GraphicsOperations) {
                ((GraphicsOperations) text).drawTextRun(this, start, end,
                        contextStart, contextEnd, x, y, flags, paint);
            } else {
                int contextLen = contextEnd - contextStart;
                int len = end - start;
                char[] buf = TemporaryBuffer.obtain(contextLen);
                TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
                nDrawTextRun(mRenderer, buf, start - contextStart, len, 0, contextLen,
                        x, y, flags, paint.mNativePaint);
                TemporaryBuffer.recycle(buf);
            }
        } finally {
            if (modifiers != MODIFIER_NONE) nResetModifiers(mRenderer, modifiers);
        }
    }

    private static native void nDrawTextRun(int renderer, String text, int start, int end,
            int contextStart, int contextEnd, float x, float y, int flags, int nativePaint);

    @Override
    public void drawVertices(VertexMode mode, int vertexCount, float[] verts, int vertOffset,
            float[] texs, int texOffset, int[] colors, int colorOffset, short[] indices,
            int indexOffset, int indexCount, Paint paint) {
        // TODO: Implement
    }

    private int setupModifiers(Bitmap b, Paint paint) {
        if (b.getConfig() == Bitmap.Config.ALPHA_8) {
            return setupModifiers(paint);
        }

        final ColorFilter filter = paint.getColorFilter();
        if (filter != null) {
            nSetupColorFilter(mRenderer, filter.nativeColorFilter);
            return MODIFIER_COLOR_FILTER;
        }

        return MODIFIER_NONE;
    }

    private int setupModifiers(Paint paint) {
        int modifiers = MODIFIER_NONE;

        if (paint.hasShadow) {
            nSetupShadow(mRenderer, paint.shadowRadius, paint.shadowDx, paint.shadowDy,
                    paint.shadowColor);
            modifiers |= MODIFIER_SHADOW;
        }

        final Shader shader = paint.getShader();
        if (shader != null) {
            nSetupShader(mRenderer, shader.native_shader);
            modifiers |= MODIFIER_SHADER;
        }

        final ColorFilter filter = paint.getColorFilter();
        if (filter != null) {
            nSetupColorFilter(mRenderer, filter.nativeColorFilter);
            modifiers |= MODIFIER_COLOR_FILTER;
        }

        return modifiers;
    }

    private int setupColorFilter(Paint paint) {
        final ColorFilter filter = paint.getColorFilter();
        if (filter != null) {
            nSetupColorFilter(mRenderer, filter.nativeColorFilter);
            return MODIFIER_COLOR_FILTER;
        }
        return MODIFIER_NONE;
    }

    private static native void nSetupShader(int renderer, int shader);
    private static native void nSetupColorFilter(int renderer, int colorFilter);
    private static native void nSetupShadow(int renderer, float radius,
            float dx, float dy, int color);

    private static native void nResetModifiers(int renderer, int modifiers);
}
