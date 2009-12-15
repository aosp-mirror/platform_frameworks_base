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

#ifndef ANDROID_RS_PROGRAM_FRAGMENT_STORE_H
#define ANDROID_RS_PROGRAM_FRAGMENT_STORE_H

#include "rsProgram.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class ProgramFragmentStoreState;

class ProgramFragmentStore : public Program
{
public:
    ProgramFragmentStore(Context *);
    virtual ~ProgramFragmentStore();

    virtual void setupGL(const Context *, ProgramFragmentStoreState *);
    virtual void setupGL2(const Context *, ProgramFragmentStoreState *);

    void setDepthFunc(RsDepthFunc);
    void setDepthMask(bool);

    void setBlendFunc(RsBlendSrcFunc src, RsBlendDstFunc dst);
    void setColorMask(bool, bool, bool, bool);

    void setDitherEnable(bool);

protected:
    bool mDitherEnable;

    bool mBlendEnable;
    bool mColorRWriteEnable;
    bool mColorGWriteEnable;
    bool mColorBWriteEnable;
    bool mColorAWriteEnable;
    int32_t mBlendSrc;
    int32_t mBlendDst;

    bool mDepthTestEnable;
    bool mDepthWriteEnable;
    int32_t mDepthFunc;

    bool mStencilTestEnable;
};

class ProgramFragmentStoreState
{
public:
    ProgramFragmentStoreState();
    ~ProgramFragmentStoreState();
    void init(Context *rsc, int32_t w, int32_t h);
    void deinit(Context *rsc);

    ObjectBaseRef<ProgramFragmentStore> mDefault;
    ObjectBaseRef<ProgramFragmentStore> mLast;


    ProgramFragmentStore *mPFS;
};


}
}
#endif



