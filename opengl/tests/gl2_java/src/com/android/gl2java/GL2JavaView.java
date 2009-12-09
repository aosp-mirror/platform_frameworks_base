/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.gl2java;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

import android.opengl.GLES20;

/**
 * An implementation of SurfaceView that uses the dedicated surface for
 * displaying an OpenGL animation.  This allows the animation to run in a
 * separate thread, without requiring that it be driven by the update mechanism
 * of the view hierarchy.
 *
 * The application-specific rendering code is delegated to a GLView.Renderer
 * instance.
 */
class GL2JavaView extends GLSurfaceView {
    private static String TAG = "GL2JavaView";

    public GL2JavaView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setRenderer(new Renderer());
    }

    private static class Renderer implements GLSurfaceView.Renderer {

        public Renderer() {
            mTriangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mTriangleVertices.put(mTriangleVerticesData).position(0);
        }

        public void onDrawFrame(GL10 gl) {
            GLES20.glClearColor(0.0f, 0.0f, 1.0f, 1.0f);
            GLES20.glClear( GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(mProgram);
            checkGlError("glUseProgram");

            GLES20.glVertexAttribPointer(mvPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mTriangleVertices);
            checkGlError("glVertexAttribPointer");
            GLES20.glEnableVertexAttribArray(mvPositionHandle);
            checkGlError("glEnableVertexAttribArray");
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
            checkGlError("glDrawArrays");

        }

        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
        }

        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            mProgram = createProgram(mVertexShader, mFragmentShader);
            if (mProgram == 0) {
                return;
            }
            mvPositionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition");
            checkGlError("glGetAttribLocation");
            if (mvPositionHandle == -1) {
                throw new RuntimeException("Could not get attrib location for vPosition");
            }
        }

        private int loadShader(int shaderType, String source) {
            int shader = GLES20.glCreateShader(shaderType);
            if (shader != 0) {
                GLES20.glShaderSource(shader, source);
                GLES20.glCompileShader(shader);
                int[] compiled = new int[1];
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
                if (compiled[0] == 0) {
                    Log.e(TAG, "Could not compile shader " + shaderType + ":");
                    Log.e(TAG, GLES20.glGetShaderInfoLog(shader));
                    GLES20.glDeleteShader(shader);
                    shader = 0;
                }
            }
            return shader;
        }

        private int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            if (vertexShader == 0) {
                return 0;
            }

            int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            if (pixelShader == 0) {
                return 0;
            }

            int program = GLES20.glCreateProgram();
            if (program != 0) {
                GLES20.glAttachShader(program, vertexShader);
                checkGlError("glAttachShader");
                GLES20.glAttachShader(program, pixelShader);
                checkGlError("glAttachShader");
                GLES20.glLinkProgram(program);
                int[] linkStatus = new int[1];
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
                if (linkStatus[0] != GLES20.GL_TRUE) {
                    Log.e(TAG, "Could not link program: ");
                    Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                    GLES20.glDeleteProgram(program);
                    program = 0;
                }
            }
            return program;
        }

        private void checkGlError(String op) {
            int error;
            while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
                Log.e(TAG, op + ": glError " + error);
                throw new RuntimeException(op + ": glError " + error);
            }
        }

        private final float[] mTriangleVerticesData = { 0.0f, 0.5f, -0.5f, -0.5f,
                0.5f, -0.5f };

        private FloatBuffer mTriangleVertices;

        private final String mVertexShader = "attribute vec4 vPosition;\n"
            + "void main() {\n"
            + "  gl_Position = vPosition;\n"
            + "}\n";

        private final String mFragmentShader = "precision mediump float;\n"
            + "void main() {\n"
            + "  gl_FragColor = vec4(0.0, 1.0, 0.0, 1.0);\n"
            + "}\n";

        private int mProgram;
        private int mvPositionHandle;

    }
}

