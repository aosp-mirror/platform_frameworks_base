/*
 * Copyright (C) 2009-2011 The Android Open Source Project
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

#include "rsProgramBase.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class ProgramRasterState;

class ProgramRaster : public ProgramBase {
public:
    virtual void setup(const Context *, ProgramRasterState *);
    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_PROGRAM_RASTER; }
    static ProgramRaster *createFromStream(Context *rsc, IStream *stream);

    static ObjectBaseRef<ProgramRaster> getProgramRaster(Context *rsc,
                                                         bool pointSmooth,
                                                         bool lineSmooth,
                                                         bool pointSprite,
                                                         float lineWidth,
                                                         RsCullMode cull);
    struct Hal {
        mutable void *drv;

        struct State {
            bool pointSmooth;
            bool lineSmooth;
            bool pointSprite;
            float lineWidth;
            RsCullMode cull;
        };
        State state;
    };
    Hal mHal;

protected:
    virtual void preDestroy() const;
    virtual ~ProgramRaster();

private:
    ProgramRaster(Context *rsc,
                  bool pointSmooth,
                  bool lineSmooth,
                  bool pointSprite,
                  float lineWidth,
                  RsCullMode cull);

};

class ProgramRasterState {
public:
    ProgramRasterState();
    ~ProgramRasterState();
    void init(Context *rsc);
    void deinit(Context *rsc);

    ObjectBaseRef<ProgramRaster> mDefault;
    ObjectBaseRef<ProgramRaster> mLast;

    // Cache of all existing raster programs.
    Vector<ProgramRaster *> mRasterPrograms;
};


}
}
#endif




