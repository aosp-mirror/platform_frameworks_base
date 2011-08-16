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

#ifndef ANDROID_RS_PROGRAM_FRAGMENT_STORE_H
#define ANDROID_RS_PROGRAM_FRAGMENT_STORE_H

#include "rsProgramBase.h"
#include "rsStream.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class ProgramStoreState;

class ProgramStore : public ProgramBase {
public:
    virtual void setup(const Context *, ProgramStoreState *);

    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_PROGRAM_STORE; }
    static ProgramStore *createFromStream(Context *rsc, IStream *stream);
    static ObjectBaseRef<ProgramStore> getProgramStore(Context *,
                                                       bool colorMaskR, bool colorMaskG,
                                                       bool colorMaskB, bool colorMaskA,
                                                       bool depthMask, bool ditherEnable,
                                                       RsBlendSrcFunc srcFunc, RsBlendDstFunc destFunc,
                                                       RsDepthFunc depthFunc);

    void init();

    struct Hal {
        mutable void *drv;

        struct State {
            bool ditherEnable;

            //bool blendEnable;
            bool colorRWriteEnable;
            bool colorGWriteEnable;
            bool colorBWriteEnable;
            bool colorAWriteEnable;
            RsBlendSrcFunc blendSrc;
            RsBlendDstFunc blendDst;

            //bool depthTestEnable;
            bool depthWriteEnable;
            RsDepthFunc depthFunc;
        };
        State state;
    };
    Hal mHal;

protected:
    virtual void preDestroy() const;
    virtual ~ProgramStore();

private:
    ProgramStore(Context *,
                 bool colorMaskR, bool colorMaskG, bool colorMaskB, bool colorMaskA,
                 bool depthMask, bool ditherEnable,
                 RsBlendSrcFunc srcFunc, RsBlendDstFunc destFunc,
                 RsDepthFunc depthFunc);
};

class ProgramStoreState {
public:
    ProgramStoreState();
    ~ProgramStoreState();
    void init(Context *rsc);
    void deinit(Context *rsc);

    ObjectBaseRef<ProgramStore> mDefault;
    ObjectBaseRef<ProgramStore> mLast;

    // Cache of all existing store programs.
    Vector<ProgramStore *> mStorePrograms;
};

}
}
#endif



