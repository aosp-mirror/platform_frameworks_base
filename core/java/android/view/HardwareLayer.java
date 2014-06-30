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
    private HardwareRenderer mRenderer;
    private VirtualRefBasePtr mFinalizer;

    private HardwareLayer(HardwareRenderer renderer, long deferredUpdater) {
        if (renderer == null || deferredUpdater == 0) {
            throw new IllegalArgumentException("Either hardware renderer: " + renderer
                    + " or deferredUpdater: " + deferredUpdater + " is invalid");
        }
        mRenderer = renderer;
        mFinalizer = new VirtualRefBasePtr(deferredUpdater);
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
        mRenderer.onLayerDestroyed(this);
        mRenderer = null;
        mFinalizer.release();
        mFinalizer = null;
    }

    public long getDeferredLayerUpdater() {
        return mFinalizer.get();
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
    public void detachSurfaceTexture() {
        mRenderer.detachSurfaceTexture(mFinalizer.get());
    }

    public long getLayer() {
        return nGetLayer(mFinalizer.get());
    }

    public void setSurfaceTexture(SurfaceTexture surface) {
        nSetSurfaceTexture(mFinalizer.get(), surface, false);
        mRenderer.pushLayerUpdate(this);
    }

    public void updateSurfaceTexture() {
        nUpdateSurfaceTexture(mFinalizer.get());
        mRenderer.pushLayerUpdate(this);
    }

    static HardwareLayer adoptTextureLayer(HardwareRenderer renderer, long layer) {
        return new HardwareLayer(renderer, layer);
    }

    private static native boolean nPrepare(long layerUpdater, int width, int height, boolean isOpaque);
    private static native void nSetLayerPaint(long layerUpdater, long paint);
    private static native void nSetTransform(long layerUpdater, long matrix);
    private static native void nSetSurfaceTexture(long layerUpdater,
            SurfaceTexture surface, boolean isAlreadyAttached);
    private static native void nUpdateSurfaceTexture(long layerUpdater);
    private static native void nUpdateRenderLayer(long layerUpdater, long displayList,
            int left, int top, int right, int bottom);

    private static native long nGetLayer(long layerUpdater);
    private static native int nGetTexName(long layerUpdater);
}
