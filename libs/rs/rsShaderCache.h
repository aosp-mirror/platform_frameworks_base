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

#ifndef ANDROID_SHADER_CACHE_H
#define ANDROID_SHADER_CACHE_H


#include "rsObjectBase.h"
#include "rsVertexArray.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {


// An element is a group of Components that occupies one cell in a structure.
class ShaderCache
{
public:
    ShaderCache();
    virtual ~ShaderCache();

    bool lookup(Context *rsc, ProgramVertex *, ProgramFragment *);

    void cleanupVertex(uint32_t id);
    void cleanupFragment(uint32_t id);

    void cleanupAll();

    int32_t vtxAttribSlot(uint32_t a) const {return mCurrent->mVtxAttribSlots[a];}
    int32_t vtxUniformSlot(uint32_t a) const {return mCurrent->mVtxUniformSlots[a];}
    int32_t fragAttribSlot(uint32_t a) const {return mCurrent->mFragAttribSlots[a];}
    int32_t fragUniformSlot(uint32_t a) const {return mCurrent->mFragUniformSlots[a];}
    bool isUserVertexProgram() const {return mCurrent->mUserVertexProgram;}

protected:
    typedef struct {
        uint32_t vtx;
        uint32_t frag;
        uint32_t program;
        int32_t mVtxAttribSlots[Program::MAX_ATTRIBS];
        int32_t mVtxUniformSlots[Program::MAX_UNIFORMS];
        int32_t mFragAttribSlots[Program::MAX_ATTRIBS];
        int32_t mFragUniformSlots[Program::MAX_UNIFORMS];
        bool mUserVertexProgram;
        bool mIsValid;
    } entry_t;
    entry_t *mEntries;
    entry_t *mCurrent;

    uint32_t mEntryCount;
    uint32_t mEntryAllocationCount;

};



}
}
#endif //ANDROID_SHADER_CACHE_H




