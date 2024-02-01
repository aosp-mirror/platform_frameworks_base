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

import android.annotation.NonNull;
import android.hardware.SyncFence;
import android.os.ParcelFileDescriptor;
import android.util.Log;

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

    // EGL_ANDROID_native_fence_sync
    public static final int EGL_SYNC_NATIVE_FENCE_ANDROID     = 0x3144;
    public static final int EGL_SYNC_NATIVE_FENCE_FD_ANDROID  = 0x3145;
    public static final int EGL_SYNC_NATIVE_FENCE_SIGNALED_ANDROID = 0x3146;
    public static final int EGL_NO_NATIVE_FENCE_FD_ANDROID    = -1;

    native private static void _nativeClassInit();
    static {
        _nativeClassInit();
    }

    /**
     * Retrieves the SyncFence for an EGLSync created with EGL_SYNC_NATIVE_FENCE_ANDROID
     *
     * See <a href="https://www.khronos.org/registry/EGL/extensions/ANDROID/EGL_ANDROID_native_fence_sync.txt">
     *     EGL_ANDROID_native_fence_sync</a> extension for more details
     * @param display The EGLDisplay connection
     * @param sync The EGLSync to fetch the SyncFence from
     * @return A SyncFence representing the native fence.
     *       * If <sync> is not a valid sync object for <display>,
     *         an {@link SyncFence#isValid() invalid} SyncFence is returned and an EGL_BAD_PARAMETER
     *         error is generated.
     *       * If the EGL_SYNC_NATIVE_FENCE_FD_ANDROID attribute of <sync> is
     *         EGL_NO_NATIVE_FENCE_FD_ANDROID, an {@link SyncFence#isValid() invalid} SyncFence is
     *         returned and an EGL_BAD_PARAMETER error is generated.
     *       * If <display> does not match the display passed to eglCreateSync
     *         when <sync> was created, the behaviour is undefined.
     */
    public static @NonNull SyncFence eglDupNativeFenceFDANDROID(@NonNull EGLDisplay display,
            @NonNull EGLSync sync) {
        int fd = eglDupNativeFenceFDANDROIDImpl(display, sync);
        Log.d("EGL", "eglDupNativeFence returned " + fd);
        if (fd >= 0) {
            return SyncFence.create(ParcelFileDescriptor.adoptFd(fd));
        } else {
            return SyncFence.createEmpty();
        }
    }

    private static native int eglDupNativeFenceFDANDROIDImpl(EGLDisplay display, EGLSync sync);

    // C function EGLBoolean eglPresentationTimeANDROID ( EGLDisplay dpy, EGLSurface sur, EGLnsecsANDROID time )

    public static native boolean eglPresentationTimeANDROID(
        EGLDisplay dpy,
        EGLSurface sur,
        long time
    );

}
