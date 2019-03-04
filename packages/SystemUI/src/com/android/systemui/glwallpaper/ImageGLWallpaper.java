/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.glwallpaper;

import static android.opengl.GLES20.GL_FLOAT;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_TEXTURE0;
import static android.opengl.GLES20.GL_TEXTURE_2D;
import static android.opengl.GLES20.GL_TEXTURE_MAG_FILTER;
import static android.opengl.GLES20.GL_TEXTURE_MIN_FILTER;
import static android.opengl.GLES20.GL_TRIANGLES;
import static android.opengl.GLES20.glActiveTexture;
import static android.opengl.GLES20.glBindTexture;
import static android.opengl.GLES20.glDrawArrays;
import static android.opengl.GLES20.glEnableVertexAttribArray;
import static android.opengl.GLES20.glGenTextures;
import static android.opengl.GLES20.glTexParameteri;
import static android.opengl.GLES20.glUniform1i;
import static android.opengl.GLES20.glVertexAttribPointer;

import android.graphics.Bitmap;
import android.opengl.GLUtils;
import android.os.Build;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * This class takes charge of the geometry data like vertices and texture coordinates.
 * It delivers these data to opengl runtime and triggers draw calls if necessary.
 */
class ImageGLWallpaper {
    private static final String TAG = ImageGLWallpaper.class.getSimpleName();

    static final String A_POSITION = "aPosition";
    static final String A_TEXTURE_COORDINATES = "aTextureCoordinates";
    static final String U_PER85 = "uPer85";
    static final String U_REVEAL = "uReveal";
    static final String U_AOD2OPACITY = "uAod2Opacity";
    static final String U_TEXTURE = "uTexture";

    private static final int HANDLE_UNDEFINED = -1;
    private static final int POSITION_COMPONENT_COUNT = 2;
    private static final int TEXTURE_COMPONENT_COUNT = 2;
    private static final int BYTES_PER_FLOAT = 4;

    // Vertices to define the square with 2 triangles.
    private static final float[] VERTICES = {
            -1.0f,  -1.0f,
            +1.0f,  -1.0f,
            +1.0f,  +1.0f,
            +1.0f,  +1.0f,
            -1.0f,  +1.0f,
            -1.0f,  -1.0f
    };

    // Texture coordinates that maps to vertices.
    private static final float[] TEXTURES = {
            0f, 1f,
            1f, 1f,
            1f, 0f,
            1f, 0f,
            0f, 0f,
            0f, 1f
    };

    private final FloatBuffer mVertexBuffer;
    private final FloatBuffer mTextureBuffer;
    private final ImageGLProgram mProgram;

    private int mAttrPosition;
    private int mAttrTextureCoordinates;
    private int mUniAod2Opacity;
    private int mUniPer85;
    private int mUniReveal;
    private int mUniTexture;
    private int mTextureId;

