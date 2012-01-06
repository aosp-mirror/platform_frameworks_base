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


#include <rs_hal.h>
#include <rsContext.h>
#include <rsMesh.h>

#include "rsdCore.h"
#include "rsdMesh.h"
#include "rsdMeshObj.h"
#include "rsdShaderCache.h"

using namespace android;
using namespace android::renderscript;

bool rsdMeshInit(const Context *rsc, const Mesh *m) {
    RsdMeshObj *drv = NULL;
    if(m->mHal.drv) {
        drv = (RsdMeshObj*)m->mHal.drv;
        delete drv;
    }
    drv = new RsdMeshObj(rsc, m);
    m->mHal.drv = drv;
    return drv->init(rsc);
}

void rsdMeshDraw(const Context *rsc, const Mesh *m, uint32_t primIndex, uint32_t start, uint32_t len) {
    if(m->mHal.drv) {
        RsdHal *dc = (RsdHal *)rsc->mHal.drv;
        if (!dc->gl.shaderCache->setup(rsc)) {
            return;
        }

        RsdMeshObj *drv = (RsdMeshObj*)m->mHal.drv;
        drv->renderPrimitiveRange(rsc, primIndex, start, len);
    }
}

void rsdMeshDestroy(const Context *rsc, const Mesh *m) {
    if(m->mHal.drv) {
        RsdMeshObj *drv = (RsdMeshObj*)m->mHal.drv;
        delete drv;
    }
}


