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
#include "rsSampler.h"


using namespace android;
using namespace android::renderscript;


Sampler::Sampler(Context *rsc) : ObjectBase(rsc) {
    // Should not get called.
    rsAssert(0);
}

Sampler::Sampler(Context *rsc,
                 RsSamplerValue magFilter,
                 RsSamplerValue minFilter,
                 RsSamplerValue wrapS,
                 RsSamplerValue wrapT,
                 RsSamplerValue wrapR,
                 float aniso) : ObjectBase(rsc) {
    mHal.state.magFilter = magFilter;
    mHal.state.minFilter = minFilter;
    mHal.state.wrapS = wrapS;
    mHal.state.wrapT = wrapT;
    mHal.state.wrapR = wrapR;
    mHal.state.aniso = aniso;

    mRSC->mHal.funcs.sampler.init(mRSC, this);
}

Sampler::~Sampler() {
    mRSC->mHal.funcs.sampler.destroy(mRSC, this);
}

void Sampler::preDestroy() const {
    for (uint32_t ct = 0; ct < mRSC->mStateSampler.mAllSamplers.size(); ct++) {
        if (mRSC->mStateSampler.mAllSamplers[ct] == this) {
            mRSC->mStateSampler.mAllSamplers.removeAt(ct);
            break;
        }
    }
}

void Sampler::bindToContext(SamplerState *ss, uint32_t slot) {
    ss->mSamplers[slot].set(this);
    mBoundSlot = slot;
}

void Sampler::unbindFromContext(SamplerState *ss) {
    int32_t slot = mBoundSlot;
    mBoundSlot = -1;
    ss->mSamplers[slot].clear();
}

void Sampler::serialize(OStream *stream) const {
}

Sampler *Sampler::createFromStream(Context *rsc, IStream *stream) {
    return NULL;
}

ObjectBaseRef<Sampler> Sampler::getSampler(Context *rsc,
                                           RsSamplerValue magFilter,
                                           RsSamplerValue minFilter,
                                           RsSamplerValue wrapS,
                                           RsSamplerValue wrapT,
                                           RsSamplerValue wrapR,
                                           float aniso) {
    ObjectBaseRef<Sampler> returnRef;
    ObjectBase::asyncLock();
    for (uint32_t ct = 0; ct < rsc->mStateSampler.mAllSamplers.size(); ct++) {
        Sampler *existing = rsc->mStateSampler.mAllSamplers[ct];
        if (existing->mHal.state.magFilter != magFilter) continue;
        if (existing->mHal.state.minFilter != minFilter ) continue;
        if (existing->mHal.state.wrapS != wrapS) continue;
        if (existing->mHal.state.wrapT != wrapT) continue;
        if (existing->mHal.state.wrapR != wrapR) continue;
        if (existing->mHal.state.aniso != aniso) continue;
        returnRef.set(existing);
        ObjectBase::asyncUnlock();
        return returnRef;
    }
    ObjectBase::asyncUnlock();

    Sampler *s = new Sampler(rsc, magFilter, minFilter, wrapS, wrapT, wrapR, aniso);
    returnRef.set(s);

    ObjectBase::asyncLock();
    rsc->mStateSampler.mAllSamplers.push(s);
    ObjectBase::asyncUnlock();

    return returnRef;
}

////////////////////////////////

namespace android {
namespace renderscript {

RsSampler rsi_SamplerCreate(Context * rsc,
                            RsSamplerValue magFilter,
                            RsSamplerValue minFilter,
                            RsSamplerValue wrapS,
                            RsSamplerValue wrapT,
                            RsSamplerValue wrapR,
                            float aniso) {
    ObjectBaseRef<Sampler> s = Sampler::getSampler(rsc, magFilter, minFilter,
                                                   wrapS, wrapT, wrapR, aniso);
    s->incUserRef();
    return s.get();
}

}}
