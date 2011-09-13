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


#include "rsdFrameBufferObj.h"
#include "rsdAllocation.h"
#include "rsdGL.h"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

using namespace android;
using namespace android::renderscript;

RsdFrameBufferObj::RsdFrameBufferObj() {
    mFBOId = 0;
    mWidth = 0;
    mHeight = 0;
    mColorTargetsCount = 1;
    mColorTargets = new DrvAllocation*[mColorTargetsCount];
    for (uint32_t i = 0; i < mColorTargetsCount; i ++) {
        mColorTargets[i] = 0;
    }
    mDepthTarget = NULL;
    mDirty = true;
}

RsdFrameBufferObj::~RsdFrameBufferObj() {
    if(mFBOId != 0) {
        glDeleteFramebuffers(1, &mFBOId);
    }
    delete [] mColorTargets;
}

void RsdFrameBufferObj::checkError(const Context *rsc) {
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


void RsdFrameBufferObj::setDepthAttachment() {
    if (mDepthTarget != NULL) {
        if (mDepthTarget->textureID) {
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                                   GL_TEXTURE_2D, mDepthTarget->textureID, 0);
        } else {
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                                      GL_RENDERBUFFER, mDepthTarget->renderTargetID);
        }
    } else {
        // Reset last attachment
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, 0, 0);
    }
}

void RsdFrameBufferObj::setColorAttachment() {
    // Now attach color targets
    for (uint32_t i = 0; i < mColorTargetsCount; i ++) {
        if (mColorTargets[i] != NULL) {
            if (mColorTargets[i]->textureID) {
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i,
                                       GL_TEXTURE_2D, mColorTargets[i]->textureID, 0);
            } else {
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i,
                                          GL_RENDERBUFFER, mColorTargets[i]->renderTargetID);
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

bool RsdFrameBufferObj::renderToFramebuffer() {
    if (mDepthTarget != NULL) {
        return false;
    }

    for (uint32_t i = 0; i < mColorTargetsCount; i ++) {
        if (mColorTargets[i] != NULL) {
            return false;
        }
    }
    return true;
}

void RsdFrameBufferObj::setActive(const Context *rsc) {
    bool framebuffer = renderToFramebuffer();
    if (!framebuffer) {
        if(mFBOId == 0) {
            RSD_CALL_GL(glGenFramebuffers, 1, &mFBOId);
        }
        RSD_CALL_GL(glBindFramebuffer, GL_FRAMEBUFFER, mFBOId);

        if (mDirty) {
            setDepthAttachment();
            setColorAttachment();
            mDirty = false;
        }

        RSD_CALL_GL(glViewport, 0, 0, mWidth, mHeight);
        checkError(rsc);
    } else {
        RSD_CALL_GL(glBindFramebuffer, GL_FRAMEBUFFER, 0);
        RSD_CALL_GL(glViewport, 0, 0, rsc->getWidth(), rsc->getHeight());
    }
}
