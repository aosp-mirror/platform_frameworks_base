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

package com.android.server.wallpaper;

import static android.opengl.EGL14.EGL_ALPHA_SIZE;
import static android.opengl.EGL14.EGL_BLUE_SIZE;
import static android.opengl.EGL14.EGL_CONFIG_CAVEAT;
import static android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION;
import static android.opengl.EGL14.EGL_DEFAULT_DISPLAY;
import static android.opengl.EGL14.EGL_DEPTH_SIZE;
import static android.opengl.EGL14.EGL_GREEN_SIZE;
import static android.opengl.EGL14.EGL_HEIGHT;
import static android.opengl.EGL14.EGL_NONE;
import static android.opengl.EGL14.EGL_NO_CONTEXT;
import static android.opengl.EGL14.EGL_NO_DISPLAY;
import static android.opengl.EGL14.EGL_NO_SURFACE;
import static android.opengl.EGL14.EGL_OPENGL_ES2_BIT;
import static android.opengl.EGL14.EGL_RED_SIZE;
import static android.opengl.EGL14.EGL_RENDERABLE_TYPE;
import static android.opengl.EGL14.EGL_STENCIL_SIZE;
import static android.opengl.EGL14.EGL_WIDTH;
import static android.opengl.EGL14.eglChooseConfig;
import static android.opengl.EGL14.eglCreateContext;
import static android.opengl.EGL14.eglCreatePbufferSurface;
import static android.opengl.EGL14.eglDestroyContext;
import static android.opengl.EGL14.eglDestroySurface;
import static android.opengl.EGL14.eglGetDisplay;
import static android.opengl.EGL14.eglGetError;
import static android.opengl.EGL14.eglInitialize;
import static android.opengl.EGL14.eglMakeCurrent;
import static android.opengl.EGL14.eglTerminate;
import static android.opengl.GLES20.GL_MAX_TEXTURE_SIZE;
import static android.opengl.GLES20.glGetIntegerv;

import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLUtils;
import android.os.SystemProperties;
import android.util.Log;

class GLHelper {
    private static final String TAG = GLHelper.class.getSimpleName();
    private static final int sMaxTextureSize;

    static {
        int maxTextureSize = SystemProperties.getInt("sys.max_texture_size", 0);
        sMaxTextureSize = maxTextureSize > 0 ? maxTextureSize : retrieveTextureSizeFromGL();
    }

    private static int retrieveTextureSizeFromGL() {
        try {
            String err;

            // Before we can retrieve info from GL,
            // we have to create EGLContext, EGLConfig and EGLDisplay first.
            // We will fail at querying info from GL once one of above failed.
            // When this happens, we will use defValue instead.
            EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
            if (eglDisplay == null || eglDisplay == EGL_NO_DISPLAY) {
                err = "eglGetDisplay failed: " + GLUtils.getEGLErrorString(eglGetError());
                throw new RuntimeException(err);
            }

            if (!eglInitialize(eglDisplay, null, 0 /* majorOffset */, null, 1 /* minorOffset */)) {
                err = "eglInitialize failed: " + GLUtils.getEGLErrorString(eglGetError());
                throw new RuntimeException(err);
            }

            EGLConfig eglConfig = null;
            int[] configsCount = new int[1];
            EGLConfig[] configs = new EGLConfig[1];
            int[] configSpec = new int[] {
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL_RED_SIZE, 8,
                    EGL_GREEN_SIZE, 8,
                    EGL_BLUE_SIZE, 8,
                    EGL_ALPHA_SIZE, 0,
                    EGL_DEPTH_SIZE, 0,
                    EGL_STENCIL_SIZE, 0,
                    EGL_CONFIG_CAVEAT, EGL_NONE,
                    EGL_NONE
            };

            if (!eglChooseConfig(eglDisplay, configSpec, 0 /* attrib_listOffset */,
                    configs, 0  /* configOffset */, 1 /* config_size */,
                    configsCount, 0 /* num_configOffset */)) {
                err = "eglChooseConfig failed: " + GLUtils.getEGLErrorString(eglGetError());
                throw new RuntimeException(err);
            } else if (configsCount[0] > 0) {
                eglConfig = configs[0];
            }

            if (eglConfig == null) {
                throw new RuntimeException("eglConfig not initialized!");
            }

            int[] attr_list = new int[] {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
            EGLContext eglContext = eglCreateContext(
                    eglDisplay, eglConfig, EGL_NO_CONTEXT, attr_list, 0 /* offset */);

            if (eglContext == null || eglContext == EGL_NO_CONTEXT) {
                err = "eglCreateContext failed: " + GLUtils.getEGLErrorString(eglGetError());
                throw new RuntimeException(err);
            }

            // We create a push buffer temporarily for querying info from GL.
            int[] attrs = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
            EGLSurface eglSurface =
                    eglCreatePbufferSurface(eglDisplay, eglConfig, attrs, 0 /* offset */);
            eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

            // Now, we are ready to query the info from GL.
            int[] maxSize = new int[1];
            glGetIntegerv(GL_MAX_TEXTURE_SIZE, maxSize, 0 /* offset */);

            // We have got the info we want, release all egl resources.
            eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface(eglDisplay, eglSurface);
            eglDestroyContext(eglDisplay, eglContext);
            eglTerminate(eglDisplay);
            return maxSize[0];
        } catch (RuntimeException e) {
            Log.w(TAG, "Retrieve from GL failed", e);
            return Integer.MAX_VALUE;
        }
    }

    static int getMaxTextureSize() {
        return sMaxTextureSize;
    }
}

