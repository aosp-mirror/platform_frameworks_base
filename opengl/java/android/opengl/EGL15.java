/*
** Copyright 2018, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.opengl;

/**
 * EGL 1.5
 *
 */
public final class EGL15 {

    private EGL15() {};

    public static final int EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT            = 0x00000001;
    public static final int EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT   = 0x00000002;
    public static final int EGL_OPENGL_ES3_BIT                             = 0x00000040;
    public static final int EGL_SYNC_FLUSH_COMMANDS_BIT                    = 0x0001;
    public static final int EGL_GL_COLORSPACE_SRGB                         = 0x3089;
    public static final int EGL_GL_COLORSPACE_LINEAR                       = 0x308A;
    public static final int EGL_CONTEXT_MAJOR_VERSION                      = 0x3098;
    public static final int EGL_CL_EVENT_HANDLE                            = 0x309C;
    public static final int EGL_GL_COLORSPACE                              = 0x309D;
    public static final int EGL_GL_TEXTURE_2D                              = 0x30B1;
    public static final int EGL_GL_TEXTURE_3D                              = 0x30B2;
    public static final int EGL_GL_TEXTURE_CUBE_MAP_POSITIVE_X             = 0x30B3;
    public static final int EGL_GL_TEXTURE_CUBE_MAP_NEGATIVE_X             = 0x30B4;
    public static final int EGL_GL_TEXTURE_CUBE_MAP_POSITIVE_Y             = 0x30B5;
    public static final int EGL_GL_TEXTURE_CUBE_MAP_NEGATIVE_Y             = 0x30B6;
    public static final int EGL_GL_TEXTURE_CUBE_MAP_POSITIVE_Z             = 0x30B7;
    public static final int EGL_GL_TEXTURE_CUBE_MAP_NEGATIVE_Z             = 0x30B8;
    public static final int EGL_GL_RENDERBUFFER                            = 0x30B9;
    public static final int EGL_GL_TEXTURE_LEVEL                           = 0x30BC;
    public static final int EGL_GL_TEXTURE_ZOFFSET                         = 0x30BD;
    public static final int EGL_IMAGE_PRESERVED                            = 0x30D2;
    public static final int EGL_SYNC_PRIOR_COMMANDS_COMPLETE               = 0x30F0;
    public static final int EGL_SYNC_STATUS                                = 0x30F1;
    public static final int EGL_SIGNALED                                   = 0x30F2;
    public static final int EGL_UNSIGNALED                                 = 0x30F3;
    public static final int EGL_TIMEOUT_EXPIRED                            = 0x30F5;
    public static final int EGL_CONDITION_SATISFIED                        = 0x30F6;
    public static final int EGL_SYNC_TYPE                                  = 0x30F7;
    public static final int EGL_SYNC_CONDITION                             = 0x30F8;
    public static final int EGL_SYNC_FENCE                                 = 0x30F9;
    public static final int EGL_CONTEXT_MINOR_VERSION                      = 0x30FB;
    public static final int EGL_CONTEXT_OPENGL_PROFILE_MASK                = 0x30FD;
    public static final int EGL_SYNC_CL_EVENT                              = 0x30FE;
    public static final int EGL_SYNC_CL_EVENT_COMPLETE                     = 0x30FF;
    public static final int EGL_CONTEXT_OPENGL_DEBUG                       = 0x31B0;
    public static final int EGL_CONTEXT_OPENGL_FORWARD_COMPATIBLE          = 0x31B1;
    public static final int EGL_CONTEXT_OPENGL_ROBUST_ACCESS               = 0x31B2;
    public static final int EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY = 0x31BD;
    public static final int EGL_NO_RESET_NOTIFICATION                      = 0x31BE;
    public static final int EGL_LOSE_CONTEXT_ON_RESET                      = 0x31BF;
    public static final int EGL_PLATFORM_ANDROID_KHR                       = 0x3141;
    public static final long EGL_FOREVER                                   = 0xFFFFFFFFFFFFFFFFL;
    public static final EGLImage EGL_NO_IMAGE                              = null;
    public static final EGLSync EGL_NO_SYNC                                = null;
    public static final EGLContext EGL_NO_CONTEXT                          = null;
    public static final EGLDisplay EGL_NO_DISPLAY                          = null;
    public static final EGLSurface EGL_NO_SURFACE                          = null;

    native private static void _nativeClassInit();
    static {
        _nativeClassInit();
    }
    // C function EGLSync eglCreateSync ( EGLDisplay dpy, EGLenum type, const EGLAttrib *attrib_list )

    public static native EGLSync eglCreateSync(
        EGLDisplay dpy,
        int type,
        long[] attrib_list,
        int offset
    );

    /**
    * C function EGLBoolean eglGetSyncAttrib ( EGLDisplay dpy, EGLSync sync, EGLint attribute,
    *                                          EGLAttrib *value )
    */

    public static native boolean eglGetSyncAttrib(
            EGLDisplay dpy,
            EGLSync sync,
            int attribute,
            long[] value,
            int offset
    );

    // C function EGLBoolean eglDestroySync ( EGLDisplay dpy, EGLSync sync )

    public static native boolean eglDestroySync(
        EGLDisplay dpy,
        EGLSync sync
    );

    // C function EGLint eglClientWaitSync ( EGLDisplay dpy, EGLSync sync, EGLint flags, EGLTime timeout )

    public static native int eglClientWaitSync(
        EGLDisplay dpy,
        EGLSync sync,
        int flags,
        long timeout
    );

    // C function EGLDisplay eglGetPlatformDisplay ( EGLenum platform, EGLAttrib native_display, const EGLAttrib *attrib_list )

    public static native EGLDisplay eglGetPlatformDisplay(
        int platform,
        long native_display,
        long[] attrib_list,
        int offset
    );

    // C function EGLSurface eglCreatePlatformWindowSurface ( EGLDisplay dpy, EGLConfig config, void *native_window, const EGLAttrib *attrib_list )

    public static native EGLSurface eglCreatePlatformWindowSurface(
        EGLDisplay dpy,
        EGLConfig config,
        java.nio.Buffer native_window,
        long[] attrib_list,
        int offset
    );

    // C function EGLSurface eglCreatePlatformPixmapSurface ( EGLDisplay dpy, EGLConfig config, void *native_pixmap, const EGLAttrib *attrib_list )

    public static native EGLSurface eglCreatePlatformPixmapSurface(
        EGLDisplay dpy,
        EGLConfig config,
        java.nio.Buffer native_pixmap,
        long[] attrib_list,
        int offset
    );

    // C function EGLBoolean eglWaitSync ( EGLDisplay dpy, EGLSync sync, EGLint flags )

    public static native boolean eglWaitSync(
        EGLDisplay dpy,
        EGLSync sync,
        int flags
    );

    // C function EGLImage eglCreateImage ( EGLDisplay dpy, EGLContext context, EGLenum target, EGLClientBuffer buffer, const EGLAttrib *attrib_list )

    public static native EGLImage eglCreateImage(
        EGLDisplay dpy,
        EGLContext context,
        int target,
        long buffer,
        long[] attrib_list,
        int offset
    );

    // C function EGLBoolean eglDestroyImage ( EGLDisplay dpy, EGLImage image )

    public static native boolean eglDestroyImage(
        EGLDisplay dpy,
        EGLImage image
    );

}
