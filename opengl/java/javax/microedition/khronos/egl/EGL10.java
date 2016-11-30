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

package javax.microedition.khronos.egl;

import java.lang.String;

public interface EGL10 extends EGL {
    int EGL_SUCCESS                     = 0x3000;
    int EGL_NOT_INITIALIZED             = 0x3001;
    int EGL_BAD_ACCESS                  = 0x3002;
    int EGL_BAD_ALLOC                   = 0x3003;
    int EGL_BAD_ATTRIBUTE               = 0x3004;
    int EGL_BAD_CONFIG                  = 0x3005;
    int EGL_BAD_CONTEXT                 = 0x3006;
    int EGL_BAD_CURRENT_SURFACE         = 0x3007;
    int EGL_BAD_DISPLAY                 = 0x3008;
    int EGL_BAD_MATCH                   = 0x3009;
    int EGL_BAD_NATIVE_PIXMAP           = 0x300A;
    int EGL_BAD_NATIVE_WINDOW           = 0x300B;
    int EGL_BAD_PARAMETER               = 0x300C;
    int EGL_BAD_SURFACE                 = 0x300D;
    int EGL_BUFFER_SIZE                 = 0x3020;
    int EGL_ALPHA_SIZE                  = 0x3021;
    int EGL_BLUE_SIZE                   = 0x3022;
    int EGL_GREEN_SIZE                  = 0x3023;
    int EGL_RED_SIZE                    = 0x3024;
    int EGL_DEPTH_SIZE                  = 0x3025;
    int EGL_STENCIL_SIZE                = 0x3026;
    int EGL_CONFIG_CAVEAT               = 0x3027;
    int EGL_CONFIG_ID                   = 0x3028;
    int EGL_LEVEL                       = 0x3029;
    int EGL_MAX_PBUFFER_HEIGHT          = 0x302A;
    int EGL_MAX_PBUFFER_PIXELS          = 0x302B;
    int EGL_MAX_PBUFFER_WIDTH           = 0x302C;
    int EGL_NATIVE_RENDERABLE           = 0x302D;
    int EGL_NATIVE_VISUAL_ID            = 0x302E;
    int EGL_NATIVE_VISUAL_TYPE          = 0x302F;
    int EGL_SAMPLES                     = 0x3031;
    int EGL_SAMPLE_BUFFERS              = 0x3032;
    int EGL_SURFACE_TYPE                = 0x3033;
    int EGL_TRANSPARENT_TYPE            = 0x3034;
    int EGL_TRANSPARENT_BLUE_VALUE      = 0x3035;
    int EGL_TRANSPARENT_GREEN_VALUE     = 0x3036;
    int EGL_TRANSPARENT_RED_VALUE       = 0x3037;
    int EGL_NONE                        = 0x3038;
    int EGL_LUMINANCE_SIZE              = 0x303D;
    int EGL_ALPHA_MASK_SIZE             = 0x303E;
    int EGL_COLOR_BUFFER_TYPE           = 0x303F;
    int EGL_RENDERABLE_TYPE             = 0x3040;
    int EGL_SLOW_CONFIG                 = 0x3050;
    int EGL_NON_CONFORMANT_CONFIG       = 0x3051;
    int EGL_TRANSPARENT_RGB             = 0x3052;
    int EGL_RGB_BUFFER                  = 0x308E;
    int EGL_LUMINANCE_BUFFER            = 0x308F;
    int EGL_VENDOR                      = 0x3053;
    int EGL_VERSION                     = 0x3054;
    int EGL_EXTENSIONS                  = 0x3055;
    int EGL_HEIGHT                      = 0x3056;
    int EGL_WIDTH                       = 0x3057;
    int EGL_LARGEST_PBUFFER             = 0x3058;
    int EGL_RENDER_BUFFER               = 0x3086;
    int EGL_COLORSPACE                  = 0x3087;
    int EGL_ALPHA_FORMAT                = 0x3088;
    int EGL_HORIZONTAL_RESOLUTION       = 0x3090;
    int EGL_VERTICAL_RESOLUTION         = 0x3091;
    int EGL_PIXEL_ASPECT_RATIO          = 0x3092;
    int EGL_SINGLE_BUFFER               = 0x3085;
    int EGL_CORE_NATIVE_ENGINE          = 0x305B;
    int EGL_DRAW                        = 0x3059;
    int EGL_READ                        = 0x305A;

    int EGL_DONT_CARE                   = -1;

    int EGL_PBUFFER_BIT                 = 0x01;
    int EGL_PIXMAP_BIT                  = 0x02;
    int EGL_WINDOW_BIT                  = 0x04;

    Object     EGL_DEFAULT_DISPLAY = null;
    EGLDisplay EGL_NO_DISPLAY = new com.google.android.gles_jni.EGLDisplayImpl(0);
    EGLContext EGL_NO_CONTEXT = new com.google.android.gles_jni.EGLContextImpl(0);
    EGLSurface EGL_NO_SURFACE = new com.google.android.gles_jni.EGLSurfaceImpl(0);
    
    boolean     eglChooseConfig(EGLDisplay display, int[] attrib_list, EGLConfig[] configs, int config_size, int[] num_config);
    boolean     eglCopyBuffers(EGLDisplay display, EGLSurface surface, Object native_pixmap);
    EGLContext  eglCreateContext(EGLDisplay display, EGLConfig config, EGLContext share_context, int[] attrib_list);
    EGLSurface  eglCreatePbufferSurface(EGLDisplay display, EGLConfig config, int[] attrib_list);
    @Deprecated
    EGLSurface  eglCreatePixmapSurface(EGLDisplay display, EGLConfig config, Object native_pixmap, int[] attrib_list);
    EGLSurface  eglCreateWindowSurface(EGLDisplay display, EGLConfig config, Object native_window, int[] attrib_list);
    boolean     eglDestroyContext(EGLDisplay display, EGLContext context);
    boolean     eglDestroySurface(EGLDisplay display, EGLSurface surface);
    boolean     eglGetConfigAttrib(EGLDisplay display, EGLConfig config, int attribute, int[] value);
    boolean     eglGetConfigs(EGLDisplay display, EGLConfig[] configs, int config_size, int[] num_config);
    EGLContext  eglGetCurrentContext();
    EGLDisplay  eglGetCurrentDisplay();
    EGLSurface  eglGetCurrentSurface(int readdraw);
    EGLDisplay  eglGetDisplay(Object native_display);
    int         eglGetError();
    boolean     eglInitialize(EGLDisplay display, int[] major_minor);
    boolean     eglMakeCurrent(EGLDisplay display, EGLSurface draw, EGLSurface read, EGLContext context);
    boolean     eglQueryContext(EGLDisplay display, EGLContext context, int attribute, int[] value);
    String      eglQueryString(EGLDisplay display, int name);
    boolean     eglQuerySurface(EGLDisplay display, EGLSurface surface, int attribute, int[] value);
    /** @hide **/
    boolean     eglReleaseThread();
    boolean     eglSwapBuffers(EGLDisplay display, EGLSurface surface);
    boolean     eglTerminate(EGLDisplay display);
    boolean     eglWaitGL();
    boolean     eglWaitNative(int engine, Object bindTarget);
}