    ImageGLWallpaper(ImageGLProgram program) {
        mProgram = program;

        // Create an float array in opengles runtime (native) and put vertex data.
        mVertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        mVertexBuffer.put(VERTICES);
        mVertexBuffer.position(0);

        // Create an float array in opengles runtime (native) and put texture data.
        mTextureBuffer = ByteBuffer.allocateDirect(TEXTURES.length * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        mTextureBuffer.put(TEXTURES);
        mTextureBuffer.position(0);
    }

    void setup() {
        setupAttributes();
        setupUniforms();
    }

    private void setupAttributes() {
        mAttrPosition = mProgram.getAttributeHandle(A_POSITION);
        mVertexBuffer.position(0);
        glVertexAttribPointer(mAttrPosition, POSITION_COMPONENT_COUNT, GL_FLOAT,
                false, 0, mVertexBuffer);
        glEnableVertexAttribArray(mAttrPosition);

        mAttrTextureCoordinates = mProgram.getAttributeHandle(A_TEXTURE_COORDINATES);
        mTextureBuffer.position(0);
        glVertexAttribPointer(mAttrTextureCoordinates, TEXTURE_COMPONENT_COUNT, GL_FLOAT,
                false, 0, mTextureBuffer);
        glEnableVertexAttribArray(mAttrTextureCoordinates);
    }

    private void setupUniforms() {
        mUniAod2Opacity = mProgram.getUniformHandle(U_AOD2OPACITY);
        mUniPer85 = mProgram.getUniformHandle(U_PER85);
        mUniReveal = mProgram.getUniformHandle(U_REVEAL);
        mUniTexture = mProgram.getUniformHandle(U_TEXTURE);
    }

    int getHandle(String name) {
        switch (name) {
            case A_POSITION:
                return mAttrPosition;
            case A_TEXTURE_COORDINATES:
                return mAttrTextureCoordinates;
            case U_AOD2OPACITY:
                return mUniAod2Opacity;
            case U_PER85:
                return mUniPer85;
            case U_REVEAL:
                return mUniReveal;
            case U_TEXTURE:
                return mUniTexture;
            default:
                return HANDLE_UNDEFINED;
        }
    }

    void draw() {
        glDrawArrays(GL_TRIANGLES, 0, VERTICES.length / 2);
    }

    void setupTexture(Bitmap bitmap) {
        final int[] tids = new int[1];

        if (bitmap == null) {
            Log.w(TAG, "setupTexture: invalid bitmap");
            return;
        }

        // Generate one texture object and store the id in tids[0].
        glGenTextures(1, tids, 0);
        if (tids[0] == 0) {
            Log.w(TAG, "setupTexture: glGenTextures() failed");
            return;
        }

        // Bind a named texture to a texturing target.
        glBindTexture(GL_TEXTURE_2D, tids[0]);
        // Load the bitmap data and copy it over into the texture object that is currently bound.
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0);
        // Use bilinear texture filtering when minification.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        // Use bilinear texture filtering when magnification.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        mTextureId = tids[0];
    }

    void useTexture() {
        // Set the active texture unit to texture unit 0.
        glActiveTexture(GL_TEXTURE0);
        // Bind the texture to this unit.
        glBindTexture(GL_TEXTURE_2D, mTextureId);
        // Let the texture sampler in fragment shader to read form this texture unit.
        glUniform1i(mUniTexture, 0);
    }

    void adjustTextureCoordinates(Bitmap bitmap, int surfaceWidth, int surfaceHeight,
            float xOffset, float yOffset) {
        if (bitmap == null) {
            Log.d(TAG, "adjustTextureCoordinates: invalid bitmap");
            return;
        }

        int bitmapWidth = bitmap.getWidth();
        int bitmapHeight = bitmap.getHeight();
        float ratioW = 1f;
        float ratioH = 1f;
        float rX = 0f;
        float rY = 0f;
        float[] coordinates = null;

        final boolean adjustWidth = bitmapWidth > surfaceWidth;
        final boolean adjustHeight = bitmapHeight > surfaceHeight;

        if (adjustWidth || adjustHeight) {
            coordinates = TEXTURES.clone();
        }

        if (adjustWidth) {
            float x = (float) Math.round((bitmapWidth - surfaceWidth) * xOffset) / bitmapWidth;
            ratioW = (float) surfaceWidth / bitmapWidth;
            float referenceX = x + ratioW > 1f ? 1f - ratioW : x;
            for (int i = 0; i < coordinates.length; i += 2) {
                if (i == 2 || i == 4 || i == 6) {
                    coordinates[i] = Math.min(1f, referenceX + ratioW);
                } else {
                    coordinates[i] = referenceX;
                }
            }
            rX = referenceX;
        }


        if (adjustHeight) {
            float y = (float) Math.round((bitmapHeight - surfaceHeight) * yOffset) / bitmapHeight;
            ratioH = (float) surfaceHeight / bitmapHeight;
            float referenceY = y + ratioH > 1f ? 1f - ratioH : y;
            for (int i = 1; i < coordinates.length; i += 2) {
                if (i == 1 || i == 3 || i == 11) {
                    coordinates[i] = Math.min(1f, referenceY + ratioH);
                } else {
                    coordinates[i] = referenceY;
                }
            }
            rY = referenceY;
        }

        if (adjustWidth || adjustHeight) {
            if (Build.IS_DEBUGGABLE) {
                Log.d(TAG, "adjustTextureCoordinates: sW=" + surfaceWidth + ", sH=" + surfaceHeight
                        + ", bW=" + bitmapWidth + ", bH=" + bitmapHeight
                        + ", rW=" + ratioW + ", rH=" + ratioH + ", rX=" + rX + ", rY=" + rY);
            }
            mTextureBuffer.put(coordinates);
            mTextureBuffer.position(0);
        }
    }
}
