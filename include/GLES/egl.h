/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_EGL_H
#define ANDROID_EGL_H

#include <GLES/gl.h>
#include <GLES/egltypes.h>
#include <GLES/eglnatives.h>

#ifdef __cplusplus
extern "C" {
#endif

#define EGL_VERSION_1_0         1
#define EGL_VERSION_1_1         1
#define EGL_VERSION_1_2         1

#define EGL_FALSE               0
#define EGL_TRUE                1

/* Errors */
#define EGL_SUCCESS                     0x3000
#define EGL_NOT_INITIALIZED             0x3001
#define EGL_BAD_ACCESS                  0x3002
#define EGL_BAD_ALLOC                   0x3003
#define EGL_BAD_ATTRIBUTE               0x3004
#define EGL_BAD_CONFIG                  0x3005
#define EGL_BAD_CONTEXT                 0x3006
#define EGL_BAD_CURRENT_SURFACE         0x3007
#define EGL_BAD_DISPLAY                 0x3008
#define EGL_BAD_MATCH                   0x3009
#define EGL_BAD_NATIVE_PIXMAP           0x300A
#define EGL_BAD_NATIVE_WINDOW           0x300B
#define EGL_BAD_PARAMETER               0x300C
#define EGL_BAD_SURFACE                 0x300D
#define EGL_CONTEXT_LOST                0x300E

/* Config attributes */
#define EGL_BUFFER_SIZE                 0x3020
#define EGL_ALPHA_SIZE                  0x3021
#define EGL_BLUE_SIZE                   0x3022
#define EGL_GREEN_SIZE                  0x3023
#define EGL_RED_SIZE                    0x3024
#define EGL_DEPTH_SIZE                  0x3025
#define EGL_STENCIL_SIZE                0x3026
#define EGL_CONFIG_CAVEAT               0x3027
#define EGL_CONFIG_ID                   0x3028
#define EGL_LEVEL                       0x3029
#define EGL_MAX_PBUFFER_HEIGHT          0x302A
#define EGL_MAX_PBUFFER_PIXELS          0x302B
#define EGL_MAX_PBUFFER_WIDTH           0x302C
#define EGL_NATIVE_RENDERABLE           0x302D
#define EGL_NATIVE_VISUAL_ID            0x302E
#define EGL_NATIVE_VISUAL_TYPE          0x302F
#define EGL_SAMPLES                     0x3031
#define EGL_SAMPLE_BUFFERS              0x3032
#define EGL_SURFACE_TYPE                0x3033
#define EGL_TRANSPARENT_TYPE            0x3034
#define EGL_TRANSPARENT_BLUE_VALUE      0x3035
#define EGL_TRANSPARENT_GREEN_VALUE     0x3036
#define EGL_TRANSPARENT_RED_VALUE       0x3037
#define EGL_NONE                        0x3038
#define EGL_BIND_TO_TEXTURE_RGB         0x3039
#define EGL_BIND_TO_TEXTURE_RGBA        0x303A
#define EGL_MIN_SWAP_INTERVAL           0x303B
#define EGL_MAX_SWAP_INTERVAL           0x303C
#define EGL_LUMINANCE_SIZE              0x303D
#define EGL_ALPHA_MASK_SIZE             0x303E
#define EGL_COLOR_BUFFER_TYPE           0x303F
#define EGL_RENDERABLE_TYPE             0x3040

/* Config values */
#define EGL_DONT_CARE                   ((EGLint)-1)

#define EGL_SLOW_CONFIG                 0x3050
#define EGL_NON_CONFORMANT_CONFIG       0x3051
#define EGL_TRANSPARENT_RGB             0x3052
#define EGL_NO_TEXTURE                  0x305C
#define EGL_TEXTURE_RGB                 0x305D
#define EGL_TEXTURE_RGBA                0x305E
#define EGL_TEXTURE_2D                  0x305F
#define EGL_RGB_BUFFER                  0x308E
#define EGL_LUMINANCE_BUFFER            0x308F

/* Config attribute mask bits */
#define EGL_PBUFFER_BIT                 0x01
#define EGL_PIXMAP_BIT                  0x02
#define EGL_WINDOW_BIT                  0x04
#define EGL_OPENGL_ES_BIT               0x01
#define EGL_OPENVG_BIT                  0x02

/* String names */
#define EGL_VENDOR                      0x3053
#define EGL_VERSION                     0x3054
#define EGL_EXTENSIONS                  0x3055
#define EGL_CLIENT_APIS                 0x308D

/* Surface attributes */
#define EGL_HEIGHT                      0x3056
#define EGL_WIDTH                       0x3057
#define EGL_LARGEST_PBUFFER             0x3058
#define EGL_TEXTURE_FORMAT              0x3080
#define EGL_TEXTURE_TARGET              0x3081
#define EGL_MIPMAP_TEXTURE              0x3082
#define EGL_MIPMAP_LEVEL                0x3083
#define EGL_RENDER_BUFFER               0x3086
#define EGL_COLORSPACE                  0x3087
#define EGL_ALPHA_FORMAT                0x3088
#define EGL_HORIZONTAL_RESOLUTION       0x3090
#define EGL_VERTICAL_RESOLUTION         0x3091
#define EGL_PIXEL_ASPECT_RATIO          0x3092
#define EGL_SWAP_BEHAVIOR               0x3093

#define EGL_BACK_BUFFER                 0x3084
#define EGL_SINGLE_BUFFER               0x3085

#define EGL_DISPLAY_SCALING             10000

#define EGL_UNKNOWN                     ((EGLint)-1)

/* Back buffer swap behaviors */
#define EGL_BUFFER_PRESERVED            0x3094
#define EGL_BUFFER_DESTROYED            0x3095

/* CreatePbufferFromClientBuffer buffer types */
#define EGL_OPENVG_IMAGE                0x3096

/* QueryContext targets */
#define EGL_CONTEXT_CLIENT_TYPE         0x3097

/* BindAPI/QueryAPI targets */
#define EGL_OPENGL_ES_API               0x30A0
#define EGL_OPENVG_API                  0x30A1

/* WaitNative engines */
#define EGL_CORE_NATIVE_ENGINE          0x305B

/* Current surfaces */
#define EGL_DRAW                        0x3059
#define EGL_READ                        0x305A


EGLDisplay eglGetDisplay(NativeDisplayType display);
EGLBoolean eglInitialize(EGLDisplay dpy, EGLint *major, EGLint *minor);
EGLBoolean eglTerminate(EGLDisplay dpy);

EGLBoolean eglGetConfigs(   EGLDisplay dpy,
                            EGLConfig *configs,
                            EGLint config_size, EGLint *num_config);

EGLBoolean eglChooseConfig( EGLDisplay dpy, const EGLint *attrib_list,
                            EGLConfig *configs, EGLint config_size,
                            EGLint *num_config);

EGLBoolean eglGetConfigAttrib(  EGLDisplay dpy, EGLConfig config,
                                EGLint attribute, EGLint *value);

EGLSurface eglCreateWindowSurface(  EGLDisplay dpy, EGLConfig config,
                                    NativeWindowType window,
                                    const EGLint *attrib_list);

EGLSurface eglCreatePixmapSurface(  EGLDisplay dpy, EGLConfig config,
                                    NativePixmapType pixmap,
                                    const EGLint *attrib_list);

EGLSurface eglCreatePbufferSurface( EGLDisplay dpy, EGLConfig config,
                                    const EGLint *attrib_list);
                                    
EGLBoolean eglDestroySurface(EGLDisplay dpy, EGLSurface surface);

EGLBoolean eglQuerySurface( EGLDisplay dpy, EGLSurface surface,
                            EGLint attribute, EGLint *value);

EGLContext eglCreateContext(EGLDisplay dpy, EGLConfig config,
                            EGLContext share_list, const EGLint *attrib_list);

EGLBoolean eglDestroyContext(EGLDisplay dpy, EGLContext ctx);

EGLBoolean eglMakeCurrent(  EGLDisplay dpy, EGLSurface draw,
                            EGLSurface read, EGLContext ctx);

EGLContext eglGetCurrentContext(void);
EGLSurface eglGetCurrentSurface(EGLint readdraw);
EGLDisplay eglGetCurrentDisplay(void);
EGLBoolean eglQueryContext( EGLDisplay dpy, EGLContext ctx,
                            EGLint attribute, EGLint *value);

EGLBoolean eglWaitGL(void);
EGLBoolean eglWaitNative(EGLint engine);
EGLBoolean eglSwapBuffers(EGLDisplay dpy, EGLSurface draw);
EGLBoolean eglCopyBuffers(  EGLDisplay dpy, EGLSurface surface,
                            NativePixmapType target);

EGLint eglGetError(void);
const char* eglQueryString(EGLDisplay dpy, EGLint name);
void (*eglGetProcAddress (const char *procname))();

/* ----------------------------------------------------------------------------
 * EGL 1.1
 * ----------------------------------------------------------------------------
 */

EGLBoolean eglSurfaceAttrib(
        EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint value);
EGLBoolean eglBindTexImage(
        EGLDisplay dpy, EGLSurface surface, EGLint buffer);
EGLBoolean eglReleaseTexImage(
        EGLDisplay dpy, EGLSurface surface, EGLint buffer);

EGLBoolean eglSwapInterval(EGLDisplay dpy, EGLint interval);

/* ----------------------------------------------------------------------------
 * EGL 1.2
 * ----------------------------------------------------------------------------
 */

EGLBoolean eglBindAPI(EGLenum api);
EGLenum eglQueryAPI(void);
EGLBoolean eglWaitClient(void);
EGLBoolean eglReleaseThread(void);
EGLSurface eglCreatePbufferFromClientBuffer(
          EGLDisplay dpy, EGLenum buftype, EGLClientBuffer buffer,
          EGLConfig config, const EGLint *attrib_list);

/* ----------------------------------------------------------------------------
 * Android extentions
 * ----------------------------------------------------------------------------
 */

EGLBoolean eglSwapRectangleANDROID(EGLDisplay dpy, EGLSurface draw,
        EGLint l, EGLint t, EGLint w, EGLint h);

EGLBoolean eglCopyFrontToBackANDROID(EGLDisplay dpy,
        EGLSurface surface,
        EGLint l, EGLint t, EGLint w, EGLint h);

const char* eglQueryStringConfigANDROID(
        EGLDisplay dpy, EGLConfig config, EGLint name);

void* eglGetRenderBufferAddressANDROID(EGLDisplay dpy, EGLSurface surface);

EGLBoolean eglCopyBitsANDROID(EGLDisplay dpy,
        NativeWindowType draw, EGLint x, EGLint y,
        NativeWindowType read,
        EGLint crop_x, EGLint crop_y, EGLint crop_w, EGLint crop_h,
        EGLint flags);


#ifdef __cplusplus
}
#endif


#endif /*ANDROID_EGL_H*/
