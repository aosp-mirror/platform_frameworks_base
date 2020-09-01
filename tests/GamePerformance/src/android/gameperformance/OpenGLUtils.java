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

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

/**
 * Helper class for OpenGL.
 */
public class OpenGLUtils {
    private final static String TAG = "OpenGLUtils";

    public static void checkGlError(String glOperation) {
        final int error = GLES20.glGetError();
        if (error == GLES20.GL_NO_ERROR) {
            return;
        }
        final String errorMessage = glOperation + ": glError " + error;
        Log.e(TAG, errorMessage);
    }

    public static int loadShader(int type, String shaderCode) {
        final int shader = GLES20.glCreateShader(type);
        checkGlError("createShader");

        GLES20.glShaderSource(shader, shaderCode);
        checkGlError("shaderSource");
        GLES20.glCompileShader(shader);
        checkGlError("shaderCompile");

        return shader;
    }

    public static int createProgram(@NonNull String vertexShaderCode,
                                    @NonNull String fragmentShaderCode) {
        final int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        final int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

        final int program = GLES20.glCreateProgram();
        checkGlError("createProgram");
        GLES20.glAttachShader(program, vertexShader);
        checkGlError("attachVertexShader");
        GLES20.glAttachShader(program, fragmentShader);
        checkGlError("attachFragmentShader");
        GLES20.glLinkProgram(program);
        checkGlError("linkProgram");

        return program;
    }

    public static int createTexture(@NonNull Context context, int resource) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;

        final int[] textureHandle = new int[1];
        GLES20.glGenTextures(1, textureHandle, 0);
        OpenGLUtils.checkGlError("GenTextures");
        final int handle = textureHandle[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handle);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLUtils.texImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                BitmapFactory.decodeResource(
                        context.getResources(), resource, options),
                0);

        return handle;
    }
}