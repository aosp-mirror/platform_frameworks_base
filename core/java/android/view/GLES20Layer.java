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

import android.graphics.Canvas;

/**
 * An OpenGL ES 2.0 implementation of {@link HardwareLayer}.
 */
class GLES20Layer extends HardwareLayer {
    private int mLayer;

    private int mLayerWidth;
    private int mLayerHeight;

    private final GLES20Canvas mCanvas;

    @SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"})
    private final Finalizer mFinalizer;

    GLES20Layer(int width, int height, boolean isOpaque) {
        super(width, height, isOpaque);

        int[] layerInfo = new int[2];
        mLayer = GLES20Canvas.nCreateLayer(width, height, isOpaque, layerInfo);
        if (mLayer != 0) {
            mLayerWidth = layerInfo[0];
            mLayerHeight = layerInfo[1];

            mCanvas = new GLES20Canvas(mLayer, !isOpaque);
            mFinalizer = new Finalizer(mLayer);
        } else {
            mCanvas = null;
            mFinalizer = null;
        }
    }

    /**
     * Returns the native layer object used to render this layer.
     * 
     * @return A pointer to the native layer object, or 0 if the object is NULL
     */
    public int getLayer() {
        return mLayer;
    }

    @Override
    boolean isValid() {
        return mLayer != 0 && mLayerWidth > 0 && mLayerHeight > 0;
    }

    @Override
    void resize(int width, int height) {
        if (!isValid() || width <= 0 || height <= 0) return;

        mWidth = width;
        mHeight = height;
        
        if (width != mLayerWidth || height != mLayerHeight) {
            int[] layerInfo = new int[2];

            GLES20Canvas.nResizeLayer(mLayer, width, height, layerInfo);

            mLayerWidth = layerInfo[0];
            mLayerHeight = layerInfo[1];
        }
    }

    @Override
    HardwareCanvas getCanvas() {
        return mCanvas;
    }

    @Override
    void end(Canvas currentCanvas) {
        if (currentCanvas instanceof GLES20Canvas) {
            ((GLES20Canvas) currentCanvas).resume();
        }
    }

    @Override
    HardwareCanvas start(Canvas currentCanvas) {
        if (currentCanvas instanceof GLES20Canvas) {
            ((GLES20Canvas) currentCanvas).interrupt();
        }
        return getCanvas();
    }

    @Override
    void destroy() {
        mFinalizer.destroy();
        mLayer = 0;
    }

    private static class Finalizer {
        private int mLayerId;

        public Finalizer(int layerId) {
            mLayerId = layerId;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                if (mLayerId != 0) {
                    GLES20Canvas.nDestroyLayerDeferred(mLayerId);
                }
            } finally {
                super.finalize();
            }
        }

        void destroy() {
            GLES20Canvas.nDestroyLayer(mLayerId);
            mLayerId = 0;
        }
    }
}
