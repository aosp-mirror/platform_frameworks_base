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
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGL11;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;

@SuppressWarnings({"UnusedDeclaration"})
public class GLTextureViewActivity extends Activity implements TextureView.SurfaceTextureListener {
    private RenderThread mRenderThread;
    private TextureView mTextureView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mTextureView = new TextureView(this);
        mTextureView.setSurfaceTextureListener(this);

        setContentView(mTextureView, new FrameLayout.LayoutParams(500, 400, Gravity.CENTER));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mRenderThread.finish();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface) {
        mRenderThread = new RenderThread(surface);
        mRenderThread.start();

        mTextureView.setCameraDistance(5000);

        ObjectAnimator animator = ObjectAnimator.ofFloat(mTextureView, "rotationY", 0.0f, 360.0f);
        animator.setRepeatMode(ObjectAnimator.REVERSE);
        animator.setRepeatCount(ObjectAnimator.INFINITE);
        animator.setDuration(4000);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                ((View) mTextureView.getParent()).invalidate();
            }
        });
        animator.start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    private static class RenderThread extends Thread {
        private static final String LOG_TAG = "GLTextureView";

        static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
        static final int EGL_SURFACE_TYPE = 0x3033;
        static final int EGL_SWAP_BEHAVIOR_PRESERVED_BIT = 0x0400;
        static final int EGL_OPENGL_ES2_BIT = 4;

        private volatile boolean mFinished;

        private SurfaceTexture mSurface;
        
        private EGL10 mEgl;
        private EGLDisplay mEglDisplay;
        private EGLConfig mEglConfig;
        private EGLContext mEglContext;
        private EGLSurface mEglSurface;
        private GL mGL;

        RenderThread(SurfaceTexture surface) {
            mSurface = surface;
        }

        @Override
        public void run() {
            initGL();

            float red = 0.0f;
            while (!mFinished) {
                checkCurrent();

                GLES20.glClearColor(red, 0.0f, 0.0f, 1.0f);
                int error = GLES20.glGetError();
                if (error != GLES20.GL_NO_ERROR) {
                    Log.w(LOG_TAG, "GL error = 0x" + Integer.toHexString(error));
                }

                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                error = GLES20.glGetError();
                if (error != GLES20.GL_NO_ERROR) {
                    Log.w(LOG_TAG, "GL error = 0x" + Integer.toHexString(error));
                }

                if (!mEgl.eglSwapBuffers(mEglDisplay, mEglSurface)) {
                    throw new RuntimeException("Cannot swap buffers");
                }
                
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    // Ignore
                }

                red += 0.021f;
                if (red > 1.0f) red = 0.0f;
            }

            finishGL();
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
                            + getEGLErrorString(mEgl.eglGetError()));
                }
            }
        }
        
        private void initGL() {
            mEgl = (EGL10) EGLContext.getEGL();

            mEglDisplay = mEgl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (mEglDisplay == EGL10.EGL_NO_DISPLAY) {
                throw new RuntimeException("eglGetDisplay failed "
                        + getEGLErrorString(mEgl.eglGetError()));
            }
            
            int[] version = new int[2];
            if (!mEgl.eglInitialize(mEglDisplay, version)) {
                throw new RuntimeException("eglInitialize failed " +
                        getEGLErrorString(mEgl.eglGetError()));
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
                        + getEGLErrorString(error));
            }

            if (!mEgl.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                throw new RuntimeException("eglMakeCurrent failed "
                        + getEGLErrorString(mEgl.eglGetError()));
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
                        getEGLErrorString(mEgl.eglGetError()));
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

        static String getEGLErrorString(int error) {
            switch (error) {
                case EGL10.EGL_SUCCESS:
                    return "EGL_SUCCESS";
                case EGL10.EGL_NOT_INITIALIZED:
                    return "EGL_NOT_INITIALIZED";
                case EGL10.EGL_BAD_ACCESS:
                    return "EGL_BAD_ACCESS";
                case EGL10.EGL_BAD_ALLOC:
                    return "EGL_BAD_ALLOC";
                case EGL10.EGL_BAD_ATTRIBUTE:
                    return "EGL_BAD_ATTRIBUTE";
                case EGL10.EGL_BAD_CONFIG:
                    return "EGL_BAD_CONFIG";
                case EGL10.EGL_BAD_CONTEXT:
                    return "EGL_BAD_CONTEXT";
                case EGL10.EGL_BAD_CURRENT_SURFACE:
                    return "EGL_BAD_CURRENT_SURFACE";
                case EGL10.EGL_BAD_DISPLAY:
                    return "EGL_BAD_DISPLAY";
                case EGL10.EGL_BAD_MATCH:
                    return "EGL_BAD_MATCH";
                case EGL10.EGL_BAD_NATIVE_PIXMAP:
                    return "EGL_BAD_NATIVE_PIXMAP";
                case EGL10.EGL_BAD_NATIVE_WINDOW:
                    return "EGL_BAD_NATIVE_WINDOW";
                case EGL10.EGL_BAD_PARAMETER:
                    return "EGL_BAD_PARAMETER";
                case EGL10.EGL_BAD_SURFACE:
                    return "EGL_BAD_SURFACE";
                case EGL11.EGL_CONTEXT_LOST:
                    return "EGL_CONTEXT_LOST";
                default:
                    return "0x" + Integer.toHexString(error);
            }
        }

        void finish() {
            mFinished = true;
        }
    }
}
