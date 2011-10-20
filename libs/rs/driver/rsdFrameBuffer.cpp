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
#include "rsdFrameBuffer.h"
#include "rsdFrameBufferObj.h"
#include "rsdAllocation.h"

#include "rsContext.h"
#include "rsFBOCache.h"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

using namespace android;
using namespace android::renderscript;

void setDepthAttachment(const Context *rsc, const FBOCache *fb) {
    RsdFrameBufferObj *fbo = (RsdFrameBufferObj*)fb->mHal.drv;

    DrvAllocation *depth = NULL;
    if (fb->mHal.state.depthTarget != NULL) {
        depth = (DrvAllocation *)fb->mHal.state.depthTarget->mHal.drv;

        if (depth->uploadDeferred) {
            rsdAllocationSyncAll(rsc, fb->mHal.state.depthTarget,
                                 RS_ALLOCATION_USAGE_SCRIPT);
        }
    }
    fbo->setDepthTarget(depth);
}

void setColorAttachment(const Context *rsc, const FBOCache *fb) {
    RsdFrameBufferObj *fbo = (RsdFrameBufferObj*)fb->mHal.drv;
    // Now attach color targets
    for (uint32_t i = 0; i < fb->mHal.state.colorTargetsCount; i ++) {
        DrvAllocation *color = NULL;
        if (fb->mHal.state.colorTargets[i] != NULL) {
            color = (DrvAllocation *)fb->mHal.state.colorTargets[i]->mHal.drv;

            if (color->uploadDeferred) {
                rsdAllocationSyncAll(rsc, fb->mHal.state.colorTargets[i],
                                     RS_ALLOCATION_USAGE_SCRIPT);
            }
        }
        fbo->setColorTarget(color, i);
    }
}

bool rsdFrameBufferInit(const Context *rsc, const FBOCache *fb) {
    RsdFrameBufferObj *fbo = new RsdFrameBufferObj();
    if (fbo == NULL) {
        return false;
    }
    fb->mHal.drv = fbo;

    RsdHal *dc = (RsdHal *)rsc->mHal.drv;
    dc->gl.currentFrameBuffer = fbo;

    return true;
}

void rsdFrameBufferSetActive(const Context *rsc, const FBOCache *fb) {
    setDepthAttachment(rsc, fb);
    setColorAttachment(rsc, fb);

    RsdFrameBufferObj *fbo = (RsdFrameBufferObj *)fb->mHal.drv;
    if (fb->mHal.state.colorTargets[0]) {
        fbo->setDimensions(fb->mHal.state.colorTargets[0]->getType()->getDimX(),
                           fb->mHal.state.colorTargets[0]->getType()->getDimY());
    } else if (fb->mHal.state.depthTarget) {
        fbo->setDimensions(fb->mHal.state.depthTarget->getType()->getDimX(),
                           fb->mHal.state.depthTarget->getType()->getDimY());
    }

    fbo->setActive(rsc);
}

void rsdFrameBufferDestroy(const Context *rsc, const FBOCache *fb) {
    RsdFrameBufferObj *fbo = (RsdFrameBufferObj *)fb->mHal.drv;
    delete fbo;
    fb->mHal.drv = NULL;
}


