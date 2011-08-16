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
import android.graphics.Matrix;

/**
 * An OpenGL ES 2.0 implementation of {@link HardwareLayer}. This
 * implementation can be used a rendering target. It generates a
 * {@link Canvas} that can be used to render into an FBO using OpenGL.
 */
class GLES20RenderLayer extends GLES20Layer {
    private int mLayerWidth;
    private int mLayerHeight;

    private final GLES20Canvas mCanvas;

    GLES20RenderLayer(int width, int height, boolean isOpaque) {
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

    /**
     * Ignored
     */
    @Override
    void setTransform(Matrix matrix) {
    }
}
