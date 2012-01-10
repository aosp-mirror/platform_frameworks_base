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

#include <GLES/gl.h>
#include <GLES2/gl2.h>
#include <GLES/glext.h>

#include <rs_hal.h>
#include <rsContext.h>
#include <rsPath.h>

#include "rsdCore.h"
#include "rsdPath.h"
#include "rsdAllocation.h"
#include "rsdGL.h"
#include "rsdVertexArray.h"
#include "rsdShaderCache.h"

using namespace android;
using namespace android::renderscript;

class DrvPath {
protected:
    DrvPath();
public:
    virtual ~DrvPath();
    virtual void draw(Context *) = 0;
};

class DrvPathStatic : public DrvPath {
public:
    typedef struct {
        float x1, xc, x2;
        float y1, yc, y2;
    } segment_t;

    segment_t *mSegments;
    uint32_t mSegmentCount;

    DrvPathStatic(const Allocation *vtx, const Allocation *loops);
    virtual ~DrvPathStatic();

    virtual void draw(Context *);
};

class DrvPathDynamic : public DrvPath {
public:
    DrvPathDynamic();
    virtual ~DrvPathDynamic();
};

static void cleanup(const Context *rsc, const Path *m) {
    DrvPath *dp = (DrvPath *)m->mHal.drv;
    if (dp) {
        delete dp;
    }
}

bool rsdPathInitStatic(const Context *rsc, const Path *m,
                       const Allocation *vtx, const Allocation *loops) {
    DrvPathStatic *drv = NULL;
    cleanup(rsc, m);

    DrvPathStatic *dps = new DrvPathStatic(vtx, loops);
    //LOGE("init path m %p,  %p", m, dps);
    m->mHal.drv = dps;
    return dps != NULL;
}

bool rsdPathInitDynamic(const Context *rsc, const Path *m) {
    return false;
}


void rsdPathDraw(const Context *rsc, const Path *m) {
    //LOGE("render m=%p", m);

    DrvPath *drv = (DrvPath *)m->mHal.drv;
    if(drv) {
        //LOGE("render 2 drv=%p", drv);
        drv->draw((Context *)rsc);
    }
}

void rsdPathDestroy(const Context *rsc, const Path *m) {
    cleanup(rsc, m);
    m->mHal.drv = NULL;
}




DrvPath::DrvPath() {
}

DrvPath::~DrvPath() {
}

DrvPathStatic::DrvPathStatic(const Allocation *vtx, const Allocation *loops) {
    mSegmentCount = vtx->getType()->getDimX() / 3;
    mSegments = new segment_t[mSegmentCount];

    const float *fin = (const float *)vtx->getPtr();
    for (uint32_t ct=0; ct < mSegmentCount; ct++) {
        segment_t *s = &mSegments[ct];
        s->x1 = fin[0];
        s->y1 = fin[1];

        s->xc = fin[2];
        s->yc = fin[3];

        s->x2 = fin[4];
        s->y2 = fin[5];
        fin += 6;
    }
}

DrvPathStatic::~DrvPathStatic() {
}

void DrvPathStatic::draw(Context *rsc) {
    const static float color[24] = {
        1.f, 0.f, 0.f, 1.f,  0.5f, 0.f, 0.f, 1.f,
        1.f, 0.f, 0.f, 1.f,  0.5f, 0.f, 0.f, 1.f,
        1.f, 1.f, 1.f, 1.f,  1.f, 1.f, 1.f, 1.f};
    float vtx[12];

    //LOGE("draw");
    if (!rsc->setupCheck()) {
        return;
    }

    RsdHal *dc = (RsdHal *)rsc->mHal.drv;
    if (!dc->gl.shaderCache->setup(rsc)) {
        return;
    }

    RsdVertexArray::Attrib attribs[2];
    attribs[0].set(GL_FLOAT, 2, 8, false, (uint32_t)vtx, "ATTRIB_position");
    attribs[1].set(GL_FLOAT, 4, 16, false, (uint32_t)color, "ATTRIB_color");
    RsdVertexArray va(attribs, 2);
    va.setup(rsc);

    //LOGE("mSegmentCount %i", mSegmentCount);
    for (uint32_t ct=0; ct < mSegmentCount; ct++) {
        segment_t *s = &mSegments[ct];

        vtx[0] = s->x1;
        vtx[1] = s->y1;
        vtx[2] = s->xc;
        vtx[3] = s->yc;

        vtx[4] = s->x2;
        vtx[5] = s->y2;
        vtx[6] = s->xc;
        vtx[7] = s->yc;

        vtx[8] = s->x1;
        vtx[9] = s->y1;
        vtx[10] = s->x2;
        vtx[11] = s->y2;

        RSD_CALL_GL(glDrawArrays, GL_LINES, 0, 6);
    }

}

DrvPathDynamic::DrvPathDynamic() {
}

DrvPathDynamic::~DrvPathDynamic() {
}
