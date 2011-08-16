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
import android.graphics.SurfaceTexture;

/**
 * An OpenGL ES 2.0 implementation of {@link HardwareLayer}. This
 * implementation can be used as a texture. Rendering into this
 * layer is not controlled by a {@link HardwareCanvas}.
 */
class GLES20TextureLayer extends GLES20Layer {
    private int mTexture;
    private SurfaceTexture mSurface;

    GLES20TextureLayer(boolean isOpaque) {
        int[] layerInfo = new int[2];
        mLayer = GLES20Canvas.nCreateTextureLayer(isOpaque, layerInfo);

        if (mLayer != 0) {
            mTexture = layerInfo[0];
            mFinalizer = new Finalizer(mLayer);
        } else {
            mFinalizer = null;
        }        
    }

    @Override
    boolean isValid() {
        return mLayer != 0 && mTexture != 0;
    }

    @Override
    void resize(int width, int height) {
    }

    @Override
    HardwareCanvas getCanvas() {
        return null;
    }

    @Override
    HardwareCanvas start(Canvas currentCanvas) {
        return null;
    }

    @Override
    void end(Canvas currentCanvas) {
    }

    SurfaceTexture getSurfaceTexture() {
        if (mSurface == null) {
            mSurface = new SurfaceTexture(mTexture, false);
        }
        return mSurface;
    }

    @Override
    void update(int width, int height, boolean isOpaque) {
        super.update(width, height, isOpaque);
        GLES20Canvas.nUpdateTextureLayer(mLayer, width, height, isOpaque, mSurface);
    }

    @Override
    void setTransform(Matrix matrix) {
        GLES20Canvas.nSetTextureLayerTransform(mLayer, matrix.native_instance);
    }
}
