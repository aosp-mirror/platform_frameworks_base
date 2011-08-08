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
                          const char* shader, uint32_t shaderLen) {
    RsdShader *drv = new RsdShader(pv, GL_VERTEX_SHADER, shader, shaderLen);
    pv->mHal.drv = drv;

    return drv->createShader();
}

static void SyncProgramConstants(const Context *rsc, const Program *p) {
    for (uint32_t ct=0; ct < p->mHal.state.texturesCount; ct++) {
        const Allocation *a = p->mHal.state.textures[ct].get();
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
            LOGV("Destroying vertex shader with ID %u", drv->getShaderID());
        }
        if (drv->getShaderID()) {
            dc->gl.shaderCache->cleanupVertex(drv->getShaderID());
        }
        delete drv;
    }
}

bool rsdProgramFragmentInit(const Context *rsc, const ProgramFragment *pf,
                          const char* shader, uint32_t shaderLen) {
    RsdShader *drv = new RsdShader(pf, GL_FRAGMENT_SHADER, shader, shaderLen);
    pf->mHal.drv = drv;

    return drv->createShader();
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
            LOGV("Destroying fragment shader with ID %u", drv->getShaderID());
        }
        if (drv->getShaderID()) {
            dc->gl.shaderCache->cleanupFragment(drv->getShaderID());
        }
        delete drv;
    }
}


