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

package com.android.systemui.wallpapers.gl;

import static android.opengl.GLES20.GL_FRAGMENT_SHADER;
import static android.opengl.GLES20.GL_VERTEX_SHADER;
import static android.opengl.GLES20.glAttachShader;
import static android.opengl.GLES20.glCompileShader;
import static android.opengl.GLES20.glCreateProgram;
import static android.opengl.GLES20.glCreateShader;
import static android.opengl.GLES20.glGetAttribLocation;
import static android.opengl.GLES20.glGetUniformLocation;
import static android.opengl.GLES20.glLinkProgram;
import static android.opengl.GLES20.glShaderSource;
import static android.opengl.GLES20.glUseProgram;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * This class takes charge of linking shader codes and then return a handle for OpenGL ES program.
 */
class ImageGLProgram {
    private static final String TAG = ImageGLProgram.class.getSimpleName();

    private Context mContext;
    private int mProgramHandle;

    ImageGLProgram(Context context) {
        mContext = context.getApplicationContext();
    }

    private int loadShaderProgram(int vertexId, int fragmentId) {
        final String vertexSrc = getShaderResource(vertexId);
        final String fragmentSrc = getShaderResource(fragmentId);
        final int vertexHandle = getShaderHandle(GL_VERTEX_SHADER, vertexSrc);
        final int fragmentHandle = getShaderHandle(GL_FRAGMENT_SHADER, fragmentSrc);
        return getProgramHandle(vertexHandle, fragmentHandle);
    }

    private String getShaderResource(int shaderId) {
        Resources res = mContext.getResources();
        StringBuilder code = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(res.openRawResource(shaderId)))) {
            String nextLine;
            while ((nextLine = reader.readLine()) != null) {
                code.append(nextLine).append("\n");
            }
        } catch (IOException | Resources.NotFoundException ex) {
            Log.d(TAG, "Can not read the shader source", ex);
            code = null;
        }

        return code == null ? "" : code.toString();
    }

    private int getShaderHandle(int type, String src) {
        final int shader = glCreateShader(type);
        if (shader == 0) {
            Log.d(TAG, "Create shader failed, type=" + type);
            return 0;
        }
        glShaderSource(shader, src);
        glCompileShader(shader);
        return shader;
    }

    private int getProgramHandle(int vertexHandle, int fragmentHandle) {
        final int program = glCreateProgram();
        if (program == 0) {
            Log.d(TAG, "Can not create OpenGL ES program");
            return 0;
        }

        glAttachShader(program, vertexHandle);
        glAttachShader(program, fragmentHandle);
        glLinkProgram(program);
        return program;
    }

    boolean useGLProgram(int vertexResId, int fragmentResId) {
        mProgramHandle = loadShaderProgram(vertexResId, fragmentResId);
        glUseProgram(mProgramHandle);
        return true;
    }

    int getAttributeHandle(String name) {
        return glGetAttribLocation(mProgramHandle, name);
    }

    int getUniformHandle(String name) {
        return glGetUniformLocation(mProgramHandle, name);
    }
}
