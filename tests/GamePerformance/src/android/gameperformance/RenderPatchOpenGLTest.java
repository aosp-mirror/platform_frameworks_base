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
package android.gameperformance;

import java.util.List;

import javax.microedition.khronos.opengles.GL;

import android.annotation.NonNull;
import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;

/**
 * Base class for all OpenGL based tests that use RenderPatch as a base.
 */
public abstract class RenderPatchOpenGLTest extends OpenGLTest {
    private final float[] COLOR = new float[] { 1.0f, 1.0f, 1.0f, 1.0f };

    private final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;"
                    + "attribute vec4 vPosition;"
                    + "attribute vec2 vTexture;"
                    + "varying vec2 vTex;"
                    + "void main() {"
                    + "  vTex = vTexture;"
                    + "  gl_Position = uMVPMatrix * vPosition;"
                    + "}";

    private final String FRAGMENT_SHADER =
            "precision mediump float;"
                    + "uniform sampler2D uTexture;"
                    + "uniform vec4 uColor;"
                    + "varying vec2 vTex;"
                    + "void main() {"
                    + "  vec4 color = texture2D(uTexture, vTex);"
                    + "  gl_FragColor = uColor * color;"
                    + "}";

    private List<RenderPatchAnimation> mRenderPatches;

    private int mProgram = -1;
    private int mMVPMatrixHandle;
    private int mTextureHandle;
    private int mPositionHandle;
    private int mColorHandle;
    private int mTextureCoordHandle;

    private final float[] mVPMatrix = new float[16];

    public RenderPatchOpenGLTest(@NonNull GamePerformanceActivity activity) {
        super(activity);
    }

    protected void setRenderPatches(@NonNull List<RenderPatchAnimation> renderPatches) {
        mRenderPatches = renderPatches;
    }

    private void ensureInited() {
        if (mProgram >= 0) {
            return;
        }

        mProgram = OpenGLUtils.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);

        // get handle to fragment shader's uColor member
        GLES20.glUseProgram(mProgram);
        OpenGLUtils.checkGlError("useProgram");

        // get handle to shape's transformation matrix
        mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        OpenGLUtils.checkGlError("get uMVPMatrix");

        mTextureHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");
        OpenGLUtils.checkGlError("uTexture");
        // get handle to vertex shader's vPosition member
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition");
        OpenGLUtils.checkGlError("vPosition");
        mTextureCoordHandle = GLES20.glGetAttribLocation(mProgram, "vTexture");
        OpenGLUtils.checkGlError("vTexture");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");
        OpenGLUtils.checkGlError("uColor");

        mTextureHandle = OpenGLUtils.createTexture(getContext(), R.drawable.logo);

        final float[] projectionMatrix = new float[16];
        final float[] viewMatrix = new float[16];

        final float ratio = getView().getRenderRatio();
        Matrix.orthoM(projectionMatrix, 0, -ratio, ratio, -1, 1, -1, 1);
        Matrix.setLookAtM(viewMatrix, 0, 0, 0, -0.5f, 0f, 0f, 0f, 0f, 1.0f, 0.0f);
        Matrix.multiplyMM(mVPMatrix, 0, projectionMatrix, 0, viewMatrix, 0);
    }

    /**
     * Returns global color for patch.
     */
    public float[] getColor() {
        return COLOR;
    }

    /**
     * Extra setup for particular tests.
     */
    public void onBeforeDraw(GL gl) {
    }

    @Override
    public void draw(GL gl) {
        ensureInited();

        GLES20.glUseProgram(mProgram);
        OpenGLUtils.checkGlError("useProgram");

        GLES20.glDisable(GLES20.GL_BLEND);
        OpenGLUtils.checkGlError("disableBlend");

        GLES20.glEnableVertexAttribArray(mPositionHandle);
        OpenGLUtils.checkGlError("enableVertexAttributes");

        GLES20.glEnableVertexAttribArray(mTextureCoordHandle);
        OpenGLUtils.checkGlError("enableTexturesAttributes");

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureHandle);
        OpenGLUtils.checkGlError("setTexture");

        GLES20.glUniform4fv(mColorHandle, 1, getColor(), 0);
        OpenGLUtils.checkGlError("setColor");

        onBeforeDraw(gl);

        for (final RenderPatchAnimation renderPatchAnimation : mRenderPatches) {

            renderPatchAnimation.update(0.01f);
            GLES20.glUniformMatrix4fv(mMVPMatrixHandle,
                                      1,
                                      false,
                                      renderPatchAnimation.getTransform(mVPMatrix),
                                      0);
            OpenGLUtils.checkGlError("setTransform");

            GLES20.glVertexAttribPointer(
                    mPositionHandle,
                    RenderPatch.VERTEX_COORD_COUNT,
                    GLES20.GL_FLOAT,
                    false /* normalized */,
                    RenderPatch.VERTEX_STRIDE,
                    renderPatchAnimation.getRenderPatch().getVertexBuffer());
            OpenGLUtils.checkGlError("setVertexAttribute");

            GLES20.glVertexAttribPointer(
                    mTextureCoordHandle,
                    RenderPatch.TEXTURE_COORD_COUNT,
                    GLES20.GL_FLOAT,
                    false /* normalized */,
                    RenderPatch.TEXTURE_STRIDE,
                    renderPatchAnimation.getRenderPatch().getTextureBuffer());
            OpenGLUtils.checkGlError("setTextureAttribute");

            // Draw the patch.
            final int indicesCount =
                    renderPatchAnimation.getRenderPatch().getIndexBuffer().capacity() /
                    RenderPatch.SHORT_SIZE;
            GLES20.glDrawElements(
                    GLES20.GL_TRIANGLES,
                    indicesCount,
                    GLES20.GL_UNSIGNED_SHORT,
                    renderPatchAnimation.getRenderPatch().getIndexBuffer());
            OpenGLUtils.checkGlError("drawPatch");
        }

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTextureCoordHandle);
    }
}