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

#include "rsContext.h"
#include "rsScriptC.h"

#include "utils/Timers.h"

#include <time.h>

namespace android {
namespace renderscript {


//////////////////////////////////////////////////////////////////////////////
// Context
//////////////////////////////////////////////////////////////////////////////

void rsrBindTexture(Context *, Script *, ProgramFragment *, uint32_t slot, Allocation *);
void rsrBindConstant(Context *, Script *, ProgramFragment *, uint32_t slot, Allocation *);
void rsrBindConstant(Context *, Script *, ProgramVertex*, uint32_t slot, Allocation *);
void rsrBindSampler(Context *, Script *, ProgramFragment *, uint32_t slot, Sampler *);
void rsrBindProgramStore(Context *, Script *, ProgramStore *);
void rsrBindProgramFragment(Context *, Script *, ProgramFragment *);
void rsrBindProgramVertex(Context *, Script *, ProgramVertex *);
void rsrBindProgramRaster(Context *, Script *, ProgramRaster *);
void rsrBindFrameBufferObjectColorTarget(Context *, Script *, Allocation *, uint32_t slot);
void rsrBindFrameBufferObjectDepthTarget(Context *, Script *, Allocation *);
void rsrClearFrameBufferObjectColorTarget(Context *, Script *, uint32_t slot);
void rsrClearFrameBufferObjectDepthTarget(Context *, Script *);
void rsrClearFrameBufferObjectTargets(Context *, Script *);

//////////////////////////////////////////////////////////////////////////////
// VP
//////////////////////////////////////////////////////////////////////////////

void rsrVpLoadProjectionMatrix(Context *, Script *, const rsc_Matrix *m);
void rsrVpLoadModelMatrix(Context *, Script *, const rsc_Matrix *m);
void rsrVpLoadTextureMatrix(Context *, Script *, const rsc_Matrix *m);
void rsrPfConstantColor(Context *, Script *, ProgramFragment *, float r, float g, float b, float a);
void rsrVpGetProjectionMatrix(Context *, Script *, rsc_Matrix *m);

//////////////////////////////////////////////////////////////////////////////
// Drawing
//////////////////////////////////////////////////////////////////////////////

void rsrDrawQuadTexCoords(Context *, Script *,
                          float x1, float y1, float z1, float u1, float v1,
                          float x2, float y2, float z2, float u2, float v2,
                          float x3, float y3, float z3, float u3, float v3,
                          float x4, float y4, float z4, float u4, float v4);
void rsrDrawQuad(Context *, Script *,
                 float x1, float y1, float z1,
                 float x2, float y2, float z2,
                 float x3, float y3, float z3,
                 float x4, float y4, float z4);
void rsrDrawSpriteScreenspace(Context *, Script *,
                              float x, float y, float z, float w, float h);
void rsrDrawRect(Context *, Script *, float x1, float y1, float x2, float y2, float z);
void rsrDrawPath(Context *, Script *, Path *);
void rsrDrawMesh(Context *, Script *, Mesh *);
void rsrDrawMeshPrimitive(Context *, Script *, Mesh *, uint32_t primIndex);
void rsrDrawMeshPrimitiveRange(Context *, Script *, Mesh *,
                               uint32_t primIndex, uint32_t start, uint32_t len);
void rsrMeshComputeBoundingBox(Context *, Script *, Mesh *,
                               float *minX, float *minY, float *minZ,
                               float *maxX, float *maxY, float *maxZ);


//////////////////////////////////////////////////////////////////////////////
//
//////////////////////////////////////////////////////////////////////////////


void rsrColor(Context *, Script *, float r, float g, float b, float a);
void rsrFinish(Context *, Script *);
void rsrAllocationSyncAll(Context *, Script *, Allocation *);

void rsrAllocationCopy1DRange(Context *, Allocation *dstAlloc,
                              uint32_t dstOff,
                              uint32_t dstMip,
                              uint32_t count,
                              Allocation *srcAlloc,
                              uint32_t srcOff, uint32_t srcMip);
void rsrAllocationCopy2DRange(Context *, Allocation *dstAlloc,
                              uint32_t dstXoff, uint32_t dstYoff,
                              uint32_t dstMip, uint32_t dstFace,
                              uint32_t width, uint32_t height,
                              Allocation *srcAlloc,
                              uint32_t srcXoff, uint32_t srcYoff,
                              uint32_t srcMip, uint32_t srcFace);

void rsrClearColor(Context *, Script *, float r, float g, float b, float a);
void rsrClearDepth(Context *, Script *, float v);
uint32_t rsrGetWidth(Context *, Script *);
uint32_t rsrGetHeight(Context *, Script *);
void rsrDrawTextAlloc(Context *, Script *, Allocation *, int x, int y);
void rsrDrawText(Context *, Script *, const char *text, int x, int y);
void rsrSetMetrics(Context *, Script *, Font::Rect *metrics,
                   int32_t *left, int32_t *right, int32_t *top, int32_t *bottom);
void rsrMeasureTextAlloc(Context *, Script *, Allocation *,
                         int32_t *left, int32_t *right, int32_t *top, int32_t *bottom);
void rsrMeasureText(Context *, Script *, const char *text,
                    int32_t *left, int32_t *right, int32_t *top, int32_t *bottom);
void rsrBindFont(Context *, Script *, Font *);
void rsrFontColor(Context *, Script *, float r, float g, float b, float a);

//////////////////////////////////////////////////////////////////////////////
// Time routines
//////////////////////////////////////////////////////////////////////////////

float rsrGetDt(Context *, Script *);
time_t rsrTime(Context *, Script *, time_t *timer);
tm* rsrLocalTime(Context *, Script *, tm *local, time_t *timer);
int64_t rsrUptimeMillis(Context *, Script *);
int64_t rsrUptimeNanos(Context *, Script *);

//////////////////////////////////////////////////////////////////////////////
// Message routines
//////////////////////////////////////////////////////////////////////////////

uint32_t rsrToClient(Context *, Script *, int cmdID, void *data, int len);
uint32_t rsrToClientBlocking(Context *, Script *, int cmdID, void *data, int len);

//////////////////////////////////////////////////////////////////////////////
//
//////////////////////////////////////////////////////////////////////////////

void rsrSetObject(const Context *, const Script *, ObjectBase **dst, ObjectBase * src);
void rsrClearObject(const Context *, const Script *, ObjectBase **dst);
bool rsrIsObject(const Context *, const Script *, const ObjectBase *src);

void rsrAllocationIncRefs(const Context *, const Allocation *, void *ptr,
                          size_t elementCount, size_t startOffset);
void rsrAllocationDecRefs(const Context *, const Allocation *, void *ptr,
                          size_t elementCount, size_t startOffset);


uint32_t rsrToClient(Context *, Script *, int cmdID, void *data, int len);
uint32_t rsrToClientBlocking(Context *, Script *, int cmdID, void *data, int len);
const Allocation * rsrGetAllocation(Context *, Script *, const void *ptr);

void rsrAllocationMarkDirty(Context *, Script *, RsAllocation a);
void rsrAllocationSyncAll(Context *, Script *, Allocation *a, RsAllocationUsageType source);


void rsrForEach(Context *, Script *, Script *target,
                Allocation *in,
                Allocation *out,
                const void *usr,
                 uint32_t usrBytes,
                const RsScriptCall *call);


//////////////////////////////////////////////////////////////////////////////
// Heavy math functions
//////////////////////////////////////////////////////////////////////////////


void rsrMatrixSet(rs_matrix4x4 *m, uint32_t row, uint32_t col, float v);
float rsrMatrixGet(const rs_matrix4x4 *m, uint32_t row, uint32_t col);
void rsrMatrixSet(rs_matrix3x3 *m, uint32_t row, uint32_t col, float v);
float rsrMatrixGet(const rs_matrix3x3 *m, uint32_t row, uint32_t col);
void rsrMatrixSet(rs_matrix2x2 *m, uint32_t row, uint32_t col, float v);
float rsrMatrixGet(const rs_matrix2x2 *m, uint32_t row, uint32_t col);
void rsrMatrixLoadIdentity_4x4(rs_matrix4x4 *m);
void rsrMatrixLoadIdentity_3x3(rs_matrix3x3 *m);
void rsrMatrixLoadIdentity_2x2(rs_matrix2x2 *m);
void rsrMatrixLoad_4x4_f(rs_matrix4x4 *m, const float *v);
void rsrMatrixLoad_3x3_f(rs_matrix3x3 *m, const float *v);
void rsrMatrixLoad_2x2_f(rs_matrix2x2 *m, const float *v);
void rsrMatrixLoad_4x4_4x4(rs_matrix4x4 *m, const rs_matrix4x4 *v);
void rsrMatrixLoad_4x4_3x3(rs_matrix4x4 *m, const rs_matrix3x3 *v);
void rsrMatrixLoad_4x4_2x2(rs_matrix4x4 *m, const rs_matrix2x2 *v);
void rsrMatrixLoad_3x3_3x3(rs_matrix3x3 *m, const rs_matrix3x3 *v);
void rsrMatrixLoad_2x2_2x2(rs_matrix2x2 *m, const rs_matrix2x2 *v);
void rsrMatrixLoadRotate(rs_matrix4x4 *m, float rot, float x, float y, float z);
void rsrMatrixLoadScale(rs_matrix4x4 *m, float x, float y, float z);
void rsrMatrixLoadTranslate(rs_matrix4x4 *m, float x, float y, float z);
void rsrMatrixLoadMultiply_4x4_4x4_4x4(rs_matrix4x4 *m, const rs_matrix4x4 *lhs,
                                       const rs_matrix4x4 *rhs);
void rsrMatrixMultiply_4x4_4x4(rs_matrix4x4 *m, const rs_matrix4x4 *rhs);
void rsrMatrixLoadMultiply_3x3_3x3_3x3(rs_matrix3x3 *m, const rs_matrix3x3 *lhs,
                                       const rs_matrix3x3 *rhs);
void rsrMatrixMultiply_3x3_3x3(rs_matrix3x3 *m, const rs_matrix3x3 *rhs);
void rsrMatrixLoadMultiply_2x2_2x2_2x2(rs_matrix2x2 *m, const rs_matrix2x2 *lhs,
                                       const rs_matrix2x2 *rhs);
void rsrMatrixMultiply_2x2_2x2(rs_matrix2x2 *m, const rs_matrix2x2 *rhs);
void rsrMatrixRotate(rs_matrix4x4 *m, float rot, float x, float y, float z);
void rsrMatrixScale(rs_matrix4x4 *m, float x, float y, float z);
void rsrMatrixTranslate(rs_matrix4x4 *m, float x, float y, float z);
void rsrMatrixLoadOrtho(rs_matrix4x4 *m, float left, float right,
                        float bottom, float top, float near, float far);
void rsrMatrixLoadFrustum(rs_matrix4x4 *m, float left, float right,
                          float bottom, float top, float near, float far);
void rsrMatrixLoadPerspective(rs_matrix4x4* m, float fovy, float aspect, float near, float far);

// Returns true if the matrix was successfully inversed
bool rsrMatrixInverse_4x4(rs_matrix4x4 *m);
// Returns true if the matrix was successfully inversed
bool rsrMatrixInverseTranspose_4x4(rs_matrix4x4 *m);

void rsrMatrixTranspose_4x4(rs_matrix4x4 *m);
void rsrMatrixTranspose_3x3(rs_matrix3x3 *m);
void rsrMatrixTranspose_2x2(rs_matrix2x2 *m);

}
}
