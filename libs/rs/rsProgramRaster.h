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

#ifndef ANDROID_RS_PROGRAM_RASTER_H
#define ANDROID_RS_PROGRAM_RASTER_H

#include "rsProgram.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class ProgramRasterState;

class ProgramRaster : public Program
{
public:
    ProgramRaster(Context *rsc,
                  bool pointSmooth,
                  bool lineSmooth,
                  bool pointSprite);
    virtual ~ProgramRaster();

    virtual void setupGL(const Context *, ProgramRasterState *);
    virtual void setupGL2(const Context *, ProgramRasterState *);
    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_PROGRAM_RASTER; }
    static ProgramRaster *createFromStream(Context *rsc, IStream *stream);

    void setLineWidth(float w);
    void setCullMode(RsCullMode mode);

protected:
    bool mPointSmooth;
    bool mLineSmooth;
    bool mPointSprite;
    float mLineWidth;
    RsCullMode mCull;
};

class ProgramRasterState
{
public:
    ProgramRasterState();
    ~ProgramRasterState();
    void init(Context *rsc);
    void deinit(Context *rsc);

    ObjectBaseRef<ProgramRaster> mDefault;
    ObjectBaseRef<ProgramRaster> mLast;
};


}
}
#endif




