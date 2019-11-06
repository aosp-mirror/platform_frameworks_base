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

package com.android.systemui.glwallpaper;

import static android.opengl.EGL14.EGL_ALPHA_SIZE;
import static android.opengl.EGL14.EGL_BLUE_SIZE;
import static android.opengl.EGL14.EGL_CONFIG_CAVEAT;
import static android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION;
import static android.opengl.EGL14.EGL_DEFAULT_DISPLAY;
import static android.opengl.EGL14.EGL_DEPTH_SIZE;
import static android.opengl.EGL14.EGL_EXTENSIONS;
import static android.opengl.EGL14.EGL_GREEN_SIZE;
import static android.opengl.EGL14.EGL_NONE;
import static android.opengl.EGL14.EGL_NO_CONTEXT;
import static android.opengl.EGL14.EGL_NO_DISPLAY;
import static android.opengl.EGL14.EGL_NO_SURFACE;
import static android.opengl.EGL14.EGL_OPENGL_ES2_BIT;
import static android.opengl.EGL14.EGL_RED_SIZE;
import static android.opengl.EGL14.EGL_RENDERABLE_TYPE;
import static android.opengl.EGL14.EGL_STENCIL_SIZE;
import static android.opengl.EGL14.EGL_SUCCESS;
import static android.opengl.EGL14.eglChooseConfig;
import static android.opengl.EGL14.eglCreateContext;
import static android.opengl.EGL14.eglCreateWindowSurface;
import static android.opengl.EGL14.eglDestroyContext;
import static android.opengl.EGL14.eglDestroySurface;
import static android.opengl.EGL14.eglGetDisplay;
import static android.opengl.EGL14.eglGetError;
import static android.opengl.EGL14.eglInitialize;
import static android.opengl.EGL14.eglMakeCurrent;
import static android.opengl.EGL14.eglQueryString;
import static android.opengl.EGL14.eglSwapBuffers;
import static android.opengl.EGL14.eglTerminate;

import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLUtils;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A helper class to handle EGL management.
 */
public class EglHelper {
    private static final String TAG = EglHelper.class.getSimpleName();
    // Below two constants make drawing at low priority, so other things can preempt our drawing.
    private static final int EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100;
    private static final int EGL_CONTEXT_PRIORITY_LOW_IMG = 0x3103;
    private static final String EGL_IMG_CONTEXT_PRIORITY = "EGL_IMG_context_priority";

    private EGLDisplay mEglDisplay;
    private EGLConfig mEglConfig;
    private EGLContext mEglContext;
    private EGLSurface mEglSurface;
    private final int[] mEglVersion = new int[2];
    private boolean mEglReady;
    private boolean mContextPrioritySupported;

    /**
     * Initialize EGL and prepare EglSurface.
     * @param surfaceHolder surface holder.
     * @return true if EglSurface is ready.
     */
    public boolean init(SurfaceHolder surfaceHolder) {
        mEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (mEglDisplay == EGL_NO_DISPLAY) {
            Log.w(TAG, "eglGetDisplay failed: " + GLUtils.getEGLErrorString(eglGetError()));
            return false;
        }

        if (!eglInitialize(mEglDisplay, mEglVersion, 0 /* majorOffset */,
                    mEglVersion, 1 /* minorOffset */)) {
            Log.w(TAG, "eglInitialize failed: " + GLUtils.getEGLErrorString(eglGetError()));
            return false;
        }

        mEglConfig = chooseEglConfig();
        if (mEglConfig == null) {
            Log.w(TAG, "eglConfig not initialized!");
            return false;
        }

        if (!createEglContext()) {
            Log.w(TAG, "Can't create EGLContext!");
            return false;
        }

        if (!createEglSurface(surfaceHolder)) {
            Log.w(TAG, "Can't create EGLSurface!");
            return false;
        }

        mContextPrioritySupported = isContextPrioritySuppported();

        mEglReady = true;
        return true;
    }

    private boolean isContextPrioritySuppported() {
        String[] extensions = eglQueryString(mEglDisplay, EGL_EXTENSIONS).split(" ");
        for (String extension : extensions) {
            if (extension.equals(EGL_IMG_CONTEXT_PRIORITY)) {
                return true;
            }
        }
        return false;
    }

    private EGLConfig chooseEglConfig() {
        int[] configsCount = new int[1];
        EGLConfig[] configs = new EGLConfig[1];
        int[] configSpec = getConfig();
        if (!eglChooseConfig(mEglDisplay, configSpec, 0, configs, 0, 1, configsCount, 0)) {
            Log.w(TAG, "eglChooseConfig failed: " + GLUtils.getEGLErrorString(eglGetError()));
            return null;
        } else {
            if (configsCount[0] <= 0) {
                Log.w(TAG, "eglChooseConfig failed, invalid configs count: " + configsCount[0]);
                return null;
            } else {
                return configs[0];
            }
        }
    }

