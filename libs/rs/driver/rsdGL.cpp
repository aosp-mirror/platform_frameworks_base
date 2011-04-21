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

#include <ui/FramebufferNativeWindow.h>
#include <ui/PixelFormat.h>
#include <ui/EGLUtils.h>
#include <ui/egl/android_natives.h>

#include <sys/types.h>
#include <sys/resource.h>
#include <sched.h>

#include <cutils/properties.h>

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

//#include <cutils/sched_policy.h>
//#include <sys/syscall.h>
#include <string.h>


#include "rsdCore.h"
#include "rsdGL.h"

#include <malloc.h>
#include "rsContext.h"

using namespace android;
using namespace android::renderscript;

static int32_t gGLContextCount = 0;

static void checkEglError(const char* op, EGLBoolean returnVal = EGL_TRUE) {
    if (returnVal != EGL_TRUE) {
        fprintf(stderr, "%s() returned %d\n", op, returnVal);
    }

    for (EGLint error = eglGetError(); error != EGL_SUCCESS; error
            = eglGetError()) {
        fprintf(stderr, "after %s() eglError %s (0x%x)\n", op, EGLUtils::strerror(error),
                error);
    }
}

static void printEGLConfiguration(EGLDisplay dpy, EGLConfig config) {

#define X(VAL) {VAL, #VAL}
    struct {EGLint attribute; const char* name;} names[] = {
    X(EGL_BUFFER_SIZE),
    X(EGL_ALPHA_SIZE),
    X(EGL_BLUE_SIZE),
    X(EGL_GREEN_SIZE),
    X(EGL_RED_SIZE),
    X(EGL_DEPTH_SIZE),
    X(EGL_STENCIL_SIZE),
    X(EGL_CONFIG_CAVEAT),
    X(EGL_CONFIG_ID),
    X(EGL_LEVEL),
    X(EGL_MAX_PBUFFER_HEIGHT),
    X(EGL_MAX_PBUFFER_PIXELS),
    X(EGL_MAX_PBUFFER_WIDTH),
    X(EGL_NATIVE_RENDERABLE),
    X(EGL_NATIVE_VISUAL_ID),
    X(EGL_NATIVE_VISUAL_TYPE),
    X(EGL_SAMPLES),
    X(EGL_SAMPLE_BUFFERS),
    X(EGL_SURFACE_TYPE),
    X(EGL_TRANSPARENT_TYPE),
    X(EGL_TRANSPARENT_RED_VALUE),
    X(EGL_TRANSPARENT_GREEN_VALUE),
    X(EGL_TRANSPARENT_BLUE_VALUE),
    X(EGL_BIND_TO_TEXTURE_RGB),
    X(EGL_BIND_TO_TEXTURE_RGBA),
    X(EGL_MIN_SWAP_INTERVAL),
    X(EGL_MAX_SWAP_INTERVAL),
    X(EGL_LUMINANCE_SIZE),
    X(EGL_ALPHA_MASK_SIZE),
    X(EGL_COLOR_BUFFER_TYPE),
    X(EGL_RENDERABLE_TYPE),
    X(EGL_CONFORMANT),
   };
#undef X

    for (size_t j = 0; j < sizeof(names) / sizeof(names[0]); j++) {
        EGLint value = -1;
        EGLint returnVal = eglGetConfigAttrib(dpy, config, names[j].attribute, &value);
        EGLint error = eglGetError();
        if (returnVal && error == EGL_SUCCESS) {
            LOGV(" %s: %d (0x%x)", names[j].name, value, value);
        }
    }
}

static void DumpDebug(RsHal *dc) {
    LOGE(" EGL ver %i %i", dc->gl.egl.majorVersion, dc->gl.egl.minorVersion);
    LOGE(" EGL context %p  surface %p,  Display=%p", dc->gl.egl.context, dc->gl.egl.surface,
         dc->gl.egl.display);
    LOGE(" GL vendor: %s", dc->gl.gl.vendor);
    LOGE(" GL renderer: %s", dc->gl.gl.renderer);
    LOGE(" GL Version: %s", dc->gl.gl.version);
    LOGE(" GL Extensions: %s", dc->gl.gl.extensions);
    LOGE(" GL int Versions %i %i", dc->gl.gl.majorVersion, dc->gl.gl.minorVersion);

    LOGV("MAX Textures %i, %i  %i", dc->gl.gl.maxVertexTextureUnits,
         dc->gl.gl.maxFragmentTextureImageUnits, dc->gl.gl.maxTextureImageUnits);
    LOGV("MAX Attribs %i", dc->gl.gl.maxVertexAttribs);
    LOGV("MAX Uniforms %i, %i", dc->gl.gl.maxVertexUniformVectors,
         dc->gl.gl.maxFragmentUniformVectors);
    LOGV("MAX Varyings %i", dc->gl.gl.maxVaryingVectors);
}

