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

#include "rsContext.h"
#include "rsScriptC.h"
#include "rsMatrix4x4.h"
#include "rsMatrix3x3.h"
#include "rsMatrix2x2.h"

#include "utils/Timers.h"
#include "driver/rsdVertexArray.h"
#include "driver/rsdShaderCache.h"
#include "driver/rsdCore.h"

#define GL_GLEXT_PROTOTYPES

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <time.h>

using namespace android;
using namespace android::renderscript;

namespace android {
namespace renderscript {

//////////////////////////////////////////////////////////////////////////////
// Context
//////////////////////////////////////////////////////////////////////////////

void rsrBindTexture(Context *rsc, Script *sc, ProgramFragment *pf, uint32_t slot, Allocation *a) {
    CHECK_OBJ_OR_NULL(a);
    CHECK_OBJ(pf);
    pf->bindTexture(rsc, slot, a);
}

void rsrBindConstant(Context *rsc, Script *sc, ProgramFragment *pf, uint32_t slot, Allocation *a) {
    CHECK_OBJ_OR_NULL(a);
    CHECK_OBJ(pf);
    pf->bindAllocation(rsc, a, slot);
}

void rsrBindConstant(Context *rsc, Script *sc, ProgramVertex *pv, uint32_t slot, Allocation *a) {
    CHECK_OBJ_OR_NULL(a);
    CHECK_OBJ(pv);
    pv->bindAllocation(rsc, a, slot);
}

void rsrBindSampler(Context *rsc, Script *sc, ProgramFragment *pf, uint32_t slot, Sampler *s) {
    CHECK_OBJ_OR_NULL(vs);
    CHECK_OBJ(vpf);
    pf->bindSampler(rsc, slot, s);
}

void rsrBindProgramStore(Context *rsc, Script *sc, ProgramStore *ps) {
    CHECK_OBJ_OR_NULL(ps);
    rsc->setProgramStore(ps);
}

void rsrBindProgramFragment(Context *rsc, Script *sc, ProgramFragment *pf) {
    CHECK_OBJ_OR_NULL(pf);
    rsc->setProgramFragment(pf);
}

void rsrBindProgramVertex(Context *rsc, Script *sc, ProgramVertex *pv) {
    CHECK_OBJ_OR_NULL(pv);
    rsc->setProgramVertex(pv);
}

void rsrBindProgramRaster(Context *rsc, Script *sc, ProgramRaster *pr) {
    CHECK_OBJ_OR_NULL(pr);
    rsc->setProgramRaster(pr);
}

void rsrBindFrameBufferObjectColorTarget(Context *rsc, Script *sc, Allocation *a, uint32_t slot) {
    CHECK_OBJ(va);
    rsc->mFBOCache.bindColorTarget(rsc, a, slot);
    rsc->mStateVertex.updateSize(rsc);
}

void rsrBindFrameBufferObjectDepthTarget(Context *rsc, Script *sc, Allocation *a) {
    CHECK_OBJ(va);
    rsc->mFBOCache.bindDepthTarget(rsc, a);
    rsc->mStateVertex.updateSize(rsc);
}

void rsrClearFrameBufferObjectColorTarget(Context *rsc, Script *sc, uint32_t slot) {
    rsc->mFBOCache.bindColorTarget(rsc, NULL, slot);
    rsc->mStateVertex.updateSize(rsc);
}

void rsrClearFrameBufferObjectDepthTarget(Context *rsc, Script *sc) {
    rsc->mFBOCache.bindDepthTarget(rsc, NULL);
    rsc->mStateVertex.updateSize(rsc);
}

void rsrClearFrameBufferObjectTargets(Context *rsc, Script *sc) {
    rsc->mFBOCache.resetAll(rsc);
    rsc->mStateVertex.updateSize(rsc);
}

//////////////////////////////////////////////////////////////////////////////
// VP
//////////////////////////////////////////////////////////////////////////////

void rsrVpLoadProjectionMatrix(Context *rsc, Script *sc, const rsc_Matrix *m) {
    rsc->getProgramVertex()->setProjectionMatrix(rsc, m);
}

void rsrVpLoadModelMatrix(Context *rsc, Script *sc, const rsc_Matrix *m) {
    rsc->getProgramVertex()->setModelviewMatrix(rsc, m);
}

void rsrVpLoadTextureMatrix(Context *rsc, Script *sc, const rsc_Matrix *m) {
    rsc->getProgramVertex()->setTextureMatrix(rsc, m);
}

void rsrPfConstantColor(Context *rsc, Script *sc, ProgramFragment *pf,
                        float r, float g, float b, float a) {
    CHECK_OBJ(pf);
    pf->setConstantColor(rsc, r, g, b, a);
}

void rsrVpGetProjectionMatrix(Context *rsc, Script *sc, rsc_Matrix *m) {
    rsc->getProgramVertex()->getProjectionMatrix(rsc, m);
}

//////////////////////////////////////////////////////////////////////////////
// Drawing
//////////////////////////////////////////////////////////////////////////////

void rsrDrawQuadTexCoords(Context *rsc, Script *sc,
                          float x1, float y1, float z1, float u1, float v1,
                          float x2, float y2, float z2, float u2, float v2,
                          float x3, float y3, float z3, float u3, float v3,
                          float x4, float y4, float z4, float u4, float v4) {
    if (!rsc->setupCheck()) {
        return;
    }

    RsdHal *dc = (RsdHal *)rsc->mHal.drv;
    if (!dc->gl.shaderCache->setup(rsc)) {
        return;
    }

    //LOGE("Quad");
    //LOGE("%4.2f, %4.2f, %4.2f", x1, y1, z1);
    //LOGE("%4.2f, %4.2f, %4.2f", x2, y2, z2);
    //LOGE("%4.2f, %4.2f, %4.2f", x3, y3, z3);
    //LOGE("%4.2f, %4.2f, %4.2f", x4, y4, z4);

    float vtx[] = {x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4};
    const float tex[] = {u1,v1, u2,v2, u3,v3, u4,v4};

    RsdVertexArray::Attrib attribs[2];
    attribs[0].set(GL_FLOAT, 3, 12, false, (uint32_t)vtx, "ATTRIB_position");
    attribs[1].set(GL_FLOAT, 2, 8, false, (uint32_t)tex, "ATTRIB_texture0");

    RsdVertexArray va(attribs, 2);
    va.setup(rsc);

    RSD_CALL_GL(glDrawArrays, GL_TRIANGLE_FAN, 0, 4);
}

void rsrDrawQuad(Context *rsc, Script *sc,
                 float x1, float y1, float z1,
                 float x2, float y2, float z2,
                 float x3, float y3, float z3,
                 float x4, float y4, float z4) {
    rsrDrawQuadTexCoords(rsc, sc, x1, y1, z1, 0, 1,
                                  x2, y2, z2, 1, 1,
                                  x3, y3, z3, 1, 0,
                                  x4, y4, z4, 0, 0);
}

void rsrDrawSpriteScreenspace(Context *rsc, Script *sc,
                              float x, float y, float z, float w, float h) {
    ObjectBaseRef<const ProgramVertex> tmp(rsc->getProgramVertex());
    rsc->setProgramVertex(rsc->getDefaultProgramVertex());
    //rsc->setupCheck();

    //GLint crop[4] = {0, h, w, -h};

    float sh = rsc->getHeight();

    rsrDrawQuad(rsc, sc,
                x,   sh - y,     z,
                x+w, sh - y,     z,
                x+w, sh - (y+h), z,
                x,   sh - (y+h), z);
    rsc->setProgramVertex((ProgramVertex *)tmp.get());
}

void rsrDrawRect(Context *rsc, Script *sc, float x1, float y1, float x2, float y2, float z) {
    //LOGE("SC_drawRect %f,%f  %f,%f  %f", x1, y1, x2, y2, z);
    rsrDrawQuad(rsc, sc, x1, y2, z, x2, y2, z, x2, y1, z, x1, y1, z);
}

void rsrDrawPath(Context *rsc, Script *sc, Path *sm) {
    CHECK_OBJ(sm);
    if (!rsc->setupCheck()) {
        return;
    }
    sm->render(rsc);
}

void rsrDrawMesh(Context *rsc, Script *sc, Mesh *sm) {
    CHECK_OBJ(sm);
    if (!rsc->setupCheck()) {
        return;
    }
    sm->render(rsc);
}

void rsrDrawMeshPrimitive(Context *rsc, Script *sc, Mesh *sm, uint32_t primIndex) {
    CHECK_OBJ(sm);
    if (!rsc->setupCheck()) {
        return;
    }
    sm->renderPrimitive(rsc, primIndex);
}

void rsrDrawMeshPrimitiveRange(Context *rsc, Script *sc, Mesh *sm, uint32_t primIndex,
                               uint32_t start, uint32_t len) {
    CHECK_OBJ(sm);
    if (!rsc->setupCheck()) {
        return;
    }
    sm->renderPrimitiveRange(rsc, primIndex, start, len);
}

void rsrMeshComputeBoundingBox(Context *rsc, Script *sc, Mesh *sm,
                               float *minX, float *minY, float *minZ,
                               float *maxX, float *maxY, float *maxZ) {
    CHECK_OBJ(sm);
    sm->computeBBox();
    *minX = sm->mBBoxMin[0];
    *minY = sm->mBBoxMin[1];
    *minZ = sm->mBBoxMin[2];
    *maxX = sm->mBBoxMax[0];
    *maxY = sm->mBBoxMax[1];
    *maxZ = sm->mBBoxMax[2];
}


//////////////////////////////////////////////////////////////////////////////
//
//////////////////////////////////////////////////////////////////////////////


void rsrColor(Context *rsc, Script *sc, float r, float g, float b, float a) {
    ProgramFragment *pf = rsc->getProgramFragment();
    pf->setConstantColor(rsc, r, g, b, a);
}

void rsrFinish(Context *rsc, Script *sc) {
    RSD_CALL_GL(glFinish);
}


void rsrClearColor(Context *rsc, Script *sc, float r, float g, float b, float a) {
    rsc->mFBOCache.setup(rsc);
    rsc->setupProgramStore();

    RSD_CALL_GL(glClearColor, r, g, b, a);
    RSD_CALL_GL(glClear, GL_COLOR_BUFFER_BIT);
}

void rsrClearDepth(Context *rsc, Script *sc, float v) {
    rsc->mFBOCache.setup(rsc);
    rsc->setupProgramStore();

    RSD_CALL_GL(glClearDepthf, v);
    RSD_CALL_GL(glClear, GL_DEPTH_BUFFER_BIT);
}

uint32_t rsrGetWidth(Context *rsc, Script *sc) {
    return rsc->getWidth();
}

uint32_t rsrGetHeight(Context *rsc, Script *sc) {
    return rsc->getHeight();
}

void rsrDrawTextAlloc(Context *rsc, Script *sc, Allocation *a, int x, int y) {
    const char *text = (const char *)a->getPtr();
    size_t allocSize = a->getType()->getSizeBytes();
    rsc->mStateFont.renderText(text, allocSize, x, y);
}

void rsrDrawText(Context *rsc, Script *sc, const char *text, int x, int y) {
    size_t textLen = strlen(text);
    rsc->mStateFont.renderText(text, textLen, x, y);
}

static void SetMetrics(Font::Rect *metrics,
                       int32_t *left, int32_t *right, int32_t *top, int32_t *bottom) {
    if (left) {
        *left = metrics->left;
    }
    if (right) {
        *right = metrics->right;
    }
    if (top) {
        *top = metrics->top;
    }
    if (bottom) {
        *bottom = metrics->bottom;
    }
}

void rsrMeasureTextAlloc(Context *rsc, Script *sc, Allocation *a,
                         int32_t *left, int32_t *right, int32_t *top, int32_t *bottom) {
    CHECK_OBJ(a);
    const char *text = (const char *)a->getPtr();
    size_t textLen = a->getType()->getSizeBytes();
    Font::Rect metrics;
    rsc->mStateFont.measureText(text, textLen, &metrics);
    SetMetrics(&metrics, left, right, top, bottom);
}

void rsrMeasureText(Context *rsc, Script *sc, const char *text,
                    int32_t *left, int32_t *right, int32_t *top, int32_t *bottom) {
    size_t textLen = strlen(text);
    Font::Rect metrics;
    rsc->mStateFont.measureText(text, textLen, &metrics);
    SetMetrics(&metrics, left, right, top, bottom);
}

void rsrBindFont(Context *rsc, Script *sc, Font *font) {
    CHECK_OBJ(font);
    rsi_ContextBindFont(rsc, font);
}

void rsrFontColor(Context *rsc, Script *sc, float r, float g, float b, float a) {
    rsc->mStateFont.setFontColor(r, g, b, a);
}

}
}
