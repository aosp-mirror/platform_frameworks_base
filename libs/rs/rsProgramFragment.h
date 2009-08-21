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

#ifndef ANDROID_RS_PROGRAM_FRAGMENT_H
#define ANDROID_RS_PROGRAM_FRAGMENT_H

#include "rsProgram.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class ProgramFragmentState;

class ProgramFragment : public Program
{
public:
    const static uint32_t MAX_TEXTURE = 2;



    ProgramFragment(Element *in, Element *out, bool pointSpriteEnable);
    virtual ~ProgramFragment();

    virtual void setupGL(ProgramFragmentState *);



    void bindTexture(uint32_t slot, Allocation *);
    void bindSampler(uint32_t slot, Sampler *);
    void setType(uint32_t slot, const Element *, uint32_t dim);

    void setEnvMode(uint32_t slot, RsTexEnvMode);
    void setTexEnable(uint32_t slot, bool);



protected:
    // The difference between Textures and Constants is how they are accessed
    // Texture lookups go though a sampler which in effect converts normalized
    // coordinates into type specific.  Multiple samples may also be taken
    // and filtered.
    //
    // Constants are strictly accessed by programetic loads.
    ObjectBaseRef<Allocation> mTextures[MAX_TEXTURE];
    ObjectBaseRef<Sampler> mSamplers[MAX_TEXTURE];
    ObjectBaseRef<const Element> mTextureFormats[MAX_TEXTURE];
    uint32_t mTextureDimensions[MAX_TEXTURE];


    // Hacks to create a program for now
    RsTexEnvMode mEnvModes[MAX_TEXTURE];
    uint32_t mTextureEnableMask;
    bool mPointSpriteEnable;
};

class ProgramFragmentState
{
public:
    ProgramFragmentState();
    ~ProgramFragmentState();

    ProgramFragment *mPF;
    void init(Context *rsc, int32_t w, int32_t h);

    ObjectBaseRef<Type> mTextureTypes[ProgramFragment::MAX_TEXTURE];
    ObjectBaseRef<ProgramFragment> mDefault;
    Vector<ProgramFragment *> mPrograms;

    ObjectBaseRef<ProgramFragment> mLast;
};


}
}
#endif