void rsdGLShutdown(const Context *rsc) {
    RsHal *dc = (RsHal *)rsc->mHal.drv;

    LOGV("%p, deinitEGL", rsc);

    if (dc->gl.egl.context != EGL_NO_CONTEXT) {
        eglMakeCurrent(dc->gl.egl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(dc->gl.egl.display, dc->gl.egl.surfaceDefault);
        if (dc->gl.egl.surface != EGL_NO_SURFACE) {
            eglDestroySurface(dc->gl.egl.display, dc->gl.egl.surface);
        }
        eglDestroyContext(dc->gl.egl.display, dc->gl.egl.context);
        checkEglError("eglDestroyContext");
    }

    gGLContextCount--;
    if (!gGLContextCount) {
        eglTerminate(dc->gl.egl.display);
    }
}

bool rsdGLInit(const Context *rsc) {
    RsHal *dc = (RsHal *)rsc->mHal.drv;

    dc->gl.egl.numConfigs = -1;
    EGLint configAttribs[128];
    EGLint *configAttribsPtr = configAttribs;
    EGLint context_attribs2[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };

    memset(configAttribs, 0, sizeof(configAttribs));

    configAttribsPtr[0] = EGL_SURFACE_TYPE;
    configAttribsPtr[1] = EGL_WINDOW_BIT;
    configAttribsPtr += 2;

    configAttribsPtr[0] = EGL_RENDERABLE_TYPE;
    configAttribsPtr[1] = EGL_OPENGL_ES2_BIT;
    configAttribsPtr += 2;

    if (rsc->mUserSurfaceConfig.depthMin > 0) {
        configAttribsPtr[0] = EGL_DEPTH_SIZE;
        configAttribsPtr[1] = rsc->mUserSurfaceConfig.depthMin;
        configAttribsPtr += 2;
    }

    if (rsc->mDev->mForceSW) {
        configAttribsPtr[0] = EGL_CONFIG_CAVEAT;
        configAttribsPtr[1] = EGL_SLOW_CONFIG;
        configAttribsPtr += 2;
    }

    configAttribsPtr[0] = EGL_NONE;
    rsAssert(configAttribsPtr < (configAttribs + (sizeof(configAttribs) / sizeof(EGLint))));

    LOGV("%p initEGL start", rsc);
    dc->gl.egl.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    checkEglError("eglGetDisplay");

    eglInitialize(dc->gl.egl.display, &dc->gl.egl.majorVersion, &dc->gl.egl.minorVersion);
    checkEglError("eglInitialize");

    PixelFormat pf = PIXEL_FORMAT_RGBA_8888;
    if (rsc->mUserSurfaceConfig.alphaMin == 0) {
        pf = PIXEL_FORMAT_RGBX_8888;
    }

    status_t err = EGLUtils::selectConfigForPixelFormat(dc->gl.egl.display, configAttribs,
                                                        pf, &dc->gl.egl.config);
    if (err) {
       LOGE("%p, couldn't find an EGLConfig matching the screen format\n", rsc);
    }
    //if (props.mLogVisual) {
    if (0) {
        printEGLConfiguration(dc->gl.egl.display, dc->gl.egl.config);
    }
    //}

    dc->gl.egl.context = eglCreateContext(dc->gl.egl.display, dc->gl.egl.config,
                                          EGL_NO_CONTEXT, context_attribs2);
    checkEglError("eglCreateContext");
    if (dc->gl.egl.context == EGL_NO_CONTEXT) {
        LOGE("%p, eglCreateContext returned EGL_NO_CONTEXT", rsc);
        return false;
    }
    gGLContextCount++;


    EGLint pbuffer_attribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    dc->gl.egl.surfaceDefault = eglCreatePbufferSurface(dc->gl.egl.display, dc->gl.egl.config,
                                                        pbuffer_attribs);
    checkEglError("eglCreatePbufferSurface");
    if (dc->gl.egl.surfaceDefault == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface returned EGL_NO_SURFACE");
        rsdGLShutdown(rsc);
        return false;
    }

    EGLBoolean ret = eglMakeCurrent(dc->gl.egl.display, dc->gl.egl.surfaceDefault,
                                    dc->gl.egl.surfaceDefault, dc->gl.egl.context);
    if (ret == EGL_FALSE) {
        LOGE("eglMakeCurrent returned EGL_FALSE");
        checkEglError("eglMakeCurrent", ret);
        rsdGLShutdown(rsc);
        return false;
    }

    dc->gl.gl.version = glGetString(GL_VERSION);
    dc->gl.gl.vendor = glGetString(GL_VENDOR);
    dc->gl.gl.renderer = glGetString(GL_RENDERER);
    dc->gl.gl.extensions = glGetString(GL_EXTENSIONS);

    //LOGV("EGL Version %i %i", mEGL.mMajorVersion, mEGL.mMinorVersion);
    //LOGV("GL Version %s", mGL.mVersion);
    //LOGV("GL Vendor %s", mGL.mVendor);
    //LOGV("GL Renderer %s", mGL.mRenderer);
    //LOGV("GL Extensions %s", mGL.mExtensions);

    const char *verptr = NULL;
    if (strlen((const char *)dc->gl.gl.version) > 9) {
        if (!memcmp(dc->gl.gl.version, "OpenGL ES-CM", 12)) {
            verptr = (const char *)dc->gl.gl.version + 12;
        }
        if (!memcmp(dc->gl.gl.version, "OpenGL ES ", 10)) {
            verptr = (const char *)dc->gl.gl.version + 9;
        }
    }

    if (!verptr) {
        LOGE("Error, OpenGL ES Lite not supported");
        rsdGLShutdown(rsc);
        return false;
    } else {
        sscanf(verptr, " %i.%i", &dc->gl.gl.majorVersion, &dc->gl.gl.minorVersion);
    }

    glGetIntegerv(GL_MAX_VERTEX_ATTRIBS, &dc->gl.gl.maxVertexAttribs);
    glGetIntegerv(GL_MAX_VERTEX_UNIFORM_VECTORS, &dc->gl.gl.maxVertexUniformVectors);
    glGetIntegerv(GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS, &dc->gl.gl.maxVertexTextureUnits);

    glGetIntegerv(GL_MAX_VARYING_VECTORS, &dc->gl.gl.maxVaryingVectors);
    glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &dc->gl.gl.maxTextureImageUnits);

    glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, &dc->gl.gl.maxFragmentTextureImageUnits);
    glGetIntegerv(GL_MAX_FRAGMENT_UNIFORM_VECTORS, &dc->gl.gl.maxFragmentUniformVectors);

    dc->gl.gl.OES_texture_npot = NULL != strstr((const char *)dc->gl.gl.extensions,
                                                "GL_OES_texture_npot");
    dc->gl.gl.GL_IMG_texture_npot = NULL != strstr((const char *)dc->gl.gl.extensions,
                                                   "GL_IMG_texture_npot");
    dc->gl.gl.GL_NV_texture_npot_2D_mipmap = NULL != strstr((const char *)dc->gl.gl.extensions,
                                                            "GL_NV_texture_npot_2D_mipmap");
    dc->gl.gl.EXT_texture_max_aniso = 1.0f;
    bool hasAniso = NULL != strstr((const char *)dc->gl.gl.extensions,
                                   "GL_EXT_texture_filter_anisotropic");
    if (hasAniso) {
        glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, &dc->gl.gl.EXT_texture_max_aniso);
    }

    if (0) {
        DumpDebug(dc);
    }

    LOGV("initGLThread end %p", rsc);
    return true;
}


