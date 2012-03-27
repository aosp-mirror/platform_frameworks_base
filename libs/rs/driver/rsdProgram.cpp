/*
 * Copyright (C) 2011-2012 The Android Open Source Project
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
#include "rsdAllocation.h"
#include "rsdProgramVertex.h"
#include "rsdShader.h"
#include "rsdShaderCache.h"

#include "rsContext.h"
#include "rsProgramVertex.h"
#include "rsProgramFragment.h"

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

using namespace android;
using namespace android::renderscript;

bool rsdProgramVertexInit(const Context *rsc, const ProgramVertex *pv,
                          const char* shader, size_t shaderLen,
                          const char** textureNames, size_t textureNamesCount,
                          const size_t *textureNamesLength) {
    RsdShader *drv = new RsdShader(pv, GL_VERTEX_SHADER, shader, shaderLen,
                                   textureNames, textureNamesCount, textureNamesLength);
    pv->mHal.drv = drv;

    return true;
}

static void SyncProgramConstants(const Context *rsc, const Program *p) {
    for (uint32_t ct=0; ct < p->mHal.state.texturesCount; ct++) {
        const Allocation *a = p->mHal.state.textures[ct];
        if (!a) {
            continue;
        }
        DrvAllocation *drvAlloc = (DrvAllocation *)a->mHal.drv;
        if (drvAlloc->uploadDeferred) {
            rsdAllocationSyncAll(rsc, a, RS_ALLOCATION_USAGE_SCRIPT);
        }
    }
}

void rsdProgramVertexSetActive(const Context *rsc, const ProgramVertex *pv) {
    RsdHal *dc = (RsdHal *)rsc->mHal.drv;

    SyncProgramConstants(rsc, pv);
    dc->gl.shaderCache->setActiveVertex((RsdShader*)pv->mHal.drv);
}

void rsdProgramVertexDestroy(const Context *rsc, const ProgramVertex *pv) {
    RsdHal *dc = (RsdHal *)rsc->mHal.drv;

    RsdShader *drv = NULL;
    if(pv->mHal.drv) {
        drv = (RsdShader*)pv->mHal.drv;
        if (rsc->props.mLogShaders) {
            ALOGV("Destroying vertex shader with ID %u", (uint32_t)pv);
        }
        if (drv->getStateBasedIDCount()) {
            dc->gl.shaderCache->cleanupVertex(drv);
        }
        delete drv;
    }
}

bool rsdProgramFragmentInit(const Context *rsc, const ProgramFragment *pf,
                            const char* shader, size_t shaderLen,
                            const char** textureNames, size_t textureNamesCount,
                            const size_t *textureNamesLength) {
    RsdShader *drv = new RsdShader(pf, GL_FRAGMENT_SHADER, shader, shaderLen,
                                   textureNames, textureNamesCount, textureNamesLength);
    pf->mHal.drv = drv;

    return true;
}

void rsdProgramFragmentSetActive(const Context *rsc, const ProgramFragment *pf) {
    RsdHal *dc = (RsdHal *)rsc->mHal.drv;

    SyncProgramConstants(rsc, pf);
    dc->gl.shaderCache->setActiveFragment((RsdShader*)pf->mHal.drv);
}

void rsdProgramFragmentDestroy(const Context *rsc, const ProgramFragment *pf) {
    RsdHal *dc = (RsdHal *)rsc->mHal.drv;

    RsdShader *drv = NULL;
    if(pf->mHal.drv) {
        drv = (RsdShader*)pf->mHal.drv;
        if (rsc->props.mLogShaders) {
            ALOGV("Destroying fragment shader with ID %u", (uint32_t)pf);
        }
        if (drv->getStateBasedIDCount()) {
            dc->gl.shaderCache->cleanupFragment(drv);
        }
        delete drv;
    }
}


