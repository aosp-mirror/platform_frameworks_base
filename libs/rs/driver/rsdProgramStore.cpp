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


#include "rsdCore.h"
#include "rsdProgramStore.h"

#include "rsContext.h"
#include "rsProgramStore.h"

#include <GLES/gl.h>
#include <GLES/glext.h>


using namespace android;
using namespace android::renderscript;

struct DrvProgramStore {
    GLenum blendSrc;
    GLenum blendDst;
    bool blendEnable;

    GLenum depthFunc;
    bool depthTestEnable;
};

bool rsdProgramStoreInit(const Context *rsc, const ProgramStore *ps) {
    DrvProgramStore *drv = (DrvProgramStore *)calloc(1, sizeof(DrvProgramStore));
    if (drv == NULL) {
        return false;
    }

    ps->mHal.drv = drv;
    drv->depthTestEnable = true;

    switch (ps->mHal.state.depthFunc) {
    case RS_DEPTH_FUNC_ALWAYS:
        drv->depthTestEnable = false;
        drv->depthFunc = GL_ALWAYS;
        break;
    case RS_DEPTH_FUNC_LESS:
        drv->depthFunc = GL_LESS;
        break;
    case RS_DEPTH_FUNC_LEQUAL:
        drv->depthFunc = GL_LEQUAL;
        break;
    case RS_DEPTH_FUNC_GREATER:
        drv->depthFunc = GL_GREATER;
        break;
    case RS_DEPTH_FUNC_GEQUAL:
        drv->depthFunc = GL_GEQUAL;
        break;
    case RS_DEPTH_FUNC_EQUAL:
        drv->depthFunc = GL_EQUAL;
        break;
    case RS_DEPTH_FUNC_NOTEQUAL:
        drv->depthFunc = GL_NOTEQUAL;
        break;
    default:
        ALOGE("Unknown depth function.");
        goto error;
    }



    drv->blendEnable = true;
    if ((ps->mHal.state.blendSrc == RS_BLEND_SRC_ONE) &&
        (ps->mHal.state.blendDst == RS_BLEND_DST_ZERO)) {
        drv->blendEnable = false;
    }

    switch (ps->mHal.state.blendSrc) {
    case RS_BLEND_SRC_ZERO:
        drv->blendSrc = GL_ZERO;
        break;
    case RS_BLEND_SRC_ONE:
        drv->blendSrc = GL_ONE;
        break;
    case RS_BLEND_SRC_DST_COLOR:
        drv->blendSrc = GL_DST_COLOR;
        break;
    case RS_BLEND_SRC_ONE_MINUS_DST_COLOR:
        drv->blendSrc = GL_ONE_MINUS_DST_COLOR;
        break;
    case RS_BLEND_SRC_SRC_ALPHA:
        drv->blendSrc = GL_SRC_ALPHA;
        break;
    case RS_BLEND_SRC_ONE_MINUS_SRC_ALPHA:
        drv->blendSrc = GL_ONE_MINUS_SRC_ALPHA;
        break;
    case RS_BLEND_SRC_DST_ALPHA:
        drv->blendSrc = GL_DST_ALPHA;
        break;
    case RS_BLEND_SRC_ONE_MINUS_DST_ALPHA:
        drv->blendSrc = GL_ONE_MINUS_DST_ALPHA;
        break;
    case RS_BLEND_SRC_SRC_ALPHA_SATURATE:
        drv->blendSrc = GL_SRC_ALPHA_SATURATE;
        break;
    default:
        rsc->setError(RS_ERROR_FATAL_DRIVER, "Unknown blend src mode.");
        goto error;
    }

    switch (ps->mHal.state.blendDst) {
    case RS_BLEND_DST_ZERO:
        drv->blendDst = GL_ZERO;
        break;
    case RS_BLEND_DST_ONE:
        drv->blendDst = GL_ONE;
        break;
    case RS_BLEND_DST_SRC_COLOR:
        drv->blendDst = GL_SRC_COLOR;
        break;
    case RS_BLEND_DST_ONE_MINUS_SRC_COLOR:
        drv->blendDst = GL_ONE_MINUS_SRC_COLOR;
        break;
    case RS_BLEND_DST_SRC_ALPHA:
        drv->blendDst = GL_SRC_ALPHA;
        break;
    case RS_BLEND_DST_ONE_MINUS_SRC_ALPHA:
        drv->blendDst = GL_ONE_MINUS_SRC_ALPHA;
        break;
    case RS_BLEND_DST_DST_ALPHA:
        drv->blendDst = GL_DST_ALPHA;
        break;
    case RS_BLEND_DST_ONE_MINUS_DST_ALPHA:
        drv->blendDst = GL_ONE_MINUS_DST_ALPHA;
        break;
    default:
        rsc->setError(RS_ERROR_FATAL_DRIVER, "Unknown blend dst mode.");
        goto error;
    }

    return true;

error:
    free(drv);
    ps->mHal.drv = NULL;
    return false;
}

void rsdProgramStoreSetActive(const Context *rsc, const ProgramStore *ps) {
    DrvProgramStore *drv = (DrvProgramStore *)ps->mHal.drv;

    RSD_CALL_GL(glColorMask, ps->mHal.state.colorRWriteEnable,
                ps->mHal.state.colorGWriteEnable,
                ps->mHal.state.colorBWriteEnable,
                ps->mHal.state.colorAWriteEnable);

    if (drv->blendEnable) {
        RSD_CALL_GL(glEnable, GL_BLEND);
        RSD_CALL_GL(glBlendFunc, drv->blendSrc, drv->blendDst);
    } else {
        RSD_CALL_GL(glDisable, GL_BLEND);
    }

    if (rsc->mUserSurfaceConfig.depthMin > 0) {
        RSD_CALL_GL(glDepthMask, ps->mHal.state.depthWriteEnable);
        if (drv->depthTestEnable || ps->mHal.state.depthWriteEnable) {
            RSD_CALL_GL(glEnable, GL_DEPTH_TEST);
            RSD_CALL_GL(glDepthFunc, drv->depthFunc);
        } else {
            RSD_CALL_GL(glDisable, GL_DEPTH_TEST);
        }
    } else {
        RSD_CALL_GL(glDepthMask, false);
        RSD_CALL_GL(glDisable, GL_DEPTH_TEST);
    }

    /*
    if (rsc->mUserSurfaceConfig.stencilMin > 0) {
    } else {
        glStencilMask(0);
        glDisable(GL_STENCIL_TEST);
    }
    */

    if (ps->mHal.state.ditherEnable) {
        RSD_CALL_GL(glEnable, GL_DITHER);
    } else {
        RSD_CALL_GL(glDisable, GL_DITHER);
    }
}

void rsdProgramStoreDestroy(const Context *rsc, const ProgramStore *ps) {
    free(ps->mHal.drv);
    ps->mHal.drv = NULL;
}