    private int[] getConfig() {
        return new int[] {
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0,
            EGL_STENCIL_SIZE, 0,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_CONFIG_CAVEAT, EGL_NONE,
            EGL_NONE
        };
    }

    /**
     * Prepare an EglSurface.
     * @param surfaceHolder surface holder.
     * @return true if EglSurface is ready.
     */
    public boolean createEglSurface(SurfaceHolder surfaceHolder) {
        if (hasEglDisplay()) {
            mEglSurface = eglCreateWindowSurface(mEglDisplay, mEglConfig, surfaceHolder, null, 0);
        } else {
            Log.w(TAG, "mEglDisplay is null");
            return false;
        }

        if (mEglSurface == null || mEglSurface == EGL_NO_SURFACE) {
            Log.w(TAG, "createWindowSurface failed: " + GLUtils.getEGLErrorString(eglGetError()));
            return false;
        }

        if (!eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            Log.w(TAG, "eglMakeCurrent failed: " + GLUtils.getEGLErrorString(eglGetError()));
            return false;
        }

        return true;
    }

    /**
     * Destroy EglSurface.
     */
    public void destroyEglSurface() {
        if (hasEglSurface()) {
            eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface(mEglDisplay, mEglSurface);
            mEglSurface = null;
        }
    }

    /**
     * Check if we have a valid EglSurface.
     * @return true if EglSurface is ready.
     */
    public boolean hasEglSurface() {
        return mEglSurface != null && mEglSurface != EGL_NO_SURFACE;
    }

    /**
     * Prepare EglContext.
     * @return true if EglContext is ready.
     */
    public boolean createEglContext() {
        int[] attrib_list = new int[5];
        int idx = 0;
        attrib_list[idx++] = EGL_CONTEXT_CLIENT_VERSION;
        attrib_list[idx++] = 2;
        if (mContextPrioritySupported) {
            attrib_list[idx++] = EGL_CONTEXT_PRIORITY_LEVEL_IMG;
            attrib_list[idx++] = EGL_CONTEXT_PRIORITY_LOW_IMG;
        }
        attrib_list[idx++] = EGL_NONE;
        if (hasEglDisplay()) {
            mEglContext = eglCreateContext(mEglDisplay, mEglConfig, EGL_NO_CONTEXT, attrib_list, 0);
        } else {
            Log.w(TAG, "mEglDisplay is null");
            return false;
        }

        if (mEglContext == EGL_NO_CONTEXT) {
            Log.w(TAG, "eglCreateContext failed: " + GLUtils.getEGLErrorString(eglGetError()));
            return false;
        }
        return true;
    }

    /**
     * Destroy EglContext.
     */
    public void destroyEglContext() {
        if (hasEglContext()) {
            eglDestroyContext(mEglDisplay, mEglContext);
            mEglContext = null;
        }
    }

    /**
     * Check if we have EglContext.
     * @return true if EglContext is ready.
     */
    public boolean hasEglContext() {
        return mEglContext != null;
    }

    /**
     * Check if we have EglDisplay.
     * @return true if EglDisplay is ready.
     */
    public boolean hasEglDisplay() {
        return mEglDisplay != null;
    }

    /**
     * Swap buffer to display.
     * @return true if swap successfully.
     */
    public boolean swapBuffer() {
        boolean status = eglSwapBuffers(mEglDisplay, mEglSurface);
        int error = eglGetError();
        if (error != EGL_SUCCESS) {
            Log.w(TAG, "eglSwapBuffers failed: " + GLUtils.getEGLErrorString(error));
        }
        return status;
    }

    /**
     * Destroy EglSurface and EglContext, then terminate EGL.
     */
    public void finish() {
        if (hasEglSurface()) {
            destroyEglSurface();
        }
        if (hasEglContext()) {
            destroyEglContext();
        }
        if (hasEglDisplay()) {
            eglTerminate(mEglDisplay);
        }
        mEglReady = false;
    }

    /**
     * Called to dump current state.
     * @param prefix prefix.
     * @param fd fd.
     * @param out out.
     * @param args args.
     */
    public void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args) {
        String eglVersion = mEglVersion[0] + "." + mEglVersion[1];
        out.print(prefix); out.print("EGL version="); out.print(eglVersion);
        out.print(", "); out.print("EGL ready="); out.print(mEglReady);
        out.print(", "); out.print("has EglContext="); out.print(hasEglContext());
        out.print(", "); out.print("has EglSurface="); out.println(hasEglSurface());

        int[] configs = getConfig();
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int egl : configs) {
            sb.append("0x").append(Integer.toHexString(egl)).append(",");
        }
        sb.setCharAt(sb.length() - 1, '}');
        out.print(prefix); out.print("EglConfig="); out.println(sb.toString());
    }
}
