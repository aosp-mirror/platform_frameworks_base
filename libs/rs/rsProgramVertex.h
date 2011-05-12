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

class ProgramVertex : public Program {
public:
    ProgramVertex(Context *,const char * shaderText, uint32_t shaderLength,
                  const uint32_t * params, uint32_t paramLength);
    virtual ~ProgramVertex();

    virtual void setup(Context *rsc, ProgramVertexState *state);

    void setProjectionMatrix(Context *, const rsc_Matrix *) const;
    void getProjectionMatrix(Context *, rsc_Matrix *) const;
    void setModelviewMatrix(Context *, const rsc_Matrix *) const;
    void setTextureMatrix(Context *, const rsc_Matrix *) const;

    void transformToScreen(Context *, float *v4out, const float *v3in) const;

    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_PROGRAM_VERTEX; }
    static ProgramVertex *createFromStream(Context *rsc, IStream *stream);
};

class ProgramVertexState {
public:
    ProgramVertexState();
    ~ProgramVertexState();

    void init(Context *rsc);
    void deinit(Context *rsc);
    void updateSize(Context *rsc);

    ObjectBaseRef<ProgramVertex> mDefault;
    ObjectBaseRef<ProgramVertex> mLast;
    ObjectBaseRef<Allocation> mDefaultAlloc;
};

}
}
#endif


