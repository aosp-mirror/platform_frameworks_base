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
    private static final int LAYER_TYPE_RENDER = 2;

    private HardwareRenderer mRenderer;
    private Finalizer mFinalizer;
    private DisplayList mDisplayList;
    private final int mLayerType;

    private HardwareLayer(HardwareRenderer renderer, long deferredUpdater, int type) {
        if (renderer == null || deferredUpdater == 0) {
            throw new IllegalArgumentException("Either hardware renderer: " + renderer
                    + " or deferredUpdater: " + deferredUpdater + " is invalid");
        }
        mRenderer = renderer;
        mLayerType = type;
        mFinalizer = new Finalizer(deferredUpdater);

        // Layer is considered initialized at this point, notify the HardwareRenderer
        mRenderer.onLayerCreated(this);
    }

    private void assertType(int type) {
        if (mLayerType != type) {
            throw new IllegalAccessError("Method not appropriate for this layer type! " + mLayerType);
        }
    }

    /**
     * Update the paint used when drawing this layer.
     *
     * @param paint The paint used when the layer is drawn into the destination canvas.
     * @see View#setLayerPaint(android.graphics.Paint)
     */
    public void setLayerPaint(Paint paint) {
        nSetLayerPaint(mFinalizer.mDeferredUpdater, paint.mNativePaint,
                paint.getColorFilter() != null ? paint.getColorFilter().native_instance : 0);
    }

    /**
     * Indicates whether this layer can be rendered.
     *
     * @return True if the layer can be rendered into, false otherwise
     */
    public boolean isValid() {
        return mFinalizer != null && mFinalizer.mDeferredUpdater != 0;
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
            mDisplayList.reset();
            mDisplayList = null;
        }
        if (mRenderer != null) {
            mRenderer.onLayerDestroyed(this);
            mRenderer = null;
        }
        doDestroyLayerUpdater();
    }

    /**
     * Destroys the deferred layer updater but not the backing layer. The
     * backing layer is instead returned and is the caller's responsibility
     * to destroy/recycle as appropriate.
     *
     * It is safe to call this in onLayerDestroyed only
     */
    public long detachBackingLayer() {
        long backingLayer = nDetachBackingLayer(mFinalizer.mDeferredUpdater);
        doDestroyLayerUpdater();
        return backingLayer;
    }

    private void doDestroyLayerUpdater() {
        if (mFinalizer != null) {
            mFinalizer.destroy();
            mFinalizer = null;
        }
    }

    public DisplayList startRecording() {
        assertType(LAYER_TYPE_RENDER);

        if (mDisplayList == null) {
            mDisplayList = DisplayList.create("HardwareLayer");
        }
        return mDisplayList;
    }

    public void endRecording(Rect dirtyRect) {
        nUpdateRenderLayer(mFinalizer.mDeferredUpdater, mDisplayList.getNativeDisplayList(),
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
        return nPrepare(mFinalizer.mDeferredUpdater, width, height, isOpaque);
    }

    /**
     * Sets an optional transform on this layer.
     *
     * @param matrix The transform to apply to the layer.
     */
    public void setTransform(Matrix matrix) {
        nSetTransform(mFinalizer.mDeferredUpdater, matrix.native_instance);
    }

    /**
     * Indicates that this layer has lost its texture.
     */
    public void onTextureDestroyed() {
        assertType(LAYER_TYPE_TEXTURE);
        nOnTextureDestroyed(mFinalizer.mDeferredUpdater);
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

        boolean success = nFlushChanges(mFinalizer.mDeferredUpdater);
        if (!success) {
            destroy();
        }
    }

    public long getLayer() {
        return nGetLayer(mFinalizer.mDeferredUpdater);
    }

    public void setSurfaceTexture(SurfaceTexture surface) {
        assertType(LAYER_TYPE_TEXTURE);
        nSetSurfaceTexture(mFinalizer.mDeferredUpdater, surface, false);
    }

    public void updateSurfaceTexture() {
        assertType(LAYER_TYPE_TEXTURE);
        nUpdateSurfaceTexture(mFinalizer.mDeferredUpdater);
    }

    /**
     * This should only be used by HardwareRenderer! Do not call directly
     */
    SurfaceTexture createSurfaceTexture() {
        assertType(LAYER_TYPE_TEXTURE);
        SurfaceTexture st = new SurfaceTexture(nGetTexName(mFinalizer.mDeferredUpdater));
        nSetSurfaceTexture(mFinalizer.mDeferredUpdater, st, true);
        return st;
    }

    /**
     * This should only be used by HardwareRenderer! Do not call directly
     */
    static HardwareLayer createTextureLayer(HardwareRenderer renderer) {
        return new HardwareLayer(renderer, nCreateTextureLayer(), LAYER_TYPE_TEXTURE);
    }

    /**
     * This should only be used by HardwareRenderer! Do not call directly
     */
    static HardwareLayer createRenderLayer(HardwareRenderer renderer,
            int width, int height) {
        return new HardwareLayer(renderer, nCreateRenderLayer(width, height), LAYER_TYPE_RENDER);
    }

    /** This also creates the underlying layer */
    private static native long nCreateTextureLayer();
    private static native long nCreateRenderLayer(int width, int height);

    private static native void nOnTextureDestroyed(long layerUpdater);
    private static native long nDetachBackingLayer(long layerUpdater);

    /** This also destroys the underlying layer if it is still attached.
     *  Note it does not recycle the underlying layer, but instead queues it
     *  for deferred deletion.
     *  The HardwareRenderer should use detachBackingLayer() in the
     *  onLayerDestroyed() callback to do recycling if desired.
     */
    private static native void nDestroyLayerUpdater(long layerUpdater);

    private static native boolean nPrepare(long layerUpdater, int width, int height, boolean isOpaque);
    private static native void nSetLayerPaint(long layerUpdater, long paint, long colorFilter);
    private static native void nSetTransform(long layerUpdater, long matrix);
    private static native void nSetSurfaceTexture(long layerUpdater,
            SurfaceTexture surface, boolean isAlreadyAttached);
    private static native void nUpdateSurfaceTexture(long layerUpdater);
    private static native void nUpdateRenderLayer(long layerUpdater, long displayList,
            int left, int top, int right, int bottom);

    private static native boolean nFlushChanges(long layerUpdater);

    private static native long nGetLayer(long layerUpdater);
    private static native int nGetTexName(long layerUpdater);

    private static class Finalizer {
        private long mDeferredUpdater;

        public Finalizer(long deferredUpdater) {
            mDeferredUpdater = deferredUpdater;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                destroy();
            } finally {
                super.finalize();
            }
        }

        void destroy() {
            if (mDeferredUpdater != 0) {
                nDestroyLayerUpdater(mDeferredUpdater);
                mDeferredUpdater = 0;
            }
        }
    }
}