bool rsdGLSetSurface(const Context *rsc, uint32_t w, uint32_t h, ANativeWindow *sur) {
    RsHal *dc = (RsHal *)rsc->mHal.drv;

    EGLBoolean ret;
    // WAR: Some drivers fail to handle 0 size surfaces correcntly.
    // Use the pbuffer to avoid this pitfall.
    if ((dc->gl.egl.surface != NULL) || (w == 0) || (h == 0)) {
        ret = eglMakeCurrent(dc->gl.egl.display, dc->gl.egl.surfaceDefault,
                             dc->gl.egl.surfaceDefault, dc->gl.egl.context);
        checkEglError("eglMakeCurrent", ret);

        ret = eglDestroySurface(dc->gl.egl.display, dc->gl.egl.surface);
        checkEglError("eglDestroySurface", ret);

        dc->gl.egl.surface = NULL;
        dc->gl.width = 1;
        dc->gl.height = 1;
    }

    dc->gl.wndSurface = sur;
    if (dc->gl.wndSurface != NULL) {
        dc->gl.width = w;
        dc->gl.height = h;

        dc->gl.egl.surface = eglCreateWindowSurface(dc->gl.egl.display, dc->gl.egl.config,
                                                    dc->gl.wndSurface, NULL);
        checkEglError("eglCreateWindowSurface");
        if (dc->gl.egl.surface == EGL_NO_SURFACE) {
            LOGE("eglCreateWindowSurface returned EGL_NO_SURFACE");
        }

        ret = eglMakeCurrent(dc->gl.egl.display, dc->gl.egl.surface,
                             dc->gl.egl.surface, dc->gl.egl.context);
        checkEglError("eglMakeCurrent", ret);
    }
    return true;
}

void rsdGLSwap(const android::renderscript::Context *rsc) {
    RsHal *dc = (RsHal *)rsc->mHal.drv;
    eglSwapBuffers(dc->gl.egl.display, dc->gl.egl.surface);
}

