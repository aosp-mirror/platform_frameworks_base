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

#include "rsFBOCache.h"

#include "rsContext.h"
#include "rsAllocation.h"

#ifndef ANDROID_RS_SERIALIZE
#include <GLES/gl.h>
#include <GLES2/gl2.h>
#endif //ANDROID_RS_SERIALIZE

using namespace android;
using namespace android::renderscript;


FBOCache::FBOCache() {
    mFBOId = 0;
    mDirty = false;
    mMaxTargets = 1;
    mColorTargets = new ObjectBaseRef<Allocation>[mMaxTargets];
}

FBOCache::~FBOCache() {
    delete[] mColorTargets;
#ifndef ANDROID_RS_SERIALIZE
    if(mFBOId != 0) {
        glDeleteFramebuffers(1, &mFBOId);
    }
#endif //ANDROID_RS_SERIALIZE
}

void FBOCache::bindColorTarget(Context *rsc, Allocation *a, uint32_t slot) {
    if (slot >= mMaxTargets) {
        LOGE("Invalid render target index");
        return;
    }
    if (a != NULL) {
        if (!a->getIsTexture()) {
            LOGE("Invalid Color Target");
            return;
        }
        if (a->getIsTexture()) {
            if (a->getTextureID() == 0) {
                a->deferredUploadToTexture(rsc);
            }
        } else if (a->getRenderTargetID() == 0) {
            a->deferredAllocateRenderTarget(rsc);
        }
    }
    mColorTargets[slot].set(a);
    mDirty = true;
}

void FBOCache::bindDepthTarget(Context *rsc, Allocation *a) {
    if (a != NULL) {
        if (!a->getIsRenderTarget()) {
            LOGE("Invalid Depth Target");
            return;
        }
        if (a->getIsTexture()) {
            if (a->getTextureID() == 0) {
                a->deferredUploadToTexture(rsc);
            }
        } else if (a->getRenderTargetID() == 0) {
            a->deferredAllocateRenderTarget(rsc);
        }
    }
    mDepthTarget.set(a);
    mDirty = true;
}

void FBOCache::resetAll(Context *) {
    for (uint32_t i = 0; i < mMaxTargets; i ++) {
        mColorTargets[i].set(NULL);
    }
    mDepthTarget.set(NULL);
    mDirty = true;
}

bool FBOCache::renderToFramebuffer() {
    if (mDepthTarget.get() != NULL) {
        return false;
    }

    for (uint32_t i = 0; i < mMaxTargets; i ++) {
        if (mColorTargets[i].get() != NULL) {
            return false;
        }
    }
    return true;
}

void FBOCache::checkError(Context *rsc) {
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

void FBOCache::setDepthAttachment(Context *rsc) {
#ifndef ANDROID_RS_SERIALIZE
    if (mDepthTarget.get() != NULL) {
        mDepthTarget->uploadCheck(rsc);
        if (mDepthTarget->getIsTexture()) {
            uint32_t texID = mDepthTarget->getTextureID();
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                                   GL_TEXTURE_2D, texID, 0);
        } else {
            uint32_t texID = mDepthTarget->getRenderTargetID();
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
#endif //ANDROID_RS_SERIALIZE
}

void FBOCache::setColorAttachment(Context *rsc) {
#ifndef ANDROID_RS_SERIALIZE
    // Now attach color targets
    for (uint32_t i = 0; i < mMaxTargets; i ++) {
        uint32_t texID = 0;
        if (mColorTargets[i].get() != NULL) {
            mColorTargets[i]->uploadCheck(rsc);
            if (mColorTargets[i]->getIsTexture()) {
                uint32_t texID = mColorTargets[i]->getTextureID();
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i,
                                       GL_TEXTURE_2D, texID, 0);
            } else {
                uint32_t texID = mDepthTarget->getRenderTargetID();
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
#endif //ANDROID_RS_SERIALIZE
}

void FBOCache::setupGL2(Context *rsc) {
#ifndef ANDROID_RS_SERIALIZE
    if (!mDirty) {
        return;
    }

    bool framebuffer = renderToFramebuffer();

    if (!framebuffer) {
        if(mFBOId == 0) {
            glGenFramebuffers(1, &mFBOId);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, mFBOId);

        setDepthAttachment(rsc);
        setColorAttachment(rsc);

        glViewport(0, 0, mColorTargets[0]->getType()->getDimX(),
                         mColorTargets[0]->getType()->getDimY());

        checkError(rsc);
    } else {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, rsc->getWidth(), rsc->getHeight());
    }
#endif //ANDROID_RS_SERIALIZE
}
