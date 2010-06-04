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

    ProgramVertex(Context *,const char * shaderText, uint32_t shaderLength,
                  const uint32_t * params, uint32_t paramLength);
    ProgramVertex(Context *, bool texMat);
    virtual ~ProgramVertex();

    virtual void setupGL(const Context *rsc, ProgramVertexState *state);
    virtual void setupGL2(const Context *rsc, ProgramVertexState *state, ShaderCache *sc);


    void setTextureMatrixEnable(bool e) {mTextureMatrixEnable = e;}
    void addLight(const Light *);

    void setProjectionMatrix(const rsc_Matrix *) const;
    void setModelviewMatrix(const rsc_Matrix *) const;
    void setTextureMatrix(const rsc_Matrix *) const;

    void transformToScreen(const Context *, float *v4out, const float *v3in) const;

    virtual void createShader();
    virtual void loadShader(Context *);
    virtual void init(Context *);

    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_PROGRAM_VERTEX; }
    static ProgramVertex *createFromStream(Context *rsc, IStream *stream);

protected:
    uint32_t mLightCount;
    ObjectBaseRef<const Light> mLights[MAX_LIGHTS];

    // Hacks to create a program for now
    bool mTextureMatrixEnable;

private:
    void initAddUserElement(const Element *e, String8 *names, uint32_t *count, const char *prefix);
};


class ProgramVertexState
{
public:
    ProgramVertexState();
    ~ProgramVertexState();

    void init(Context *rsc);
    void deinit(Context *rsc);
    void updateSize(Context *rsc);

    ObjectBaseRef<ProgramVertex> mDefault;
    ObjectBaseRef<ProgramVertex> mLast;
    ObjectBaseRef<Allocation> mDefaultAlloc;

    ObjectBaseRef<Type> mAllocType;


    float color[4];
};


}
}
#endif


