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

package androidx.media.filterfw;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Looper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * TODO: Make this package-private as RenderTarget and TextureSource should suffice as public
 * facing OpenGL utilities.
 * @hide
 */
public class GLToolbox {

    public static int textureNone() {
        return 0;
    }

    public static boolean isTexture(int texId) {
        return GLES20.glIsTexture(texId);
    }

    public static void deleteTexture(int texId) {
        int[] textures = new int[] { texId };
        assertNonUiThread("glDeleteTextures");
        GLES20.glDeleteTextures(1, textures, 0);
        checkGlError("glDeleteTextures");
    }

    public static void deleteFbo(int fboId) {
        int[] fbos = new int[] { fboId };
        assertNonUiThread("glDeleteFramebuffers");
        GLES20.glDeleteFramebuffers(1, fbos, 0);
        checkGlError("glDeleteFramebuffers");
    }

    public static int generateTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        checkGlError("glGenTextures");
        return textures[0];
    }

    public static int generateFbo() {
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        checkGlError("glGenFramebuffers");
        return fbos[0];
    }

    public static void readFbo(int fboId, ByteBuffer pixels, int width, int height) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        checkGlError("glReadPixels");
    }

    public static void readTarget(RenderTarget target, ByteBuffer pixels, int width, int height) {
        target.focus();
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        checkGlError("glReadPixels");
    }

    public static int attachedTexture(int fboId) {
        int[] params = new int[1];
        GLES20.glGetFramebufferAttachmentParameteriv(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME,
            params, 0);
        checkGlError("glGetFramebufferAttachmentParameteriv");
        return params[0];
    }

    public static void attachTextureToFbo(int texId, int fboId) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                                      GLES20.GL_COLOR_ATTACHMENT0,
                                      GLES20.GL_TEXTURE_2D,
                                      texId,
                                      0);
        checkGlError("glFramebufferTexture2D");
    }

    public static void allocateTexturePixels(int texId, int target, int width, int height) {
        setTexturePixels(texId, target, (ByteBuffer)null, width, height);
    }

    public static void setTexturePixels(int texId, int target, Bitmap bitmap) {
        GLES20.glBindTexture(target, texId);
        GLUtils.texImage2D(target, 0, bitmap, 0);
        checkGlError("glTexImage2D");
        setDefaultTexParams();
    }

    public static void setTexturePixels(int texId, int target, ByteBuffer pixels,
                                        int width, int height) {
        GLES20.glBindTexture(target, texId);

        // For some devices, "pixels" being null causes system error.
        if (pixels == null) {
            pixels = ByteBuffer.allocateDirect(width * height * 4);
        }
        GLES20.glTexImage2D(target, 0, GLES20.GL_RGBA, width, height, 0,
                            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        checkGlError("glTexImage2D");
        setDefaultTexParams();
    }

    public static void setDefaultTexParams() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                               GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                               GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                               GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                               GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("glTexParameteri");
    }

    public static int vboNone() {
        return 0;
    }

    public static int generateVbo() {
        int[] vbos = new int[1];
        GLES20.glGenBuffers(1, vbos, 0);
        checkGlError("glGenBuffers");
        return vbos[0];
    }

    public static void setVboData(int vboId, ByteBuffer data) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.remaining(), data, GLES20.GL_STATIC_DRAW);
        checkGlError("glBufferData");
    }

    public static void setVboFloats(int vboId, float[] values) {
        int len = values.length * 4;
        ByteBuffer buffer = ByteBuffer.allocateDirect(len).order(ByteOrder.nativeOrder());
        setVboData(vboId, buffer);
    }

    public static boolean isVbo(int vboId) {
        return GLES20.glIsBuffer(vboId);
    }

    public static void deleteVbo(int vboId) {
        int[] buffers = new int[] { vboId };
        GLES20.glDeleteBuffers(1, buffers, 0);
        checkGlError("glDeleteBuffers");
    }

    public static void checkGlError(String operation) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            throw new RuntimeException("GL Operation '" + operation + "' caused error "
                + Integer.toHexString(error) + "!");
        }
    }

    /**
     * Make sure we are not operating in the UI thread.
     *
     * It is often tricky to track down bugs that happen when issuing GL commands in the UI thread.
     * This is especially true when releasing GL resources. Often this will cause errors much later
     * on. Therefore we make sure we do not do these dangerous operations on the UI thread.
     */
    private static void assertNonUiThread(String operation) {
        if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
            throw new RuntimeException("Attempting to perform GL operation '" + operation
                    + "' on UI thread!");
        }
    }
}
