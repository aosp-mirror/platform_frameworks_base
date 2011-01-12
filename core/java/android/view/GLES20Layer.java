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
    private int mLayerId;
    int mLayerTextureId;

    private int mLayerWidth;
    private int mLayerHeight;

    private final GLES20Canvas mCanvas;

    private float mU;
    private float mV;

    @SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"})
    private final Finalizer mFinalizer;

    GLES20Layer(int width, int height, boolean isOpaque) {
        super(width, height, isOpaque);

        int[] layerInfo = new int[3];
        mLayerId = GLES20Canvas.nCreateLayer(width, height, layerInfo);
        if (mLayerId != 0) {
            mLayerWidth = layerInfo[0];
            mLayerHeight = layerInfo[1];
            mLayerTextureId = layerInfo[2];

            mCanvas = new GLES20Canvas(mLayerId, !isOpaque);
            mFinalizer = new Finalizer(mLayerId, mLayerTextureId);
            
            mU = mWidth / (float) mLayerWidth;
            mV = mHeight/ (float) mLayerHeight;
        } else {
            mCanvas = null;
            mFinalizer = null;
        }
    }

    float getU() {
        return mU;
    }

    float getV() {
        return mV;
    }

    @Override
    boolean isValid() {
        return mLayerId != 0 && mLayerWidth > 0 && mLayerHeight > 0;
    }

    @Override
    void resize(int width, int height) {
        if (!isValid() || width <= 0 || height <= 0) return;
        if (width > mLayerWidth || height > mLayerHeight) {
            mWidth = width;
            mHeight = height;

            int[] layerInfo = new int[3];

            GLES20Canvas.nResizeLayer(mLayerId, mLayerTextureId, width, height, layerInfo);

            mLayerWidth = layerInfo[0];
            mLayerHeight = layerInfo[1];

            mU = mWidth / (float) mLayerWidth;
            mV = mHeight/ (float) mLayerHeight;            
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
        mLayerId = mLayerTextureId = 0;
    }

    private static class Finalizer {
        private int mLayerId;
        private int mLayerTextureId;

        public Finalizer(int layerId, int layerTextureId) {
            mLayerId = layerId;
            mLayerTextureId = layerTextureId;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                if (mLayerId != 0 || mLayerTextureId != 0) {
                    GLES20Canvas.nDestroyLayerDeferred(mLayerId, mLayerTextureId);
                }
            } finally {
                super.finalize();
            }
        }

        void destroy() {
            GLES20Canvas.nDestroyLayer(mLayerId, mLayerTextureId);
            mLayerId = mLayerTextureId = 0;
        }
    }
}
