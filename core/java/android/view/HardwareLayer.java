/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;

import com.android.internal.util.VirtualRefBasePtr;

/**
 * A hardware layer can be used to render graphics operations into a hardware
 * friendly buffer. For instance, with an OpenGL backend a hardware layer
 * would use a Frame Buffer Object (FBO.) The hardware layer can be used as
 * a drawing cache when a complex set of graphics operations needs to be
 * drawn several times.
 *
 * @hide
 */
final class HardwareLayer {
    private static final int LAYER_TYPE_TEXTURE = 1;
    private static final int LAYER_TYPE_DISPLAY_LIST = 2;

    private HardwareRenderer mRenderer;
    private VirtualRefBasePtr mFinalizer;
    private RenderNode mDisplayList;
    private final int mLayerType;

    private HardwareLayer(HardwareRenderer renderer, long deferredUpdater, int type) {
        if (renderer == null || deferredUpdater == 0) {
            throw new IllegalArgumentException("Either hardware renderer: " + renderer
                    + " or deferredUpdater: " + deferredUpdater + " is invalid");
        }
        mRenderer = renderer;
        mLayerType = type;
        mFinalizer = new VirtualRefBasePtr(deferredUpdater);
    }

    private void assertType(int type) {
        if (mLayerType != type) {
            throw new IllegalAccessError("Method not appropriate for this layer type! " + mLayerType);
        }
    }

    boolean hasDisplayList() {
        return mDisplayList != null;
    }

    /**
     * Update the paint used when drawing this layer.
     *
     * @param paint The paint used when the layer is drawn into the destination canvas.
     * @see View#setLayerPaint(android.graphics.Paint)
     */
    public void setLayerPaint(Paint paint) {
        nSetLayerPaint(mFinalizer.get(), paint.mNativePaint);
        mRenderer.pushLayerUpdate(this);
    }

    /**
     * Indicates whether this layer can be rendered.
     *
     * @return True if the layer can be rendered into, false otherwise
     */
    public boolean isValid() {
        return mFinalizer != null && mFinalizer.get() != 0;
    }

    /**
     * Destroys resources without waiting for a GC.
     */
    public void destroy() {
        if (!isValid()) {
            // Already destroyed
            return;
        }

        if (mDisplayList != null) {
            mDisplayList.destroyDisplayListData();
            mDisplayList = null;
        }
        mRenderer.onLayerDestroyed(this);
        mRenderer = null;
        mFinalizer.release();
        mFinalizer = null;
    }

    public long getDeferredLayerUpdater() {
        return mFinalizer.get();
    }

    public RenderNode startRecording() {
        assertType(LAYER_TYPE_DISPLAY_LIST);

        if (mDisplayList == null) {
            mDisplayList = RenderNode.create("HardwareLayer");
        }
        return mDisplayList;
    }

    public void endRecording(Rect dirtyRect) {
        nUpdateRenderLayer(mFinalizer.get(), mDisplayList.getNativeDisplayList(),
                dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom);
        mRenderer.pushLayerUpdate(this);
    }

    /**
     * Copies this layer into the specified bitmap.
     *
     * @param bitmap The bitmap to copy they layer into
     *
     * @return True if the copy was successful, false otherwise
     */
    public boolean copyInto(Bitmap bitmap) {
        return mRenderer.copyLayerInto(this, bitmap);
    }

    /**
     * Update the layer's properties. Note that after calling this isValid() may
     * return false if the requested width/height cannot be satisfied
     *
     * @param width The new width of this layer
     * @param height The new height of this layer
     * @param isOpaque Whether this layer is opaque
     *
     * @return true if the layer's properties will change, false if they already
     *         match the desired values.
     */
    public boolean prepare(int width, int height, boolean isOpaque) {
        return nPrepare(mFinalizer.get(), width, height, isOpaque);
    }

