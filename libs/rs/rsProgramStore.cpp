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

#include "rsContext.h"
#include "rsProgramStore.h"

using namespace android;
using namespace android::renderscript;


ProgramStore::ProgramStore(Context *rsc) : Program(rsc) {
    memset(&mHal, 0, sizeof(mHal));

    mHal.state.ditherEnable = true;

    mHal.state.colorRWriteEnable = true;
    mHal.state.colorGWriteEnable = true;
    mHal.state.colorBWriteEnable = true;
    mHal.state.colorAWriteEnable = true;
    mHal.state.blendSrc = RS_BLEND_SRC_ONE;
    mHal.state.blendDst = RS_BLEND_DST_ZERO;

    mHal.state.depthWriteEnable = true;
    mHal.state.depthFunc = RS_DEPTH_FUNC_LESS;
}

ProgramStore::~ProgramStore() {
    mRSC->mHal.funcs.store.destroy(mRSC, this);
}

void ProgramStore::setupGL2(const Context *rsc, ProgramStoreState *state) {
    if (state->mLast.get() == this) {
        return;
    }
    state->mLast.set(this);

    rsc->mHal.funcs.store.setActive(rsc, this);
}

void ProgramStore::setDitherEnable(bool enable) {
    mHal.state.ditherEnable = enable;
}

void ProgramStore::serialize(OStream *stream) const {
}

ProgramStore *ProgramStore::createFromStream(Context *rsc, IStream *stream) {
    return NULL;
}

void ProgramStore::setDepthFunc(RsDepthFunc func) {
    mHal.state.depthFunc = func;
}

void ProgramStore::setDepthMask(bool mask) {
    mHal.state.depthWriteEnable = mask;
}

void ProgramStore::setBlendFunc(RsBlendSrcFunc src, RsBlendDstFunc dst) {
    mHal.state.blendSrc = src;
    mHal.state.blendDst = dst;
}

void ProgramStore::setColorMask(bool r, bool g, bool b, bool a) {
    mHal.state.colorRWriteEnable = r;
    mHal.state.colorGWriteEnable = g;
    mHal.state.colorBWriteEnable = b;
    mHal.state.colorAWriteEnable = a;
}

void ProgramStore::init() {
    mRSC->mHal.funcs.store.init(mRSC, this);
}

ProgramStoreState::ProgramStoreState() {
    mPFS = NULL;
}

ProgramStoreState::~ProgramStoreState() {
    ObjectBase::checkDelete(mPFS);
    mPFS = NULL;
}

void ProgramStoreState::init(Context *rsc) {
    ProgramStore *pfs = new ProgramStore(rsc);
    pfs->init();
    mDefault.set(pfs);
}

void ProgramStoreState::deinit(Context *rsc) {
    mDefault.clear();
    mLast.clear();
}


namespace android {
namespace renderscript {

void rsi_ProgramStoreBegin(Context * rsc, RsElement in, RsElement out) {
    ObjectBase::checkDelete(rsc->mStateFragmentStore.mPFS);
    rsc->mStateFragmentStore.mPFS = new ProgramStore(rsc);
}

void rsi_ProgramStoreDepthFunc(Context *rsc, RsDepthFunc func) {
    rsc->mStateFragmentStore.mPFS->setDepthFunc(func);
}

void rsi_ProgramStoreDepthMask(Context *rsc, bool mask) {
    rsc->mStateFragmentStore.mPFS->setDepthMask(mask);
}

void rsi_ProgramStoreColorMask(Context *rsc, bool r, bool g, bool b, bool a) {
    rsc->mStateFragmentStore.mPFS->setColorMask(r, g, b, a);
}

void rsi_ProgramStoreBlendFunc(Context *rsc, RsBlendSrcFunc src, RsBlendDstFunc dst) {
    rsc->mStateFragmentStore.mPFS->setBlendFunc(src, dst);
}

RsProgramStore rsi_ProgramStoreCreate(Context *rsc) {
    ProgramStore *pfs = rsc->mStateFragmentStore.mPFS;
    pfs->init();
    pfs->incUserRef();
    rsc->mStateFragmentStore.mPFS = 0;
    return pfs;
}

void rsi_ProgramStoreDither(Context *rsc, bool enable) {
    rsc->mStateFragmentStore.mPFS->setDitherEnable(enable);
}

}
}
