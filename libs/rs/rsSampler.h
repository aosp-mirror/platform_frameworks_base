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

#ifndef ANDROID_RS_SAMPLER_H
#define ANDROID_RS_SAMPLER_H

#include "rsAllocation.h"
#include "RenderScript.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

const static uint32_t RS_MAX_SAMPLER_SLOT = 16;

class SamplerState;

class Sampler : public ObjectBase {
public:
    static ObjectBaseRef<Sampler> getSampler(Context *,
                                             RsSamplerValue magFilter,
                                             RsSamplerValue minFilter,
                                             RsSamplerValue wrapS,
                                             RsSamplerValue wrapT,
                                             RsSamplerValue wrapR,
                                             float aniso = 1.0f);
    void bindToContext(SamplerState *, uint32_t slot);
    void unbindFromContext(SamplerState *);

    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_SAMPLER; }
    static Sampler *createFromStream(Context *rsc, IStream *stream);

    struct Hal {
        mutable void *drv;

        struct State {
            RsSamplerValue magFilter;
            RsSamplerValue minFilter;
            RsSamplerValue wrapS;
            RsSamplerValue wrapT;
            RsSamplerValue wrapR;
            float aniso;
        };
        State state;
    };
    Hal mHal;

protected:
    int32_t mBoundSlot;

    virtual void preDestroy() const;
    virtual ~Sampler();

private:
    Sampler(Context *);
    Sampler(Context *,
            RsSamplerValue magFilter,
            RsSamplerValue minFilter,
            RsSamplerValue wrapS,
            RsSamplerValue wrapT,
            RsSamplerValue wrapR,
            float aniso = 1.0f);
};


class SamplerState {
public:
    ObjectBaseRef<Sampler> mSamplers[RS_MAX_SAMPLER_SLOT];
    void init(Context *rsc) {
    }
    void deinit(Context *rsc) {
        for (uint32_t i = 0; i < RS_MAX_SAMPLER_SLOT; i ++) {
            mSamplers[i].clear();
        }
    }
    // Cache of all existing raster programs.
    Vector<Sampler *> mAllSamplers;
};

}
}
#endif //ANDROID_RS_SAMPLER_H



