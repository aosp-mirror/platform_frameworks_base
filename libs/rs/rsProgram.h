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

#ifndef ANDROID_RS_PROGRAM_H
#define ANDROID_RS_PROGRAM_H

#include "rsProgramBase.h"
#include "rsElement.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

#define RS_SHADER_INTERNAL "//rs_shader_internal\n"
#define RS_SHADER_ATTR "ATTRIB_"
#define RS_SHADER_UNI "UNI_"

class Program : public ProgramBase {
public:

    Program(Context *, const char * shaderText, uint32_t shaderLength,
                       const uint32_t * params, uint32_t paramLength);
    virtual ~Program();
    virtual bool freeChildren();

    void bindAllocation(Context *, Allocation *, uint32_t slot);

    bool isUserProgram() const {return !mIsInternal;}

    void bindTexture(Context *, uint32_t slot, Allocation *);
    void bindSampler(Context *, uint32_t slot, Sampler *);

    struct Hal {
        mutable void *drv;

        struct State {
            // The difference between Textures and Constants is how they are accessed
            // Texture lookups go though a sampler which in effect converts normalized
            // coordinates into type specific.  Multiple samples may also be taken
            // and filtered.
            //
            // Constants are strictly accessed by the shader code
            ObjectBaseRef<Allocation> *textures;
            RsTextureTarget *textureTargets;
            uint32_t texturesCount;

            ObjectBaseRef<Sampler> *samplers;
            uint32_t samplersCount;

            ObjectBaseRef<Allocation> *constants;
            ObjectBaseRef<Type> *constantTypes;
            uint32_t constantsCount;

            ObjectBaseRef<Element> *inputElements;
            uint32_t inputElementsCount;
        };
        State state;
    };
    Hal mHal;

protected:
    bool mIsInternal;
    String8 mUserShader;
    void initMemberVars();
};

}
}
#endif // ANDROID_RS_PROGRAM_H



