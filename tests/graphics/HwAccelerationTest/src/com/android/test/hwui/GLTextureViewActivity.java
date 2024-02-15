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

package com.android.test.hwui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.opengl.GLUtils;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static android.opengl.GLES20.*;

@SuppressWarnings({"UnusedDeclaration"})
public class GLTextureViewActivity extends Activity implements TextureView.SurfaceTextureListener {
    private RenderThread mRenderThread;
    private TextureView mTextureView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mTextureView = new TextureView(this);
        mTextureView.setSurfaceTextureListener(this);
        mTextureView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Bitmap b = mTextureView.getBitmap(800, 800);
                BufferedOutputStream out = null;
                try {
                    File dump = new File(Environment.getExternalStorageDirectory(), "out.png");
                    out = new BufferedOutputStream(new FileOutputStream(dump));
                    b.compress(Bitmap.CompressFormat.PNG, 100, out);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } finally {
                    if (out != null) try {
                        out.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

        setContentView(mTextureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mRenderThread = new RenderThread(getResources(), surface);
        mRenderThread.start();

        mTextureView.setCameraDistance(5000);

        ObjectAnimator animator = ObjectAnimator.ofFloat(mTextureView, "rotationY", 0.0f, 360.0f);
        animator.setRepeatMode(ObjectAnimator.REVERSE);
        animator.setRepeatCount(ObjectAnimator.INFINITE);
        animator.setDuration(4000);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mTextureView.invalidate();
            }
        });
        animator.start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        mRenderThread.finish();
        try {
            mRenderThread.join();
        } catch (InterruptedException e) {
            Log.e(RenderThread.LOG_TAG, "Could not wait for render thread");
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    private static class RenderThread extends Thread {
        private static final String LOG_TAG = "GLTextureView";

        static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
        static final int EGL_OPENGL_ES2_BIT = 4;

        private volatile boolean mFinished;

        private final Resources mResources;
        private final SurfaceTexture mSurface;
        
        private EGL10 mEgl;
        private EGLDisplay mEglDisplay;
        private EGLConfig mEglConfig;
        private EGLContext mEglContext;
        private EGLSurface mEglSurface;
        private GL mGL;

        RenderThread(Resources resources, SurfaceTexture surface) {
            mResources = resources;
            mSurface = surface;
        }

        private static final String sSimpleVS =
                "attribute vec4 position;\n" +
                "attribute vec2 texCoords;\n" +
                "varying vec2 outTexCoords;\n" +
                "\nvoid main(void) {\n" +
                "    outTexCoords = texCoords;\n" +
                "    gl_Position = position;\n" +
                "}\n\n";
        private static final String sSimpleFS =
                "precision mediump float;\n\n" +
                "varying vec2 outTexCoords;\n" +
                "uniform sampler2D texture;\n" +
                "\nvoid main(void) {\n" +
                "    gl_FragColor = texture2D(texture, outTexCoords);\n" +
                "}\n\n";

        private static final int FLOAT_SIZE_BYTES = 4;
        private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
        private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
        private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
        private final float[] mTriangleVerticesData = {
                // X, Y, Z, U, V
                -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
                 1.0f, -1.0f, 0.0f, 1.0f, 0.0f,
                -1.0f,  1.0f, 0.0f, 0.0f, 1.0f,
                 1.0f,  1.0f, 0.0f, 1.0f, 1.0f,
        };

        @Override
        public void run() {
            initGL();
            
            FloatBuffer triangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.length
                    * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
            triangleVertices.put(mTriangleVerticesData).position(0);

            int texture = loadTexture(R.drawable.large_photo);
            int program = buildProgram(sSimpleVS, sSimpleFS);

            int attribPosition = glGetAttribLocation(program, "position");
            checkGlError();

            int attribTexCoords = glGetAttribLocation(program, "texCoords");
            checkGlError();

            int uniformTexture = glGetUniformLocation(program, "texture");
            checkGlError();

            glBindTexture(GL_TEXTURE_2D, texture);
            checkGlError();

            glUseProgram(program);
            checkGlError();

            glEnableVertexAttribArray(attribPosition);
            checkGlError();

            glEnableVertexAttribArray(attribTexCoords);
            checkGlError();

            glUniform1i(uniformTexture, 0);
            checkGlError();

            // drawQuad
            triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
            glVertexAttribPointer(attribPosition, 3, GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices);
            checkGlError();

            triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
            glVertexAttribPointer(attribTexCoords, 3, GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices);
            checkGlError();

            glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            checkGlError();
            
            while (!mFinished) {
                checkCurrent();

                glClear(GL_COLOR_BUFFER_BIT);
                checkGlError();

                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                checkGlError();

                if (!mEgl.eglSwapBuffers(mEglDisplay, mEglSurface)) {
                    throw new RuntimeException("Cannot swap buffers");
                }
                checkEglError();

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }

            finishGL();
        }

        private int loadTexture(int resource) {
            int[] textures = new int[1];

            glActiveTexture(GL_TEXTURE0);
            glGenTextures(1, textures, 0);
            checkGlError();

            int texture = textures[0];
            glBindTexture(GL_TEXTURE_2D, texture);
            checkGlError();
            
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

            Bitmap bitmap = BitmapFactory.decodeResource(mResources, resource);

            GLUtils.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA, bitmap, GL_UNSIGNED_BYTE, 0);
            checkGlError();

            bitmap.recycle();

            return texture;
        }
        
        private static int buildProgram(String vertex, String fragment) {
            int vertexShader = buildShader(vertex, GL_VERTEX_SHADER);
            if (vertexShader == 0) return 0;

            int fragmentShader = buildShader(fragment, GL_FRAGMENT_SHADER);
            if (fragmentShader == 0) return 0;

            int program = glCreateProgram();
            glAttachShader(program, vertexShader);
            checkGlError();

            glAttachShader(program, fragmentShader);
            checkGlError();

            glLinkProgram(program);
            checkGlError();

            int[] status = new int[1];
            glGetProgramiv(program, GL_LINK_STATUS, status, 0);
            if (status[0] != GL_TRUE) {
                String error = glGetProgramInfoLog(program);
                Log.d(LOG_TAG, "Error while linking program:\n" + error);
                glDeleteShader(vertexShader);
                glDeleteShader(fragmentShader);
                glDeleteProgram(program);
                return 0;
            }

            return program;
        }
        
        private static int buildShader(String source, int type) {
            int shader = glCreateShader(type);

            glShaderSource(shader, source);
            checkGlError();

            glCompileShader(shader);
            checkGlError();

            int[] status = new int[1];
            glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0);
            if (status[0] != GL_TRUE) {
                String error = glGetShaderInfoLog(shader);
                Log.d(LOG_TAG, "Error while compiling shader:\n" + error);
                glDeleteShader(shader);
                return 0;
            }
            
            return shader;
        }

