/*
** Copyright 2013, The Android Open Source Project
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

// This source file is automatically generated

package android.opengl;

/**
 * EGL Extensions
 */
public class EGLExt {

    // EGL_KHR_create_context
    public static final int EGL_CONTEXT_MAJOR_VERSION_KHR   = 0x3098;
    public static final int EGL_CONTEXT_MINOR_VERSION_KHR   = 0x30FB;
    public static final int EGL_CONTEXT_FLAGS_KHR           = 0x30FC;
    public static final int EGL_OPENGL_ES3_BIT_KHR          = 0x0040;
    public static final int EGL_RECORDABLE_ANDROID          = 0x3142;

    native private static void _nativeClassInit();
    static {
        _nativeClassInit();
    }

    // C function EGLBoolean eglPresentationTimeANDROID ( EGLDisplay dpy, EGLSurface sur, EGLnsecsANDROID time )

    public static native boolean eglPresentationTimeANDROID(
        EGLDisplay dpy,
        EGLSurface sur,
        long time
    );

}
