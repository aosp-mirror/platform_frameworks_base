/*
** Copyright 2012, The Android Open Source Project
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

import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.SurfaceHolder;

/**
 * EGL 1.4
 *
 */
public class EGL14 {

public static final int EGL_DEFAULT_DISPLAY            = 0;
public static EGLContext EGL_NO_CONTEXT                = null;
public static EGLDisplay EGL_NO_DISPLAY                = null;
public static EGLSurface EGL_NO_SURFACE                = null;

public static final int EGL_FALSE                          = 0;
public static final int EGL_TRUE                           = 1;
public static final int EGL_SUCCESS                        = 0x3000;
public static final int EGL_NOT_INITIALIZED                = 0x3001;
public static final int EGL_BAD_ACCESS                     = 0x3002;
public static final int EGL_BAD_ALLOC                      = 0x3003;
public static final int EGL_BAD_ATTRIBUTE                  = 0x3004;
public static final int EGL_BAD_CONFIG                     = 0x3005;
public static final int EGL_BAD_CONTEXT                    = 0x3006;
public static final int EGL_BAD_CURRENT_SURFACE            = 0x3007;
public static final int EGL_BAD_DISPLAY                    = 0x3008;
public static final int EGL_BAD_MATCH                      = 0x3009;
public static final int EGL_BAD_NATIVE_PIXMAP              = 0x300A;
public static final int EGL_BAD_NATIVE_WINDOW              = 0x300B;
public static final int EGL_BAD_PARAMETER                  = 0x300C;
public static final int EGL_BAD_SURFACE                    = 0x300D;
public static final int EGL_CONTEXT_LOST                   = 0x300E;
public static final int EGL_BUFFER_SIZE                    = 0x3020;
public static final int EGL_ALPHA_SIZE                     = 0x3021;
public static final int EGL_BLUE_SIZE                      = 0x3022;
public static final int EGL_GREEN_SIZE                     = 0x3023;
public static final int EGL_RED_SIZE                       = 0x3024;
public static final int EGL_DEPTH_SIZE                     = 0x3025;
public static final int EGL_STENCIL_SIZE                   = 0x3026;
public static final int EGL_CONFIG_CAVEAT                  = 0x3027;
public static final int EGL_CONFIG_ID                      = 0x3028;
public static final int EGL_LEVEL                          = 0x3029;
public static final int EGL_MAX_PBUFFER_HEIGHT             = 0x302A;
public static final int EGL_MAX_PBUFFER_PIXELS             = 0x302B;
public static final int EGL_MAX_PBUFFER_WIDTH              = 0x302C;
public static final int EGL_NATIVE_RENDERABLE              = 0x302D;
public static final int EGL_NATIVE_VISUAL_ID               = 0x302E;
public static final int EGL_NATIVE_VISUAL_TYPE             = 0x302F;
public static final int EGL_SAMPLES                        = 0x3031;
public static final int EGL_SAMPLE_BUFFERS                 = 0x3032;
public static final int EGL_SURFACE_TYPE                   = 0x3033;
public static final int EGL_TRANSPARENT_TYPE               = 0x3034;
public static final int EGL_TRANSPARENT_BLUE_VALUE         = 0x3035;
public static final int EGL_TRANSPARENT_GREEN_VALUE        = 0x3036;
public static final int EGL_TRANSPARENT_RED_VALUE          = 0x3037;
public static final int EGL_NONE                           = 0x3038;
public static final int EGL_BIND_TO_TEXTURE_RGB            = 0x3039;
public static final int EGL_BIND_TO_TEXTURE_RGBA           = 0x303A;
public static final int EGL_MIN_SWAP_INTERVAL              = 0x303B;
public static final int EGL_MAX_SWAP_INTERVAL              = 0x303C;
public static final int EGL_LUMINANCE_SIZE                 = 0x303D;
public static final int EGL_ALPHA_MASK_SIZE                = 0x303E;
public static final int EGL_COLOR_BUFFER_TYPE              = 0x303F;
public static final int EGL_RENDERABLE_TYPE                = 0x3040;
public static final int EGL_MATCH_NATIVE_PIXMAP            = 0x3041;
public static final int EGL_CONFORMANT                     = 0x3042;
public static final int EGL_SLOW_CONFIG                    = 0x3050;
public static final int EGL_NON_CONFORMANT_CONFIG          = 0x3051;
public static final int EGL_TRANSPARENT_RGB                = 0x3052;
public static final int EGL_RGB_BUFFER                     = 0x308E;
public static final int EGL_LUMINANCE_BUFFER               = 0x308F;
public static final int EGL_NO_TEXTURE                     = 0x305C;
public static final int EGL_TEXTURE_RGB                    = 0x305D;
public static final int EGL_TEXTURE_RGBA                   = 0x305E;
public static final int EGL_TEXTURE_2D                     = 0x305F;
public static final int EGL_PBUFFER_BIT                    = 0x0001;
public static final int EGL_PIXMAP_BIT                     = 0x0002;
public static final int EGL_WINDOW_BIT                     = 0x0004;
public static final int EGL_VG_COLORSPACE_LINEAR_BIT       = 0x0020;
public static final int EGL_VG_ALPHA_FORMAT_PRE_BIT        = 0x0040;
public static final int EGL_MULTISAMPLE_RESOLVE_BOX_BIT    = 0x0200;
public static final int EGL_SWAP_BEHAVIOR_PRESERVED_BIT    = 0x0400;
public static final int EGL_OPENGL_ES_BIT                  = 0x0001;
public static final int EGL_OPENVG_BIT                     = 0x0002;
public static final int EGL_OPENGL_ES2_BIT                 = 0x0004;
public static final int EGL_OPENGL_BIT                     = 0x0008;
public static final int EGL_VENDOR                         = 0x3053;
public static final int EGL_VERSION                        = 0x3054;
public static final int EGL_EXTENSIONS                     = 0x3055;
public static final int EGL_CLIENT_APIS                    = 0x308D;
public static final int EGL_HEIGHT                         = 0x3056;
public static final int EGL_WIDTH                          = 0x3057;
public static final int EGL_LARGEST_PBUFFER                = 0x3058;
public static final int EGL_TEXTURE_FORMAT                 = 0x3080;
public static final int EGL_TEXTURE_TARGET                 = 0x3081;
public static final int EGL_MIPMAP_TEXTURE                 = 0x3082;
public static final int EGL_MIPMAP_LEVEL                   = 0x3083;
public static final int EGL_RENDER_BUFFER                  = 0x3086;
public static final int EGL_VG_COLORSPACE                  = 0x3087;
public static final int EGL_VG_ALPHA_FORMAT                = 0x3088;
public static final int EGL_HORIZONTAL_RESOLUTION          = 0x3090;
public static final int EGL_VERTICAL_RESOLUTION            = 0x3091;
public static final int EGL_PIXEL_ASPECT_RATIO             = 0x3092;
public static final int EGL_SWAP_BEHAVIOR                  = 0x3093;
public static final int EGL_MULTISAMPLE_RESOLVE            = 0x3099;
public static final int EGL_BACK_BUFFER                    = 0x3084;
public static final int EGL_SINGLE_BUFFER                  = 0x3085;
public static final int EGL_VG_COLORSPACE_sRGB             = 0x3089;
public static final int EGL_VG_COLORSPACE_LINEAR           = 0x308A;
public static final int EGL_VG_ALPHA_FORMAT_NONPRE         = 0x308B;
public static final int EGL_VG_ALPHA_FORMAT_PRE            = 0x308C;
public static final int EGL_DISPLAY_SCALING                = 10000;
public static final int EGL_BUFFER_PRESERVED               = 0x3094;
public static final int EGL_BUFFER_DESTROYED               = 0x3095;
public static final int EGL_OPENVG_IMAGE                   = 0x3096;
public static final int EGL_CONTEXT_CLIENT_TYPE            = 0x3097;
public static final int EGL_CONTEXT_CLIENT_VERSION         = 0x3098;
public static final int EGL_MULTISAMPLE_RESOLVE_DEFAULT    = 0x309A;
public static final int EGL_MULTISAMPLE_RESOLVE_BOX        = 0x309B;
public static final int EGL_OPENGL_ES_API                  = 0x30A0;
public static final int EGL_OPENVG_API                     = 0x30A1;
public static final int EGL_OPENGL_API                     = 0x30A2;
public static final int EGL_DRAW                           = 0x3059;
public static final int EGL_READ                           = 0x305A;
public static final int EGL_CORE_NATIVE_ENGINE             = 0x305B;

