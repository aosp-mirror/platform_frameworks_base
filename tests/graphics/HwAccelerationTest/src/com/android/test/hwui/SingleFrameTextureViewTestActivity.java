/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.opengl.GLES20.GL_COLOR_BUFFER_BIT;
import static android.opengl.GLES20.glClear;
import static android.opengl.GLES20.glClearColor;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.opengl.GLUtils;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.FrameLayout;
import android.widget.TextView;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public class SingleFrameTextureViewTestActivity extends Activity implements SurfaceTextureListener {
    private static final String LOG_TAG = "SingleFrameTest";

    private View mPreview;
    private TextureView mTextureView;
    private Thread mGLThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView preview = new TextView(this);
        preview.setText("This is a preview");
        preview.setBackgroundColor(Color.WHITE);
        mPreview = preview;
        mTextureView = new TextureView(this);
        mTextureView.setSurfaceTextureListener(this);

        FrameLayout content = new FrameLayout(this);
        content.addView(mTextureView,
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        content.addView(mPreview,
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

        setContentView(content);
    }

    private void stopGlThread() {
        if (mGLThread != null) {
            try {
                mGLThread.join();
                mGLThread = null;
            } catch (InterruptedException e) { }
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.d(LOG_TAG, "onSurfaceAvailable");
        mGLThread = new Thread() {
            static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
            static final int EGL_OPENGL_ES2_BIT = 4;

            private EGL10 mEgl;
            private EGLDisplay mEglDisplay;
            private EGLConfig mEglConfig;
            private EGLContext mEglContext;
            private EGLSurface mEglSurface;

            @Override
            public void run() {
                initGL();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {}

                for (int i = 0; i < 2; i++) {
                    if (i == 0) {
                        glClearColor(0.0f, 0.0f, 1.0f, 1.0f);
                    } else {
                        glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
                    }
                    glClear(GL_COLOR_BUFFER_BIT);
                    Log.d(LOG_TAG, "eglSwapBuffers");
                    if (!mEgl.eglSwapBuffers(mEglDisplay, mEglSurface)) {
                        throw new RuntimeException("Cannot swap buffers");
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {}
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {}

                finishGL();
            }

            private void finishGL() {
                mEgl.eglDestroyContext(mEglDisplay, mEglContext);
                mEgl.eglDestroySurface(mEglDisplay, mEglSurface);
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

                mEglSurface = mEgl.eglCreateWindowSurface(mEglDisplay, mEglConfig, surface, null);

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

            private int[] getConfig() {
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
        };
        mGLThread.start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.d(LOG_TAG, "onSurfaceTextureSizeChanged");
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.d(LOG_TAG, "onSurfaceTextureDestroyed");
        stopGlThread();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        Log.d(LOG_TAG, "onSurfaceTextureUpdated");
        mPreview.setVisibility(View.GONE);
    }
}