    /**
     * Sets an optional transform on this layer.
     *
     * @param matrix The transform to apply to the layer.
     */
    public void setTransform(Matrix matrix) {
        nSetTransform(mFinalizer.get(), matrix.native_instance);
        mRenderer.pushLayerUpdate(this);
    }

    /**
     * Indicates that this layer has lost its texture.
     */
    public void detachSurfaceTexture(final SurfaceTexture surface) {
        assertType(LAYER_TYPE_TEXTURE);
        mRenderer.safelyRun(new Runnable() {
            @Override
            public void run() {
                surface.detachFromGLContext();
                // SurfaceTexture owns the texture name and detachFromGLContext
                // should have deleted it
                nOnTextureDestroyed(mFinalizer.get());
            }
        });
    }

    /**
     * This exists to minimize impact into the current HardwareLayer paths as
     * some of the specifics of how to handle error cases in the fully
     * deferred model will work
     */
    @Deprecated
    public void flushChanges() {
        if (HardwareRenderer.sUseRenderThread) {
            // Not supported, don't try.
            return;
        }

        boolean success = nFlushChanges(mFinalizer.get());
        if (!success) {
            destroy();
        }
    }

    public long getLayer() {
        return nGetLayer(mFinalizer.get());
    }

    public void setSurfaceTexture(SurfaceTexture surface) {
        assertType(LAYER_TYPE_TEXTURE);
        nSetSurfaceTexture(mFinalizer.get(), surface, false);
        mRenderer.pushLayerUpdate(this);
    }

    public void updateSurfaceTexture() {
        assertType(LAYER_TYPE_TEXTURE);
        nUpdateSurfaceTexture(mFinalizer.get());
        mRenderer.pushLayerUpdate(this);
    }

    /**
     * This should only be used by HardwareRenderer! Do not call directly
     */
    SurfaceTexture createSurfaceTexture() {
        assertType(LAYER_TYPE_TEXTURE);
        SurfaceTexture st = new SurfaceTexture(nGetTexName(mFinalizer.get()));
        nSetSurfaceTexture(mFinalizer.get(), st, true);
        return st;
    }

    /**
     * This should only be used by HardwareRenderer! Do not call directly
     */
    static HardwareLayer createTextureLayer(HardwareRenderer renderer) {
        return new HardwareLayer(renderer, nCreateTextureLayer(), LAYER_TYPE_TEXTURE);
    }

    static HardwareLayer adoptTextureLayer(HardwareRenderer renderer, long layer) {
        return new HardwareLayer(renderer, layer, LAYER_TYPE_TEXTURE);
    }

    /**
     * This should only be used by HardwareRenderer! Do not call directly
     */
    static HardwareLayer createDisplayListLayer(HardwareRenderer renderer,
            int width, int height) {
        return new HardwareLayer(renderer, nCreateRenderLayer(width, height), LAYER_TYPE_DISPLAY_LIST);
    }

    static HardwareLayer adoptDisplayListLayer(HardwareRenderer renderer, long layer) {
        return new HardwareLayer(renderer, layer, LAYER_TYPE_DISPLAY_LIST);
    }

    /** This also creates the underlying layer */
    private static native long nCreateTextureLayer();
    private static native long nCreateRenderLayer(int width, int height);

    private static native void nOnTextureDestroyed(long layerUpdater);

    private static native boolean nPrepare(long layerUpdater, int width, int height, boolean isOpaque);
    private static native void nSetLayerPaint(long layerUpdater, long paint);
    private static native void nSetTransform(long layerUpdater, long matrix);
    private static native void nSetSurfaceTexture(long layerUpdater,
            SurfaceTexture surface, boolean isAlreadyAttached);
    private static native void nUpdateSurfaceTexture(long layerUpdater);
    private static native void nUpdateRenderLayer(long layerUpdater, long displayList,
            int left, int top, int right, int bottom);

    private static native boolean nFlushChanges(long layerUpdater);

    private static native long nGetLayer(long layerUpdater);
    private static native int nGetTexName(long layerUpdater);
}
