/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.google.android.gles_jni;

import javax.microedition.khronos.egl.*;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.SurfaceHolder;

public class EGLImpl implements EGL10 {
    private EGLContextImpl mContext = new EGLContextImpl(-1);
    private EGLDisplayImpl mDisplay = new EGLDisplayImpl(-1);
    private EGLSurfaceImpl mSurface = new EGLSurfaceImpl(-1);

    public native boolean     eglInitialize(EGLDisplay display, int[] major_minor);
    public native boolean     eglQueryContext(EGLDisplay display, EGLContext context, int attribute, int[] value);
    public native boolean     eglQuerySurface(EGLDisplay display, EGLSurface surface, int attribute, int[] value);
    /** @hide **/
    public native boolean     eglReleaseThread();
    public native boolean     eglChooseConfig(EGLDisplay display, int[] attrib_list, EGLConfig[] configs, int config_size, int[] num_config);
    public native boolean     eglGetConfigAttrib(EGLDisplay display, EGLConfig config, int attribute, int[] value);
    public native boolean     eglGetConfigs(EGLDisplay display, EGLConfig[] configs, int config_size, int[] num_config);
    public native int         eglGetError();
    public native boolean     eglDestroyContext(EGLDisplay display, EGLContext context);
    public native boolean     eglDestroySurface(EGLDisplay display, EGLSurface surface);
    public native boolean     eglMakeCurrent(EGLDisplay display, EGLSurface draw, EGLSurface read, EGLContext context);
    public native String      eglQueryString(EGLDisplay display, int name);
    public native boolean     eglSwapBuffers(EGLDisplay display, EGLSurface surface);
    public native boolean     eglTerminate(EGLDisplay display);
    public native boolean     eglCopyBuffers(EGLDisplay display, EGLSurface surface, Object native_pixmap);
    public native boolean     eglWaitGL();
    public native boolean     eglWaitNative(int engine, Object bindTarget);
    
    /** @hide **/
    public static native int  getInitCount(EGLDisplay display);

    public EGLContext eglCreateContext(EGLDisplay display, EGLConfig config, EGLContext share_context, int[] attrib_list) {
        long eglContextId = _eglCreateContext(display, config, share_context, attrib_list);
        if (eglContextId == 0) {
            return EGL10.EGL_NO_CONTEXT;
        }
        return new EGLContextImpl( eglContextId );
    }

    public EGLSurface eglCreatePbufferSurface(EGLDisplay display, EGLConfig config, int[] attrib_list) {
        long eglSurfaceId = _eglCreatePbufferSurface(display, config, attrib_list);
        if (eglSurfaceId == 0) {
            return EGL10.EGL_NO_SURFACE;
        }
        return new EGLSurfaceImpl( eglSurfaceId );
    }

    public EGLSurface eglCreatePixmapSurface(EGLDisplay display, EGLConfig config, Object native_pixmap, int[] attrib_list) {
        EGLSurfaceImpl sur = new EGLSurfaceImpl();
        _eglCreatePixmapSurface(sur, display, config, native_pixmap, attrib_list);
        if (sur.mEGLSurface == 0) {
            return EGL10.EGL_NO_SURFACE;
        }
        return sur;
    }

    public EGLSurface eglCreateWindowSurface(EGLDisplay display, EGLConfig config, Object native_window, int[] attrib_list) {
        Surface sur = null;
        if (native_window instanceof SurfaceView) {
            SurfaceView surfaceView = (SurfaceView)native_window;
            sur = surfaceView.getHolder().getSurface();
        } else if (native_window instanceof SurfaceHolder) {
            SurfaceHolder holder = (SurfaceHolder)native_window;
            sur = holder.getSurface();
        } else if (native_window instanceof Surface) {
            sur = (Surface) native_window;
        }

        long eglSurfaceId;
        if (sur != null) {
            eglSurfaceId = _eglCreateWindowSurface(display, config, sur, attrib_list);
        } else if (native_window instanceof SurfaceTexture) {
            eglSurfaceId = _eglCreateWindowSurfaceTexture(display, config,
                    native_window, attrib_list);
        } else {
            throw new java.lang.UnsupportedOperationException(
                "eglCreateWindowSurface() can only be called with an instance of " +
                "Surface, SurfaceView, SurfaceHolder or SurfaceTexture at the moment.");
        }

        if (eglSurfaceId == 0) {
            return EGL10.EGL_NO_SURFACE;
        }
        return new EGLSurfaceImpl( eglSurfaceId );
    }

    public synchronized EGLDisplay eglGetDisplay(Object native_display) {
        long value = _eglGetDisplay(native_display);
        if (value == 0) {
            return EGL10.EGL_NO_DISPLAY;
        }
        if (mDisplay.mEGLDisplay != value)
            mDisplay = new EGLDisplayImpl(value);
        return mDisplay;
    }

    public synchronized EGLContext eglGetCurrentContext() {
        long value = _eglGetCurrentContext();
        if (value == 0) {
            return EGL10.EGL_NO_CONTEXT;
        }
        if (mContext.mEGLContext != value)
            mContext = new EGLContextImpl(value);
        return mContext;
    }

    public synchronized EGLDisplay eglGetCurrentDisplay() {
        long value = _eglGetCurrentDisplay();
        if (value == 0) {
            return EGL10.EGL_NO_DISPLAY;
        }
        if (mDisplay.mEGLDisplay != value)
            mDisplay = new EGLDisplayImpl(value);
        return mDisplay;
    }

    public synchronized EGLSurface eglGetCurrentSurface(int readdraw) {
        long value = _eglGetCurrentSurface(readdraw);
        if (value == 0) {
            return EGL10.EGL_NO_SURFACE;
        }
        if (mSurface.mEGLSurface != value)
            mSurface = new EGLSurfaceImpl(value);
        return mSurface;
    }

    private native long _eglCreateContext(EGLDisplay display, EGLConfig config, EGLContext share_context, int[] attrib_list);
    private native long _eglCreatePbufferSurface(EGLDisplay display, EGLConfig config, int[] attrib_list);
    private native void _eglCreatePixmapSurface(EGLSurface sur, EGLDisplay display, EGLConfig config, Object native_pixmap, int[] attrib_list);
    private native long _eglCreateWindowSurface(EGLDisplay display, EGLConfig config, Object native_window, int[] attrib_list);
    private native long _eglCreateWindowSurfaceTexture(EGLDisplay display, EGLConfig config, Object native_window, int[] attrib_list);
    private native long _eglGetDisplay(Object native_display);
    private native long _eglGetCurrentContext();
    private native long _eglGetCurrentDisplay();
    private native long _eglGetCurrentSurface(int readdraw);

    native private static void _nativeClassInit();
    static { _nativeClassInit(); }
}
