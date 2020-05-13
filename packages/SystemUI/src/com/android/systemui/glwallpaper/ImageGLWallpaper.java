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
import android.graphics.Rect;
import android.opengl.GLUtils;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
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

    private float[] mCurrentTexCoordinate;

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

    void setup(Bitmap bitmap) {
        setupAttributes();
        setupUniforms();
        setupTexture(bitmap);
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

    private void setupTexture(Bitmap bitmap) {
        final int[] tids = new int[1];

        if (bitmap == null || bitmap.isRecycled()) {
            Log.w(TAG, "setupTexture: invalid bitmap");
            return;
        }

        // Generate one texture object and store the id in tids[0].
        glGenTextures(1, tids, 0);
        if (tids[0] == 0) {
            Log.w(TAG, "setupTexture: glGenTextures() failed");
            return;
        }

        try {
            // Bind a named texture to a target.
            glBindTexture(GL_TEXTURE_2D, tids[0]);
            // Load the bitmap data and copy it over into the texture object
            // that is currently bound.
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0);
            // Use bilinear texture filtering when minification.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            // Use bilinear texture filtering when magnification.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            mTextureId = tids[0];
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Failed uploading texture: " + e.getLocalizedMessage());
        }
    }

    void useTexture() {
        // Set the active texture unit to texture unit 0.
        glActiveTexture(GL_TEXTURE0);
        // Bind the texture to this unit.
        glBindTexture(GL_TEXTURE_2D, mTextureId);
        // Let the texture sampler in fragment shader to read form this texture unit.
        glUniform1i(mUniTexture, 0);
    }

    /**
     * This method adjust s(x-axis), t(y-axis) texture coordinates to get current display area
     * of texture and will be used during transition.
     * The adjustment happens if either the width or height of the surface is larger than
     * corresponding size of the display area.
     * If both width and height are larger than corresponding size of the display area,
     * the adjustment will happen at both s, t side.
     *
     * @param surface The size of the surface.
     * @param scissor The display area.
     * @param xOffset The offset amount along s axis.
     * @param yOffset The offset amount along t axis.
     */
    void adjustTextureCoordinates(Rect surface, Rect scissor, float xOffset, float yOffset) {
        mCurrentTexCoordinate = TEXTURES.clone();

        if (surface == null || scissor == null) {
            mTextureBuffer.put(mCurrentTexCoordinate);
            mTextureBuffer.position(0);
            return;
        }

        int surfaceWidth = surface.width();
        int surfaceHeight = surface.height();
        int scissorWidth = scissor.width();
        int scissorHeight = scissor.height();

        if (surfaceWidth > scissorWidth) {
            // Calculate the new s pos in pixels.
            float pixelS = (float) Math.round((surfaceWidth - scissorWidth) * xOffset);
            // Calculate the s pos in texture coordinate.
            float coordinateS = pixelS / surfaceWidth;
            // Calculate the percentage occupied by the scissor width in surface width.
            float surfacePercentageW = (float) scissorWidth / surfaceWidth;
            // Need also consider the case if surface height is smaller than scissor height.
            if (surfaceHeight < scissorHeight) {
                // We will narrow the surface percentage to keep aspect ratio.
                surfacePercentageW *= (float) surfaceHeight / scissorHeight;
            }
            // Determine the final s pos, also limit the legal s pos to prevent from out of range.
            float s = coordinateS + surfacePercentageW > 1f ? 1f - surfacePercentageW : coordinateS;
            // Traverse the s pos in texture coordinates array and adjust the s pos accordingly.
            for (int i = 0; i < mCurrentTexCoordinate.length; i += 2) {
                // indices 2, 4 and 6 are the end of s coordinates.
                if (i == 2 || i == 4 || i == 6) {
                    mCurrentTexCoordinate[i] = Math.min(1f, s + surfacePercentageW);
                } else {
                    mCurrentTexCoordinate[i] = s;
                }
            }
        }

        if (surfaceHeight > scissorHeight) {
            // Calculate the new t pos in pixels.
            float pixelT = (float) Math.round((surfaceHeight - scissorHeight) * yOffset);
            // Calculate the t pos in texture coordinate.
            float coordinateT = pixelT / surfaceHeight;
            // Calculate the percentage occupied by the scissor height in surface height.
            float surfacePercentageH = (float) scissorHeight / surfaceHeight;
            // Need also consider the case if surface width is smaller than scissor width.
            if (surfaceWidth < scissorWidth) {
                // We will narrow the surface percentage to keep aspect ratio.
                surfacePercentageH *= (float) surfaceWidth / scissorWidth;
            }
            // Determine the final t pos, also limit the legal t pos to prevent from out of range.
            float t = coordinateT + surfacePercentageH > 1f ? 1f - surfacePercentageH : coordinateT;
            // Traverse the t pos in texture coordinates array and adjust the t pos accordingly.
            for (int i = 1; i < mCurrentTexCoordinate.length; i += 2) {
                // indices 1, 3 and 11 are the end of t coordinates.
                if (i == 1 || i == 3 || i == 11) {
                    mCurrentTexCoordinate[i] = Math.min(1f, t + surfacePercentageH);
                } else {
                    mCurrentTexCoordinate[i] = t;
                }
            }
        }

        mTextureBuffer.put(mCurrentTexCoordinate);
        mTextureBuffer.position(0);
    }

    /**
     * Called to dump current state.
     * @param prefix prefix.
     * @param fd fd.
     * @param out out.
     * @param args args.
     */
    public void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        if (mCurrentTexCoordinate != null) {
            for (int i = 0; i < mCurrentTexCoordinate.length; i++) {
                sb.append(mCurrentTexCoordinate[i]).append(',');
                if (i == mCurrentTexCoordinate.length - 1) {
                    sb.deleteCharAt(sb.length() - 1);
                }
            }
        }
        sb.append('}');
        out.print(prefix); out.print("mTexCoordinates="); out.println(sb.toString());
    }
}