    native private static void _nativeClassInit();
    static {
        _nativeClassInit();
    }
    // C function EGLint eglGetError ( void )

    public static native int eglGetError(
    );

    // C function EGLDisplay eglGetDisplay ( EGLNativeDisplayType display_id )

    public static native EGLDisplay eglGetDisplay(
        int display_id
    );

    /**
     * {@hide}
     */
    public static native EGLDisplay eglGetDisplay(
        long display_id
    );

    // C function EGLBoolean eglInitialize ( EGLDisplay dpy, EGLint *major, EGLint *minor )

    public static native boolean eglInitialize(
        EGLDisplay dpy,
        int[] major,
        int majorOffset,
        int[] minor,
        int minorOffset
    );

    // C function EGLBoolean eglTerminate ( EGLDisplay dpy )

    public static native boolean eglTerminate(
        EGLDisplay dpy
    );

    // C function const char * eglQueryString ( EGLDisplay dpy, EGLint name )

    public static native String eglQueryString(
        EGLDisplay dpy,
        int name
    );
    // C function EGLBoolean eglGetConfigs ( EGLDisplay dpy, EGLConfig *configs, EGLint config_size, EGLint *num_config )

    public static native boolean eglGetConfigs(
        EGLDisplay dpy,
        EGLConfig[] configs,
        int configsOffset,
        int config_size,
        int[] num_config,
        int num_configOffset
    );

