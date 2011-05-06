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


ProgramStore::ProgramStore(Context *rsc,
                           bool colorMaskR, bool colorMaskG, bool colorMaskB, bool colorMaskA,
                           bool depthMask, bool ditherEnable,
                           RsBlendSrcFunc srcFunc, RsBlendDstFunc destFunc,
                           RsDepthFunc depthFunc) : ProgramBase(rsc) {
    memset(&mHal, 0, sizeof(mHal));

    mHal.state.ditherEnable = ditherEnable;

    mHal.state.colorRWriteEnable = colorMaskR;
    mHal.state.colorGWriteEnable = colorMaskG;
    mHal.state.colorBWriteEnable = colorMaskB;
    mHal.state.colorAWriteEnable = colorMaskA;
    mHal.state.blendSrc = srcFunc;
    mHal.state.blendDst = destFunc;

    mHal.state.depthWriteEnable = depthMask;
    mHal.state.depthFunc = depthFunc;
}

ProgramStore::~ProgramStore() {
    mRSC->mHal.funcs.store.destroy(mRSC, this);
}

void ProgramStore::setup(const Context *rsc, ProgramStoreState *state) {
    if (state->mLast.get() == this) {
        return;
    }
    state->mLast.set(this);

    rsc->mHal.funcs.store.setActive(rsc, this);
}

void ProgramStore::serialize(OStream *stream) const {
}

ProgramStore *ProgramStore::createFromStream(Context *rsc, IStream *stream) {
    return NULL;
}

void ProgramStore::init() {
    mRSC->mHal.funcs.store.init(mRSC, this);
}

ProgramStoreState::ProgramStoreState() {
}

ProgramStoreState::~ProgramStoreState() {
}

void ProgramStoreState::init(Context *rsc) {
    ProgramStore *ps = new ProgramStore(rsc,
                                        true, true, true, true,
                                        true, true,
                                        RS_BLEND_SRC_ONE, RS_BLEND_DST_ZERO,
                                        RS_DEPTH_FUNC_LESS);
    ps->init();
    mDefault.set(ps);
}

void ProgramStoreState::deinit(Context *rsc) {
    mDefault.clear();
    mLast.clear();
}


namespace android {
namespace renderscript {

RsProgramStore rsi_ProgramStoreCreate(Context *rsc,
                                      bool colorMaskR, bool colorMaskG, bool colorMaskB, bool colorMaskA,
                                      bool depthMask, bool ditherEnable,
                                      RsBlendSrcFunc srcFunc, RsBlendDstFunc destFunc,
                                      RsDepthFunc depthFunc) {

    ProgramStore *pfs = new ProgramStore(rsc,
                                         colorMaskR, colorMaskG, colorMaskB, colorMaskA,
                                         depthMask, ditherEnable,
                                         srcFunc, destFunc, depthFunc);
    pfs->init();
    pfs->incUserRef();
    return pfs;
}

}
}
