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

using namespace android;
using namespace android::renderscript;


FBOCache::FBOCache() {
    mDirty = true;
    mHal.state.colorTargetsCount = 1;
    mHal.state.colorTargets = new Allocation*[mHal.state.colorTargetsCount];
    mColorTargets = new ObjectBaseRef<Allocation>[mHal.state.colorTargetsCount];
    resetAll(NULL);
}

FBOCache::~FBOCache() {
    delete[] mHal.state.colorTargets;
    delete[] mColorTargets;
}

void FBOCache::init(Context *rsc) {
    rsc->mHal.funcs.framebuffer.init(rsc, this);
}

void FBOCache::deinit(Context *rsc) {
    rsc->mHal.funcs.framebuffer.destroy(rsc, this);
}

void FBOCache::bindColorTarget(Context *rsc, Allocation *a, uint32_t slot) {
    if (slot >= mHal.state.colorTargetsCount) {
        ALOGE("Invalid render target index");
        return;
    }
    if (a != NULL) {
        if (!a->getIsTexture()) {
            ALOGE("Invalid Color Target");
            return;
        }
    }
    mColorTargets[slot].set(a);
    mHal.state.colorTargets[slot] = a;
    mDirty = true;
}

void FBOCache::bindDepthTarget(Context *rsc, Allocation *a) {
    if (a != NULL) {
        if (!a->getIsRenderTarget()) {
            ALOGE("Invalid Depth Target");
            return;
        }
    }
    mDepthTarget.set(a);
    mHal.state.depthTarget = a;
    mDirty = true;
}

void FBOCache::resetAll(Context *) {
    for (uint32_t i = 0; i < mHal.state.colorTargetsCount; i ++) {
        mColorTargets[i].set(NULL);
        mHal.state.colorTargets[i] = NULL;
    }
    mDepthTarget.set(NULL);
    mHal.state.depthTarget = NULL;
    mDirty = true;
}

void FBOCache::setup(Context *rsc) {
    if (!mDirty) {
        return;
    }

    rsc->mHal.funcs.framebuffer.setActive(rsc, this);

    mDirty = false;
}