    // C function EGLBoolean eglChooseConfig ( EGLDisplay dpy, const EGLint *attrib_list, EGLConfig *configs, EGLint config_size, EGLint *num_config )

    public static native boolean eglChooseConfig(
        EGLDisplay dpy,
        int[] attrib_list,
        int attrib_listOffset,
        EGLConfig[] configs,
        int configsOffset,
        int config_size,
        int[] num_config,
        int num_configOffset
    );

    // C function EGLBoolean eglGetConfigAttrib ( EGLDisplay dpy, EGLConfig config, EGLint attribute, EGLint *value )

    public static native boolean eglGetConfigAttrib(
        EGLDisplay dpy,
        EGLConfig config,
        int attribute,
        int[] value,
        int offset
    );

    // C function EGLSurface eglCreateWindowSurface ( EGLDisplay dpy, EGLConfig config, EGLNativeWindowType win, const EGLint *attrib_list )

    private static native EGLSurface _eglCreateWindowSurface(
        EGLDisplay dpy,
        EGLConfig config,
        Object win,
        int[] attrib_list,
        int offset
    );

    private static native EGLSurface _eglCreateWindowSurfaceTexture(
        EGLDisplay dpy,
        EGLConfig config,
        Object win,
        int[] attrib_list,
        int offset
    );

    public static EGLSurface eglCreateWindowSurface(EGLDisplay dpy,
        EGLConfig config,
        Object win,
        int[] attrib_list,
        int offset
    ){
        Surface sur = null;
        if (win instanceof SurfaceView) {
            SurfaceView surfaceView = (SurfaceView)win;
            sur = surfaceView.getHolder().getSurface();
        } else if (win instanceof SurfaceHolder) {
            SurfaceHolder holder = (SurfaceHolder)win;
            sur = holder.getSurface();
        } else if (win instanceof Surface) {
            sur = (Surface) win;
        }

        EGLSurface surface;
        if (sur != null) {
            surface = _eglCreateWindowSurface(dpy, config, sur, attrib_list, offset);
        } else if (win instanceof SurfaceTexture) {
            surface = _eglCreateWindowSurfaceTexture(dpy, config,
                    win, attrib_list, offset);
        } else {
            throw new java.lang.UnsupportedOperationException(
                "eglCreateWindowSurface() can only be called with an instance of " +
                "Surface, SurfaceView, SurfaceTexture or SurfaceHolder at the moment, " +
                "this will be fixed later.");
        }

        return surface;
    }
    // C function EGLSurface eglCreatePbufferSurface ( EGLDisplay dpy, EGLConfig config, const EGLint *attrib_list )

    public static native EGLSurface eglCreatePbufferSurface(
        EGLDisplay dpy,
        EGLConfig config,
        int[] attrib_list,
        int offset
    );

    // C function EGLSurface eglCreatePixmapSurface ( EGLDisplay dpy, EGLConfig config, EGLNativePixmapType pixmap, const EGLint *attrib_list )

    @Deprecated
    public static native EGLSurface eglCreatePixmapSurface(
        EGLDisplay dpy,
        EGLConfig config,
        int pixmap,
        int[] attrib_list,
        int offset
    );

    // C function EGLBoolean eglDestroySurface ( EGLDisplay dpy, EGLSurface surface )

    public static native boolean eglDestroySurface(
        EGLDisplay dpy,
        EGLSurface surface
    );

    // C function EGLBoolean eglQuerySurface ( EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint *value )

