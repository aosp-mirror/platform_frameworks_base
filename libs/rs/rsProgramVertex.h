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

#ifndef ANDROID_RS_PROGRAM_VERTEX_H
#define ANDROID_RS_PROGRAM_VERTEX_H

#include "rsProgram.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class ProgramVertexState;

class ProgramVertex : public Program
{
public:
    const static uint32_t MAX_LIGHTS = 8;

    ProgramVertex(Element *in, Element *out);
    virtual ~ProgramVertex();

    virtual void setupGL(const Context *rsc, ProgramVertexState *state);


    void setTextureMatrixEnable(bool e) {mTextureMatrixEnable = e;}
    void addLight(const Light *);

    void setProjectionMatrix(const rsc_Matrix *) const;
    void setModelviewMatrix(const rsc_Matrix *) const;
    void setTextureMatrix(const rsc_Matrix *) const;

protected:
    uint32_t mLightCount;
    ObjectBaseRef<const Light> mLights[MAX_LIGHTS];

    // Hacks to create a program for now
    bool mTextureMatrixEnable;
};


class ProgramVertexState
{
public:
    ProgramVertexState();
    ~ProgramVertexState();

    void init(Context *rsc, int32_t w, int32_t h);

    ObjectBaseRef<ProgramVertex> mDefault;
    ObjectBaseRef<ProgramVertex> mLast;
    ObjectBaseRef<Allocation> mDefaultAlloc;



    ProgramVertex *mPV;

    //ObjectBaseRef<Type> mTextureTypes[ProgramFragment::MAX_TEXTURE];


};


}
}
#endif


