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
#include "rsProgramRaster.h"

using namespace android;
using namespace android::renderscript;


ProgramRaster::ProgramRaster(Context *rsc, bool pointSmooth,
                             bool lineSmooth, bool pointSprite,
                             float lineWidth, RsCullMode cull)
    : ProgramBase(rsc) {

    memset(&mHal, 0, sizeof(mHal));

    mHal.state.pointSmooth = pointSmooth;
    mHal.state.lineSmooth = lineSmooth;
    mHal.state.pointSprite = pointSprite;
    mHal.state.lineWidth = lineWidth;
    mHal.state.cull = cull;

    rsc->mHal.funcs.raster.init(rsc, this);
}

void ProgramRaster::preDestroy() const {
    for (uint32_t ct = 0; ct < mRSC->mStateRaster.mRasterPrograms.size(); ct++) {
        if (mRSC->mStateRaster.mRasterPrograms[ct] == this) {
            mRSC->mStateRaster.mRasterPrograms.removeAt(ct);
            break;
        }
    }
}

ProgramRaster::~ProgramRaster() {
    mRSC->mHal.funcs.raster.destroy(mRSC, this);
}

void ProgramRaster::setup(const Context *rsc, ProgramRasterState *state) {
    if (state->mLast.get() == this && !mDirty) {
        return;
    }
    state->mLast.set(this);
    mDirty = false;

    rsc->mHal.funcs.raster.setActive(rsc, this);
}

void ProgramRaster::serialize(OStream *stream) const {
}

ProgramRaster *ProgramRaster::createFromStream(Context *rsc, IStream *stream) {
    return NULL;
}

ProgramRasterState::ProgramRasterState() {
}

ProgramRasterState::~ProgramRasterState() {
}

void ProgramRasterState::init(Context *rsc) {
    mDefault.set(ProgramRaster::getProgramRaster(rsc, false, false,
                                                 false, 1.f, RS_CULL_BACK).get());
}

void ProgramRasterState::deinit(Context *rsc) {
    mDefault.clear();
    mLast.clear();
}

ObjectBaseRef<ProgramRaster> ProgramRaster::getProgramRaster(Context *rsc,
                                                             bool pointSmooth,
                                                             bool lineSmooth,
                                                             bool pointSprite,
                                                             float lineWidth,
                                                             RsCullMode cull) {
    ObjectBaseRef<ProgramRaster> returnRef;
    ObjectBase::asyncLock();
    for (uint32_t ct = 0; ct < rsc->mStateRaster.mRasterPrograms.size(); ct++) {
        ProgramRaster *existing = rsc->mStateRaster.mRasterPrograms[ct];
        if (existing->mHal.state.pointSmooth != pointSmooth) continue;
        if (existing->mHal.state.lineSmooth != lineSmooth) continue;
        if (existing->mHal.state.pointSprite != pointSprite) continue;
        if (existing->mHal.state.lineWidth != lineWidth) continue;
        if (existing->mHal.state.cull != cull) continue;
        returnRef.set(existing);
        ObjectBase::asyncUnlock();
        return returnRef;
    }
    ObjectBase::asyncUnlock();

    ProgramRaster *pr = new ProgramRaster(rsc, pointSmooth,
                                          lineSmooth, pointSprite, lineWidth, cull);
    returnRef.set(pr);

    ObjectBase::asyncLock();
    rsc->mStateRaster.mRasterPrograms.push(pr);
    ObjectBase::asyncUnlock();

    return returnRef;
}

namespace android {
namespace renderscript {

RsProgramRaster rsi_ProgramRasterCreate(Context * rsc, bool pointSmooth, bool lineSmooth,
                                        bool pointSprite, float lineWidth, RsCullMode cull) {
    ObjectBaseRef<ProgramRaster> pr = ProgramRaster::getProgramRaster(rsc, pointSmooth, lineSmooth,
                                                                      pointSprite, lineWidth, cull);
    pr->incUserRef();
    return pr.get();
}

}
}

