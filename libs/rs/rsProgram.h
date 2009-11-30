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

#ifndef ANDROID_RS_PROGRAM_H
#define ANDROID_RS_PROGRAM_H

#include "rsObjectBase.h"
#include "rsElement.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {


class ShaderCache;

class Program : public ObjectBase
{
public:
    const static uint32_t MAX_ATTRIBS = 8;
    const static uint32_t MAX_UNIFORMS = 16;

    Program(Context *, Element *in, Element *out);
    virtual ~Program();

    void bindAllocation(Allocation *);
    virtual void createShader();

    uint32_t getShaderID() const {return mShaderID;}
    void setShader(const char *, uint32_t len);

    uint32_t getAttribCount() const {return mAttribCount;}
    uint32_t getUniformCount() const {return mUniformCount;}
    const String8 & getAttribName(uint32_t i) const {return mAttribNames[i];}
    const String8 & getUniformName(uint32_t i) const {return mUniformNames[i];}

protected:
    // Components not listed in "in" will be passed though
    // unless overwritten by components in out.
    ObjectBaseRef<Element> mElementIn;
    ObjectBaseRef<Element> mElementOut;

    ObjectBaseRef<Allocation> mConstants;

    mutable bool mDirty;
    String8 mShader;
    String8 mUserShader;
    uint32_t mShaderID;

    uint32_t mAttribCount;
    uint32_t mUniformCount;
    String8 mAttribNames[MAX_ATTRIBS];
    String8 mUniformNames[MAX_UNIFORMS];

    bool loadShader(uint32_t type);

public:
    void forceDirty() const {mDirty = true;}
};



}
}
#endif