    public static native boolean eglQuerySurface(
        EGLDisplay dpy,
        EGLSurface surface,
        int attribute,
        int[] value,
        int offset
    );

    // C function EGLBoolean eglBindAPI ( EGLenum api )

    public static native boolean eglBindAPI(
        int api
    );

    // C function EGLenum eglQueryAPI ( void )

    public static native int eglQueryAPI(
    );

    // C function EGLBoolean eglWaitClient ( void )

    public static native boolean eglWaitClient(
    );

    // C function EGLBoolean eglReleaseThread ( void )

    public static native boolean eglReleaseThread(
    );

    // C function EGLSurface eglCreatePbufferFromClientBuffer ( EGLDisplay dpy, EGLenum buftype, EGLClientBuffer buffer, EGLConfig config, const EGLint *attrib_list )
    // TODO Deprecate the below method
    public static native EGLSurface eglCreatePbufferFromClientBuffer(
        EGLDisplay dpy,
        int buftype,
        int buffer,
        EGLConfig config,
        int[] attrib_list,
        int offset
    );
    // TODO Unhide the below method
    /**
     * {@hide}
     */
    public static native EGLSurface eglCreatePbufferFromClientBuffer(
        EGLDisplay dpy,
        int buftype,
        long buffer,
        EGLConfig config,
        int[] attrib_list,
        int offset
    );

    // C function EGLBoolean eglSurfaceAttrib ( EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint value )

    public static native boolean eglSurfaceAttrib(
        EGLDisplay dpy,
        EGLSurface surface,
        int attribute,
        int value
    );

    // C function EGLBoolean eglBindTexImage ( EGLDisplay dpy, EGLSurface surface, EGLint buffer )

    public static native boolean eglBindTexImage(
        EGLDisplay dpy,
        EGLSurface surface,
        int buffer
    );

    // C function EGLBoolean eglReleaseTexImage ( EGLDisplay dpy, EGLSurface surface, EGLint buffer )

    public static native boolean eglReleaseTexImage(
        EGLDisplay dpy,
        EGLSurface surface,
        int buffer
    );

    // C function EGLBoolean eglSwapInterval ( EGLDisplay dpy, EGLint interval )

    public static native boolean eglSwapInterval(
        EGLDisplay dpy,
        int interval
    );

    // C function EGLContext eglCreateContext ( EGLDisplay dpy, EGLConfig config, EGLContext share_context, const EGLint *attrib_list )

    public static native EGLContext eglCreateContext(
        EGLDisplay dpy,
        EGLConfig config,
        EGLContext share_context,
        int[] attrib_list,
        int offset
    );

    // C function EGLBoolean eglDestroyContext ( EGLDisplay dpy, EGLContext ctx )

    public static native boolean eglDestroyContext(
        EGLDisplay dpy,
        EGLContext ctx
    );

    // C function EGLBoolean eglMakeCurrent ( EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx )

    public static native boolean eglMakeCurrent(
        EGLDisplay dpy,
        EGLSurface draw,
        EGLSurface read,
        EGLContext ctx
    );

    // C function EGLContext eglGetCurrentContext ( void )

    public static native EGLContext eglGetCurrentContext(
    );

    // C function EGLSurface eglGetCurrentSurface ( EGLint readdraw )

    public static native EGLSurface eglGetCurrentSurface(
        int readdraw
    );

    // C function EGLDisplay eglGetCurrentDisplay ( void )

    public static native EGLDisplay eglGetCurrentDisplay(
    );

    // C function EGLBoolean eglQueryContext ( EGLDisplay dpy, EGLContext ctx, EGLint attribute, EGLint *value )

    public static native boolean eglQueryContext(
        EGLDisplay dpy,
        EGLContext ctx,
        int attribute,
        int[] value,
        int offset
    );

    // C function EGLBoolean eglWaitGL ( void )

    public static native boolean eglWaitGL(
    );

    // C function EGLBoolean eglWaitNative ( EGLint engine )

    public static native boolean eglWaitNative(
        int engine
    );

    // C function EGLBoolean eglSwapBuffers ( EGLDisplay dpy, EGLSurface surface )

    public static native boolean eglSwapBuffers(
        EGLDisplay dpy,
        EGLSurface surface
    );

    // C function EGLBoolean eglCopyBuffers ( EGLDisplay dpy, EGLSurface surface, EGLNativePixmapType target )

    public static native boolean eglCopyBuffers(
        EGLDisplay dpy,
        EGLSurface surface,
        int target
    );

}
