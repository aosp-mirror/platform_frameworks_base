/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_RS_CONTEXT_HOST_STUB_H
#define ANDROID_RS_CONTEXT_HOST_STUB_H

#include "rsUtils.h"
#include "rsType.h"
#include "rsAllocation.h"
#include "rsMesh.h"
#include "rsAllocation.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

#define CHECK_OBJ(o)
#define CHECK_OBJ_OR_NULL(o)

class Device;

class Context {
public:
    Context(Device *, bool isGraphics, bool useDepth) {
        mObjHead = NULL;
    }
    ~Context() {
    }

    //StructuredAllocationContext mStateAllocation;
    ElementState mStateElement;
    TypeState mStateType;
    RsSurfaceConfig mUserSurfaceConfig;

    //bool setupCheck();


    uint32_t getWidth() const {return 0;}
    uint32_t getHeight() const {return 0;}

    // Timers
    enum Timers {
        RS_TIMER_IDLE,
        RS_TIMER_INTERNAL,
        RS_TIMER_SCRIPT,
        RS_TIMER_CLEAR_SWAP,
        _RS_TIMER_TOTAL
    };

    void timerSet(Timers) { }

    bool checkVersion1_1() const {return false; }
    bool checkVersion2_0() const {return false; }

    struct {
        bool mLogTimes;
        bool mLogScripts;
        bool mLogObjects;
        bool mLogShaders;
        bool mLogShadersAttr;
        bool mLogShadersUniforms;
        bool mLogVisual;
    } props;

    void dumpDebug() const {    }
    void checkError(const char *) const {  };
    void setError(RsError e, const char *msg = NULL) {  }

    mutable const ObjectBase * mObjHead;

    bool ext_OES_texture_npot() const {return mGL.OES_texture_npot;}
    bool ext_GL_NV_texture_npot_2D_mipmap() const {return mGL.GL_NV_texture_npot_2D_mipmap;}
    float ext_texture_max_aniso() const {return mGL.EXT_texture_max_aniso; }
    uint32_t getMaxFragmentTextures() const {return mGL.mMaxFragmentTextureImageUnits;}
    uint32_t getMaxFragmentUniformVectors() const {return mGL.mMaxFragmentUniformVectors;}
    uint32_t getMaxVertexUniformVectors() const {return mGL.mMaxVertexUniformVectors;}
    uint32_t getMaxVertexAttributes() const {return mGL.mMaxVertexAttribs;}

protected:

    struct {
        const uint8_t * mVendor;
        const uint8_t * mRenderer;
        const uint8_t * mVersion;
        const uint8_t * mExtensions;

        uint32_t mMajorVersion;
        uint32_t mMinorVersion;

        int32_t mMaxVaryingVectors;
        int32_t mMaxTextureImageUnits;

        int32_t mMaxFragmentTextureImageUnits;
        int32_t mMaxFragmentUniformVectors;

        int32_t mMaxVertexAttribs;
        int32_t mMaxVertexUniformVectors;
        int32_t mMaxVertexTextureUnits;

        bool OES_texture_npot;
        bool GL_NV_texture_npot_2D_mipmap;
        float EXT_texture_max_aniso;
    } mGL;

};

}
}
#endif

