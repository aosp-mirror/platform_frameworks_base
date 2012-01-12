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

#ifndef RSD_GL_H
#define RSD_GL_H

#include <rs_hal.h>
#include <EGL/egl.h>

#define RSD_CALL_GL(x, ...) rsc->setWatchdogGL(#x, __LINE__, __FILE__); x(__VA_ARGS__); rsc->setWatchdogGL(NULL, 0, NULL)

class RsdShaderCache;
class RsdVertexArrayState;
class RsdFrameBufferObj;

typedef void (* InvokeFunc_t)(void);
typedef void (*WorkerCallback_t)(void *usr, uint32_t idx);

typedef struct RsdGLRec {
    struct {
        EGLint numConfigs;
        EGLint majorVersion;
        EGLint minorVersion;
        EGLConfig config;
        EGLContext context;
        EGLSurface surface;
        EGLSurface surfaceDefault;
        EGLDisplay display;
    } egl;

    struct {
        const uint8_t * vendor;
        const uint8_t * renderer;
        const uint8_t * version;
        const uint8_t * extensions;

        uint32_t majorVersion;
        uint32_t minorVersion;

        int32_t maxVaryingVectors;
        int32_t maxTextureImageUnits;

        int32_t maxFragmentTextureImageUnits;
        int32_t maxFragmentUniformVectors;

        int32_t maxVertexAttribs;
        int32_t maxVertexUniformVectors;
        int32_t maxVertexTextureUnits;

        bool OES_texture_npot;
        bool GL_IMG_texture_npot;
        bool GL_NV_texture_npot_2D_mipmap;
        float EXT_texture_max_aniso;
    } gl;

    ANativeWindow *wndSurface;
    uint32_t width;
    uint32_t height;
    RsdShaderCache *shaderCache;
    RsdVertexArrayState *vertexArrayState;
    RsdFrameBufferObj *currentFrameBuffer;
} RsdGL;


bool rsdGLInit(const android::renderscript::Context *rsc);
void rsdGLShutdown(const android::renderscript::Context *rsc);
bool rsdGLSetSurface(const android::renderscript::Context *rsc,
                     uint32_t w, uint32_t h, RsNativeWindow sur);
void rsdGLSwap(const android::renderscript::Context *rsc);
void rsdGLCheckError(const android::renderscript::Context *rsc,
                     const char *msg, bool isFatal = false);
void rsdGLSetPriority(const android::renderscript::Context *rsc,
                      int32_t priority);

#endif