        private void checkEglError() {
            int error = mEgl.eglGetError();
            if (error != EGL10.EGL_SUCCESS) {
                Log.w(LOG_TAG, "EGL error = 0x" + Integer.toHexString(error));
            }
        }

        private static void checkGlError() {
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                Log.w(LOG_TAG, "GL error = 0x" + Integer.toHexString(error));
            }
        }

        private void finishGL() {
            mEgl.eglDestroyContext(mEglDisplay, mEglContext);
            mEgl.eglDestroySurface(mEglDisplay, mEglSurface);
        }

        private void checkCurrent() {
            if (!mEglContext.equals(mEgl.eglGetCurrentContext()) ||
                    !mEglSurface.equals(mEgl.eglGetCurrentSurface(EGL10.EGL_DRAW))) {
                if (!mEgl.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                    throw new RuntimeException("eglMakeCurrent failed "
                            + GLUtils.getEGLErrorString(mEgl.eglGetError()));
                }
            }
        }
        
        private void initGL() {
            mEgl = (EGL10) EGLContext.getEGL();

            mEglDisplay = mEgl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (mEglDisplay == EGL10.EGL_NO_DISPLAY) {
                throw new RuntimeException("eglGetDisplay failed "
                        + GLUtils.getEGLErrorString(mEgl.eglGetError()));
            }
            
            int[] version = new int[2];
            if (!mEgl.eglInitialize(mEglDisplay, version)) {
                throw new RuntimeException("eglInitialize failed " +
                        GLUtils.getEGLErrorString(mEgl.eglGetError()));
            }

            mEglConfig = chooseEglConfig();
            if (mEglConfig == null) {
                throw new RuntimeException("eglConfig not initialized");
            }
            
            mEglContext = createContext(mEgl, mEglDisplay, mEglConfig);

            mEglSurface = mEgl.eglCreateWindowSurface(mEglDisplay, mEglConfig, mSurface, null);

            if (mEglSurface == null || mEglSurface == EGL10.EGL_NO_SURFACE) {
                int error = mEgl.eglGetError();
                if (error == EGL10.EGL_BAD_NATIVE_WINDOW) {
                    Log.e(LOG_TAG, "createWindowSurface returned EGL_BAD_NATIVE_WINDOW.");
                    return;
                }
                throw new RuntimeException("createWindowSurface failed "
                        + GLUtils.getEGLErrorString(error));
            }

            if (!mEgl.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                throw new RuntimeException("eglMakeCurrent failed "
                        + GLUtils.getEGLErrorString(mEgl.eglGetError()));
            }

            mGL = mEglContext.getGL();
        }
        

        EGLContext createContext(EGL10 egl, EGLDisplay eglDisplay, EGLConfig eglConfig) {
            int[] attrib_list = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE };
            return egl.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attrib_list);            
        }

        private EGLConfig chooseEglConfig() {
            int[] configsCount = new int[1];
            EGLConfig[] configs = new EGLConfig[1];
            int[] configSpec = getConfig();
            if (!mEgl.eglChooseConfig(mEglDisplay, configSpec, configs, 1, configsCount)) {
                throw new IllegalArgumentException("eglChooseConfig failed " +
                        GLUtils.getEGLErrorString(mEgl.eglGetError()));
            } else if (configsCount[0] > 0) {
                return configs[0];
            }
            return null;
        }
        
        private static int[] getConfig() {
            return new int[] {
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 0,
                    EGL10.EGL_STENCIL_SIZE, 0,
                    EGL10.EGL_NONE
            };
        }

        void finish() {
            mFinished = true;
        }
    }
}
