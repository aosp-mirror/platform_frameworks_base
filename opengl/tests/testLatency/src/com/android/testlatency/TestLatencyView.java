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

package com.android.testlatency;

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
class TestLatencyView extends GLSurfaceView {
    private static String TAG = "TestLatencyiew";
    private float mX;
    private float mY;
    private float mDX;
    private float mDY;
    private long  mT;
    private long  mDT;

    public TestLatencyView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setRenderer(new Renderer());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
        case MotionEvent.ACTION_MOVE:
            float x = event.getX();
            float y = event.getY();
            long  t = event.getEventTime();
            synchronized(this) {
                mDT = t - mT;
                mT = t;
                mDX = x - mX;
                mX = x;
                mDY = y - mY;
                mY = y;
            }
            break;
        default:
            break;
        }
        return true;
    }

    private class Renderer implements GLSurfaceView.Renderer {
        private float mScaleX, mScaleY, mOffsetX, mOffsetY;
        private final float MS_PER_FRAME = 1000 / 60;
        public Renderer() {
            mTriangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }


        public void onDrawFrame(GL10 gl) {
            GLES20.glClearColor(0.4f, 0.4f, 0.4f, 1.0f);
            GLES20.glClear( GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(mProgram);
            checkGlError("glUseProgram");

            float x, y, dx, dy;
            long t, dt;
            synchronized(TestLatencyView.this) {
                x = mX;
                y = mY;
                dx = mDX;
                dy = mDY;
                dt = mDT;
            }

            if (dt > 0) {
                dx = dx * MS_PER_FRAME / dt;
                dy = dy * MS_PER_FRAME / dt;
            }

            GLES20.glEnableVertexAttribArray(mvPositionHandle);
            checkGlError("glEnableVertexAttribArray");
            GLES20.glEnableVertexAttribArray(mvColorHandle);
            checkGlError("glEnableVertexAttribArray");
            for(int step = 0; step < 8; step++) {
                float sx = (x + dx * step) * mScaleX + mOffsetX;
                float sy = (y + dy * step) * mScaleY + mOffsetY;
                int cbase = step * 4;

                for (int i = 0; i < mTriangleVerticesData.length; i += 6) {
                    mTriangleVerticesData2[i] = sx + mTriangleVerticesData[i];
                    mTriangleVerticesData2[i+1] = -sy + mTriangleVerticesData[i+1];
                    mTriangleVerticesData2[i+2] = mColors[cbase];
                    mTriangleVerticesData2[i+3] = mColors[cbase+1];
                    mTriangleVerticesData2[i+4] = mColors[cbase+2];
                    mTriangleVerticesData2[i+5] = mColors[cbase+3];
                }
                mTriangleVertices.position(0);
                mTriangleVertices.put(mTriangleVerticesData2).position(0);

                GLES20.glVertexAttribPointer(mvPositionHandle, 2, GLES20.GL_FLOAT, false, 6*4, mTriangleVertices);
                checkGlError("glVertexAttribPointer mvPosition");
                mTriangleVertices.put(mTriangleVerticesData2).position(2);
                GLES20.glVertexAttribPointer(mvColorHandle, 4, GLES20.GL_FLOAT, false, 6*4, mTriangleVertices);
                checkGlError("glVertexAttribPointer mvColor");
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
                checkGlError("glDrawArrays");
            }
        }

        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            mScaleX = 2.0f / width;
            mScaleY = 2.0f / height;
            mOffsetX = -1f;
            mOffsetY = -1f;
        }

        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            mProgram = createProgram(mVertexShader, mFragmentShader);
            if (mProgram == 0) {
                return;
            }
            mvPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            checkGlError("glGetAttribLocation");
            if (mvPositionHandle == -1) {
                throw new RuntimeException("Could not get attrib location for vPosition");
            }
            mvColorHandle = GLES20.glGetAttribLocation(mProgram, "aColor");
            checkGlError("glGetAttribLocation");
            if (mvColorHandle == -1) {
                throw new RuntimeException("Could not get attrib location for vColor");
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
                checkGlError("glAttachShader vertexShader");
                GLES20.glAttachShader(program, pixelShader);
                checkGlError("glAttachShader pixelShader");
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

        // X, Y, R G B A
        private final float[] mTriangleVerticesData = {
                -0.025f, 0.3f, 0.0f, 1.0f, 0.0f, 1.0f,
                 0.0f  , 0.0f, 0.0f, 1.0f, 0.0f, 1.0f,
                 0.025f, 0.3f, 1.0f, 1.0f, 255.0f, 1.0f
                };

        // Color cascade:
        private final float[] mColors = {
                0.0f, 0.0f, 0.0f, 1.0f,
                0.5f, 0.0f, 0.0f, 1.0f,
                0.0f, 0.5f, 0.0f, 1.0f,
                0.5f, 0.5f, 0.0f, 1.0f,

                0.0f, 0.0f, 0.5f, 1.0f,
                1.0f, 0.0f, 0.0f, 1.0f,
                1.0f, 1.0f, 1.0f, 1.0f,
                0.0f, 1.0f, 0.0f, 1.0f
        };

        private float[] mTriangleVerticesData2 = new float[mTriangleVerticesData.length];
        private FloatBuffer mTriangleVertices;

        private final String mVertexShader = "attribute vec4 aPosition;\n"
            + "attribute vec4 aColor;\n"
            + "varying vec4 vColor;\n"
            + "void main() {\n"
            + "  gl_Position = aPosition;\n"
            + "  vColor = aColor;\n"
            + "}\n";

        private final String mFragmentShader = "precision mediump float;\n"
            + "varying vec4 vColor;\n"
            + "void main() {\n"
            + "  gl_FragColor = vColor;\n"
            + "}\n";

        private int mProgram;
        private int mvPositionHandle;
        private int mvColorHandle;

    }
}

