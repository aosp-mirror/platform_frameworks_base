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

#include "rsContext.h"
#include "rsFBOCache.h"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

using namespace android;
using namespace android::renderscript;

struct DrvFrameBuffer {
    GLuint mFBOId;
};

void checkError(const Context *rsc) {
    GLenum status;
    status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    switch (status) {
    case GL_FRAMEBUFFER_COMPLETE:
        break;
    case GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT:
        rsc->setError(RS_ERROR_BAD_VALUE,
                      "Unable to set up render Target: RFRAMEBUFFER_INCOMPLETE_ATTACHMENT");
        break;
    case GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT:
        rsc->setError(RS_ERROR_BAD_VALUE,
                      "Unable to set up render Target: GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT");
        break;
    case GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS:
        rsc->setError(RS_ERROR_BAD_VALUE,
                      "Unable to set up render Target: GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS");
        break;
    case GL_FRAMEBUFFER_UNSUPPORTED:
        rsc->setError(RS_ERROR_BAD_VALUE,
                      "Unable to set up render Target: GL_FRAMEBUFFER_UNSUPPORTED");
        break;
    }
}


void setDepthAttachment(const Context *rsc, const FBOCache *fb) {
    if (fb->mHal.state.depthTarget.get() != NULL) {
        if (fb->mHal.state.depthTarget->getIsTexture()) {
            uint32_t texID = fb->mHal.state.depthTarget->getTextureID();
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                                   GL_TEXTURE_2D, texID, 0);
        } else {
            uint32_t texID = fb->mHal.state.depthTarget->getRenderTargetID();
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                                      GL_RENDERBUFFER, texID);
        }
    } else {
        // Reset last attachment
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                                  GL_RENDERBUFFER, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                               GL_TEXTURE_2D, 0, 0);
    }
}

void setColorAttachment(const Context *rsc, const FBOCache *fb) {
    // Now attach color targets
    for (uint32_t i = 0; i < fb->mHal.state.colorTargetsCount; i ++) {
        uint32_t texID = 0;
        if (fb->mHal.state.colorTargets[i].get() != NULL) {
            if (fb->mHal.state.colorTargets[i]->getIsTexture()) {
                uint32_t texID = fb->mHal.state.colorTargets[i]->getTextureID();
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i,
                                       GL_TEXTURE_2D, texID, 0);
            } else {
                uint32_t texID = fb->mHal.state.depthTarget->getRenderTargetID();
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i,
                                          GL_RENDERBUFFER, texID);
            }
        } else {
            // Reset last attachment
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i,
                                      GL_RENDERBUFFER, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i,
                                   GL_TEXTURE_2D, 0, 0);
        }
    }
}

bool renderToFramebuffer(const FBOCache *fb) {
    if (fb->mHal.state.depthTarget.get() != NULL) {
        return false;
    }

    for (uint32_t i = 0; i < fb->mHal.state.colorTargetsCount; i ++) {
        if (fb->mHal.state.colorTargets[i].get() != NULL) {
            return false;
        }
    }
    return true;
}


bool rsdFrameBufferInit(const Context *rsc, const FBOCache *fb) {
    DrvFrameBuffer *drv = (DrvFrameBuffer *)calloc(1, sizeof(DrvFrameBuffer));
    if (drv == NULL) {
        return false;
    }
    fb->mHal.drv = drv;
    drv->mFBOId = 0;

    return true;
}

void rsdFrameBufferSetActive(const Context *rsc, const FBOCache *fb) {
    DrvFrameBuffer *drv = (DrvFrameBuffer *)fb->mHal.drv;

    bool framebuffer = renderToFramebuffer(fb);
    if (!framebuffer) {
        if(drv->mFBOId == 0) {
            glGenFramebuffers(1, &drv->mFBOId);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, drv->mFBOId);

        setDepthAttachment(rsc, fb);
        setColorAttachment(rsc, fb);

        glViewport(0, 0, fb->mHal.state.colorTargets[0]->getType()->getDimX(),
                         fb->mHal.state.colorTargets[0]->getType()->getDimY());

        checkError(rsc);
    } else {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, rsc->getWidth(), rsc->getHeight());
    }
}

void rsdFrameBufferDestroy(const Context *rsc, const FBOCache *fb) {
    DrvFrameBuffer *drv = (DrvFrameBuffer *)fb->mHal.drv;
    if(drv->mFBOId != 0) {
        glDeleteFramebuffers(1, &drv->mFBOId);
    }

    free(fb->mHal.drv);
    fb->mHal.drv = NULL;
}


