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
#include "rsRuntime.h"

#include "utils/Timers.h"
#include "rsdCore.h"

#include "rsdRuntime.h"

#include <time.h>

using namespace android;
using namespace android::renderscript;

#define GET_TLS()  ScriptTLSStruct * tls = \
    (ScriptTLSStruct *)pthread_getspecific(rsdgThreadTLSKey); \
    Context * rsc = tls->mContext; \
    ScriptC * sc = (ScriptC *) tls->mScript



//////////////////////////////////////////////////////////////////////////////
// Allocation
//////////////////////////////////////////////////////////////////////////////

static uint32_t SC_allocGetDimX(Allocation *a) {
    return a->mHal.state.dimensionX;
}

static uint32_t SC_allocGetDimY(Allocation *a) {
    return a->mHal.state.dimensionY;
}

static uint32_t SC_allocGetDimZ(Allocation *a) {
    return a->mHal.state.dimensionZ;
}

static uint32_t SC_allocGetDimLOD(Allocation *a) {
    return a->mHal.state.hasMipmaps;
}

static uint32_t SC_allocGetDimFaces(Allocation *a) {
    return a->mHal.state.hasFaces;
}

static const void * SC_getElementAtX(Allocation *a, uint32_t x) {
    const uint8_t *p = (const uint8_t *)a->getPtr();
    return &p[a->mHal.state.elementSizeBytes * x];
}

static const void * SC_getElementAtXY(Allocation *a, uint32_t x, uint32_t y) {
    const uint8_t *p = (const uint8_t *)a->getPtr();
    return &p[a->mHal.state.elementSizeBytes * (x + y * a->mHal.state.dimensionX)];
}

static const void * SC_getElementAtXYZ(Allocation *a, uint32_t x, uint32_t y, uint32_t z) {
    const uint8_t *p = (const uint8_t *)a->getPtr();
    return &p[a->mHal.state.elementSizeBytes * (x + y * a->mHal.state.dimensionX +
              z * a->mHal.state.dimensionX * a->mHal.state.dimensionY)];
}

static void SC_AllocationSyncAll2(Allocation *a, RsAllocationUsageType source) {
    GET_TLS();
    rsrAllocationSyncAll(rsc, sc, a, source);
}

static void SC_AllocationSyncAll(Allocation *a) {
    GET_TLS();
    rsrAllocationSyncAll(rsc, sc, a, RS_ALLOCATION_USAGE_SCRIPT);
}

static void SC_AllocationCopy1DRange(Allocation *dstAlloc,
                                     uint32_t dstOff,
                                     uint32_t dstMip,
                                     uint32_t count,
                                     Allocation *srcAlloc,
                                     uint32_t srcOff, uint32_t srcMip) {
    GET_TLS();
    rsrAllocationCopy1DRange(rsc, dstAlloc, dstOff, dstMip, count,
                             srcAlloc, srcOff, srcMip);
}

static void SC_AllocationCopy2DRange(Allocation *dstAlloc,
                                     uint32_t dstXoff, uint32_t dstYoff,
                                     uint32_t dstMip, uint32_t dstFace,
                                     uint32_t width, uint32_t height,
                                     Allocation *srcAlloc,
                                     uint32_t srcXoff, uint32_t srcYoff,
                                     uint32_t srcMip, uint32_t srcFace) {
    GET_TLS();
    rsrAllocationCopy2DRange(rsc, dstAlloc,
                             dstXoff, dstYoff, dstMip, dstFace,
                             width, height,
                             srcAlloc,
                             srcXoff, srcYoff, srcMip, srcFace);
}


const Allocation * SC_getAllocation(const void *ptr) {
    GET_TLS();
    return rsrGetAllocation(rsc, sc, ptr);
}


//////////////////////////////////////////////////////////////////////////////
// Context
//////////////////////////////////////////////////////////////////////////////

static void SC_BindTexture(ProgramFragment *pf, uint32_t slot, Allocation *a) {
    GET_TLS();
    rsrBindTexture(rsc, sc, pf, slot, a);
}

static void SC_BindSampler(ProgramFragment *pf, uint32_t slot, Sampler *s) {
    GET_TLS();
    rsrBindSampler(rsc, sc, pf, slot, s);
}

static void SC_BindProgramStore(ProgramStore *ps) {
    GET_TLS();
    rsrBindProgramStore(rsc, sc, ps);
}

static void SC_BindProgramFragment(ProgramFragment *pf) {
    GET_TLS();
    rsrBindProgramFragment(rsc, sc, pf);
}

static void SC_BindProgramVertex(ProgramVertex *pv) {
    GET_TLS();
    rsrBindProgramVertex(rsc, sc, pv);
}

static void SC_BindProgramRaster(ProgramRaster *pr) {
    GET_TLS();
    rsrBindProgramRaster(rsc, sc, pr);
}

static void SC_BindFrameBufferObjectColorTarget(Allocation *a, uint32_t slot) {
    GET_TLS();
    rsrBindFrameBufferObjectColorTarget(rsc, sc, a, slot);
}

static void SC_BindFrameBufferObjectDepthTarget(Allocation *a) {
    GET_TLS();
    rsrBindFrameBufferObjectDepthTarget(rsc, sc, a);
}

static void SC_ClearFrameBufferObjectColorTarget(uint32_t slot) {
    GET_TLS();
    rsrClearFrameBufferObjectColorTarget(rsc, sc, slot);
}

static void SC_ClearFrameBufferObjectDepthTarget(Context *, Script *) {
    GET_TLS();
    rsrClearFrameBufferObjectDepthTarget(rsc, sc);
}

static void SC_ClearFrameBufferObjectTargets(Context *, Script *) {
    GET_TLS();
    rsrClearFrameBufferObjectTargets(rsc, sc);
}


//////////////////////////////////////////////////////////////////////////////
// VP
//////////////////////////////////////////////////////////////////////////////

static void SC_VpLoadProjectionMatrix(const rsc_Matrix *m) {
    GET_TLS();
    rsrVpLoadProjectionMatrix(rsc, sc, m);
}

static void SC_VpLoadModelMatrix(const rsc_Matrix *m) {
    GET_TLS();
    rsrVpLoadModelMatrix(rsc, sc, m);
}

static void SC_VpLoadTextureMatrix(const rsc_Matrix *m) {
    GET_TLS();
    rsrVpLoadTextureMatrix(rsc, sc, m);
}

static void SC_PfConstantColor(ProgramFragment *pf, float r, float g, float b, float a) {
    GET_TLS();
    rsrPfConstantColor(rsc, sc, pf, r, g, b, a);
}

static void SC_VpGetProjectionMatrix(rsc_Matrix *m) {
    GET_TLS();
    rsrVpGetProjectionMatrix(rsc, sc, m);
}


//////////////////////////////////////////////////////////////////////////////
// Drawing
//////////////////////////////////////////////////////////////////////////////

static void SC_DrawQuadTexCoords(float x1, float y1, float z1, float u1, float v1,
                                 float x2, float y2, float z2, float u2, float v2,
                                 float x3, float y3, float z3, float u3, float v3,
                                 float x4, float y4, float z4, float u4, float v4) {
    GET_TLS();
    rsrDrawQuadTexCoords(rsc, sc,
                         x1, y1, z1, u1, v1,
                         x2, y2, z2, u2, v2,
                         x3, y3, z3, u3, v3,
                         x4, y4, z4, u4, v4);
}

static void SC_DrawQuad(float x1, float y1, float z1,
                        float x2, float y2, float z2,
                        float x3, float y3, float z3,
                        float x4, float y4, float z4) {
    GET_TLS();
    rsrDrawQuad(rsc, sc, x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4);
}

static void SC_DrawSpriteScreenspace(float x, float y, float z, float w, float h) {
    GET_TLS();
    rsrDrawSpriteScreenspace(rsc, sc, x, y, z, w, h);
}

static void SC_DrawRect(float x1, float y1, float x2, float y2, float z) {
    GET_TLS();
    rsrDrawRect(rsc, sc, x1, y1, x2, y2, z);
}

static void SC_DrawMesh(Mesh *m) {
    GET_TLS();
    rsrDrawMesh(rsc, sc, m);
}

static void SC_DrawMeshPrimitive(Mesh *m, uint32_t primIndex) {
    GET_TLS();
    rsrDrawMeshPrimitive(rsc, sc, m, primIndex);
}

static void SC_DrawMeshPrimitiveRange(Mesh *m, uint32_t primIndex, uint32_t start, uint32_t len) {
    GET_TLS();
    rsrDrawMeshPrimitiveRange(rsc, sc, m, primIndex, start, len);
}

static void SC_MeshComputeBoundingBox(Mesh *m,
                               float *minX, float *minY, float *minZ,
                               float *maxX, float *maxY, float *maxZ) {
    GET_TLS();
    rsrMeshComputeBoundingBox(rsc, sc, m, minX, minY, minZ, maxX, maxY, maxZ);
}



//////////////////////////////////////////////////////////////////////////////
//
//////////////////////////////////////////////////////////////////////////////


static void SC_Color(float r, float g, float b, float a) {
    GET_TLS();
    rsrColor(rsc, sc, r, g, b, a);
}

static void SC_Finish() {
    GET_TLS();
    rsrFinish(rsc, sc);
}

static void SC_ClearColor(float r, float g, float b, float a) {
    GET_TLS();
    rsrClearColor(rsc, sc, r, g, b, a);
}

static void SC_ClearDepth(float v) {
    GET_TLS();
    rsrClearDepth(rsc, sc, v);
}

static uint32_t SC_GetWidth() {
    GET_TLS();
    return rsrGetWidth(rsc, sc);
}

static uint32_t SC_GetHeight() {
    GET_TLS();
    return rsrGetHeight(rsc, sc);
}

static void SC_DrawTextAlloc(Allocation *a, int x, int y) {
    GET_TLS();
    rsrDrawTextAlloc(rsc, sc, a, x, y);
}

static void SC_DrawText(const char *text, int x, int y) {
    GET_TLS();
    rsrDrawText(rsc, sc, text, x, y);
}

static void SC_MeasureTextAlloc(Allocation *a,
                         int32_t *left, int32_t *right, int32_t *top, int32_t *bottom) {
    GET_TLS();
    rsrMeasureTextAlloc(rsc, sc, a, left, right, top, bottom);
}

static void SC_MeasureText(const char *text,
                    int32_t *left, int32_t *right, int32_t *top, int32_t *bottom) {
    GET_TLS();
    rsrMeasureText(rsc, sc, text, left, right, top, bottom);
}

static void SC_BindFont(Font *f) {
    GET_TLS();
    rsrBindFont(rsc, sc, f);
}

static void SC_FontColor(float r, float g, float b, float a) {
    GET_TLS();
    rsrFontColor(rsc, sc, r, g, b, a);
}



//////////////////////////////////////////////////////////////////////////////
//
//////////////////////////////////////////////////////////////////////////////

static void SC_SetObject(ObjectBase **dst, ObjectBase * src) {
    GET_TLS();
    rsrSetObject(rsc, sc, dst, src);
}

static void SC_ClearObject(ObjectBase **dst) {
    GET_TLS();
    rsrClearObject(rsc, sc, dst);
}

static bool SC_IsObject(const ObjectBase *src) {
    GET_TLS();
    return rsrIsObject(rsc, sc, src);
}




static const Allocation * SC_GetAllocation(const void *ptr) {
    GET_TLS();
    return rsrGetAllocation(rsc, sc, ptr);
}

static void SC_ForEach_SAA(Script *target,
                            Allocation *in,
                            Allocation *out) {
    GET_TLS();
    rsrForEach(rsc, sc, target, in, out, NULL, 0, NULL);
}

static void SC_ForEach_SAAU(Script *target,
                            Allocation *in,
                            Allocation *out,
                            const void *usr) {
    GET_TLS();
    rsrForEach(rsc, sc, target, in, out, usr, 0, NULL);
}

static void SC_ForEach_SAAUS(Script *target,
                             Allocation *in,
                             Allocation *out,
                             const void *usr,
                             const RsScriptCall *call) {
    GET_TLS();
    rsrForEach(rsc, sc, target, in, out, usr, 0, call);
}

static void SC_ForEach_SAAUL(Script *target,
                             Allocation *in,
                             Allocation *out,
                             const void *usr,
                             uint32_t usrLen) {
    GET_TLS();
    rsrForEach(rsc, sc, target, in, out, usr, usrLen, NULL);
}

static void SC_ForEach_SAAULS(Script *target,
                              Allocation *in,
                              Allocation *out,
                              const void *usr,
                              uint32_t usrLen,
                              const RsScriptCall *call) {
    GET_TLS();
    rsrForEach(rsc, sc, target, in, out, usr, usrLen, call);
}



//////////////////////////////////////////////////////////////////////////////
// Time routines
//////////////////////////////////////////////////////////////////////////////

static float SC_GetDt() {
    GET_TLS();
    return rsrGetDt(rsc, sc);
}

time_t SC_Time(time_t *timer) {
    GET_TLS();
    return rsrTime(rsc, sc, timer);
}

tm* SC_LocalTime(tm *local, time_t *timer) {
    GET_TLS();
    return rsrLocalTime(rsc, sc, local, timer);
}

int64_t SC_UptimeMillis() {
    GET_TLS();
    return rsrUptimeMillis(rsc, sc);
}

int64_t SC_UptimeNanos() {
    GET_TLS();
    return rsrUptimeNanos(rsc, sc);
}

//////////////////////////////////////////////////////////////////////////////
// Message routines
//////////////////////////////////////////////////////////////////////////////

static uint32_t SC_ToClient2(int cmdID, void *data, int len) {
    GET_TLS();
    return rsrToClient(rsc, sc, cmdID, data, len);
}

static uint32_t SC_ToClient(int cmdID) {
    GET_TLS();
    return rsrToClient(rsc, sc, cmdID, NULL, 0);
}

static uint32_t SC_ToClientBlocking2(int cmdID, void *data, int len) {
    GET_TLS();
    return rsrToClientBlocking(rsc, sc, cmdID, data, len);
}

static uint32_t SC_ToClientBlocking(int cmdID) {
    GET_TLS();
    return rsrToClientBlocking(rsc, sc, cmdID, NULL, 0);
}

int SC_divsi3(int a, int b) {
    return a / b;
}

int SC_modsi3(int a, int b) {
    return a % b;
}

unsigned int SC_udivsi3(unsigned int a, unsigned int b) {
    return a / b;
}

unsigned int SC_umodsi3(unsigned int a, unsigned int b) {
    return a % b;
}

static void SC_debugF(const char *s, float f) {
    LOGD("%s %f, 0x%08x", s, f, *((int *) (&f)));
}
static void SC_debugFv2(const char *s, float f1, float f2) {
    LOGD("%s {%f, %f}", s, f1, f2);
}
static void SC_debugFv3(const char *s, float f1, float f2, float f3) {
    LOGD("%s {%f, %f, %f}", s, f1, f2, f3);
}
static void SC_debugFv4(const char *s, float f1, float f2, float f3, float f4) {
    LOGD("%s {%f, %f, %f, %f}", s, f1, f2, f3, f4);
}
static void SC_debugD(const char *s, double d) {
    LOGD("%s %f, 0x%08llx", s, d, *((long long *) (&d)));
}
static void SC_debugFM4v4(const char *s, const float *f) {
    LOGD("%s {%f, %f, %f, %f", s, f[0], f[4], f[8], f[12]);
    LOGD("%s  %f, %f, %f, %f", s, f[1], f[5], f[9], f[13]);
    LOGD("%s  %f, %f, %f, %f", s, f[2], f[6], f[10], f[14]);
    LOGD("%s  %f, %f, %f, %f}", s, f[3], f[7], f[11], f[15]);
}
static void SC_debugFM3v3(const char *s, const float *f) {
    LOGD("%s {%f, %f, %f", s, f[0], f[3], f[6]);
    LOGD("%s  %f, %f, %f", s, f[1], f[4], f[7]);
    LOGD("%s  %f, %f, %f}",s, f[2], f[5], f[8]);
}
static void SC_debugFM2v2(const char *s, const float *f) {
    LOGD("%s {%f, %f", s, f[0], f[2]);
    LOGD("%s  %f, %f}",s, f[1], f[3]);
}

static void SC_debugI32(const char *s, int32_t i) {
    LOGD("%s %i  0x%x", s, i, i);
}
static void SC_debugU32(const char *s, uint32_t i) {
    LOGD("%s %u  0x%x", s, i, i);
}
static void SC_debugLL64(const char *s, long long ll) {
    LOGD("%s %lld  0x%llx", s, ll, ll);
}
static void SC_debugULL64(const char *s, unsigned long long ll) {
    LOGD("%s %llu  0x%llx", s, ll, ll);
}

static void SC_debugP(const char *s, const void *p) {
    LOGD("%s %p", s, p);
}


//////////////////////////////////////////////////////////////////////////////
// Stub implementation
//////////////////////////////////////////////////////////////////////////////

// llvm name mangling ref
//  <builtin-type> ::= v  # void
//                 ::= b  # bool
//                 ::= c  # char
//                 ::= a  # signed char
//                 ::= h  # unsigned char
//                 ::= s  # short
//                 ::= t  # unsigned short
//                 ::= i  # int
//                 ::= j  # unsigned int
//                 ::= l  # long
//                 ::= m  # unsigned long
//                 ::= x  # long long, __int64
//                 ::= y  # unsigned long long, __int64
//                 ::= f  # float
//                 ::= d  # double

static RsdSymbolTable gSyms[] = {
    { "memset", (void *)&memset, true },
    { "memcpy", (void *)&memcpy, true },

    // Refcounting
    { "_Z11rsSetObjectP10rs_elementS_", (void *)&SC_SetObject, true },
    { "_Z13rsClearObjectP10rs_element", (void *)&SC_ClearObject, true },
    { "_Z10rsIsObject10rs_element", (void *)&SC_IsObject, true },

    { "_Z11rsSetObjectP7rs_typeS_", (void *)&SC_SetObject, true },
    { "_Z13rsClearObjectP7rs_type", (void *)&SC_ClearObject, true },
    { "_Z10rsIsObject7rs_type", (void *)&SC_IsObject, true },

    { "_Z11rsSetObjectP13rs_allocationS_", (void *)&SC_SetObject, true },
    { "_Z13rsClearObjectP13rs_allocation", (void *)&SC_ClearObject, true },
    { "_Z10rsIsObject13rs_allocation", (void *)&SC_IsObject, true },

    { "_Z11rsSetObjectP10rs_samplerS_", (void *)&SC_SetObject, true },
    { "_Z13rsClearObjectP10rs_sampler", (void *)&SC_ClearObject, true },
    { "_Z10rsIsObject10rs_sampler", (void *)&SC_IsObject, true },

    { "_Z11rsSetObjectP9rs_scriptS_", (void *)&SC_SetObject, true },
    { "_Z13rsClearObjectP9rs_script", (void *)&SC_ClearObject, true },
    { "_Z10rsIsObject9rs_script", (void *)&SC_IsObject, true },

    { "_Z11rsSetObjectP7rs_meshS_", (void *)&SC_SetObject, true },
    { "_Z13rsClearObjectP7rs_mesh", (void *)&SC_ClearObject, true },
    { "_Z10rsIsObject7rs_mesh", (void *)&SC_IsObject, true },

    { "_Z11rsSetObjectP19rs_program_fragmentS_", (void *)&SC_SetObject, true },
    { "_Z13rsClearObjectP19rs_program_fragment", (void *)&SC_ClearObject, true },
    { "_Z10rsIsObject19rs_program_fragment", (void *)&SC_IsObject, true },

    { "_Z11rsSetObjectP17rs_program_vertexS_", (void *)&SC_SetObject, true },
    { "_Z13rsClearObjectP17rs_program_vertex", (void *)&SC_ClearObject, true },
    { "_Z10rsIsObject17rs_program_vertex", (void *)&SC_IsObject, true },

    { "_Z11rsSetObjectP17rs_program_rasterS_", (void *)&SC_SetObject, true },
    { "_Z13rsClearObjectP17rs_program_raster", (void *)&SC_ClearObject, true },
    { "_Z10rsIsObject17rs_program_raster", (void *)&SC_IsObject, true },

    { "_Z11rsSetObjectP16rs_program_storeS_", (void *)&SC_SetObject, true },
    { "_Z13rsClearObjectP16rs_program_store", (void *)&SC_ClearObject, true },
    { "_Z10rsIsObject16rs_program_store", (void *)&SC_IsObject, true },

    { "_Z11rsSetObjectP7rs_fontS_", (void *)&SC_SetObject, true },
    { "_Z13rsClearObjectP7rs_font", (void *)&SC_ClearObject, true },
    { "_Z10rsIsObject7rs_font", (void *)&SC_IsObject, true },

    // Allocation ops
    { "_Z19rsAllocationGetDimX13rs_allocation", (void *)&SC_allocGetDimX, true },
    { "_Z19rsAllocationGetDimY13rs_allocation", (void *)&SC_allocGetDimY, true },
    { "_Z19rsAllocationGetDimZ13rs_allocation", (void *)&SC_allocGetDimZ, true },
    { "_Z21rsAllocationGetDimLOD13rs_allocation", (void *)&SC_allocGetDimLOD, true },
    { "_Z23rsAllocationGetDimFaces13rs_allocation", (void *)&SC_allocGetDimFaces, true },

    { "_Z14rsGetElementAt13rs_allocationj", (void *)&SC_getElementAtX, true },
    { "_Z14rsGetElementAt13rs_allocationjj", (void *)&SC_getElementAtXY, true },
    { "_Z14rsGetElementAt13rs_allocationjjj", (void *)&SC_getElementAtXYZ, true },

    { "_Z15rsGetAllocationPKv", (void *)&SC_getAllocation, true },

    { "_Z21rsAllocationMarkDirty13rs_allocation", (void *)&SC_AllocationSyncAll, true },
    { "_Z20rsgAllocationSyncAll13rs_allocation", (void *)&SC_AllocationSyncAll, false },
    { "_Z20rsgAllocationSyncAll13rs_allocationj", (void *)&SC_AllocationSyncAll2, false },
    { "_Z20rsgAllocationSyncAll13rs_allocation24rs_allocation_usage_type", (void *)&SC_AllocationSyncAll2, false },
    { "_Z15rsGetAllocationPKv", (void *)&SC_GetAllocation, true },

    { "_Z23rsAllocationCopy1DRange13rs_allocationjjjS_jj", (void *)&SC_AllocationCopy1DRange, false },
    { "_Z23rsAllocationCopy2DRange13rs_allocationjjj26rs_allocation_cubemap_facejjS_jjjS0_", (void *)&SC_AllocationCopy2DRange, false },

    // Messaging

    { "_Z14rsSendToClienti", (void *)&SC_ToClient, false },
    { "_Z14rsSendToClientiPKvj", (void *)&SC_ToClient2, false },
    { "_Z22rsSendToClientBlockingi", (void *)&SC_ToClientBlocking, false },
    { "_Z22rsSendToClientBlockingiPKvj", (void *)&SC_ToClientBlocking2, false },

    { "_Z22rsgBindProgramFragment19rs_program_fragment", (void *)&SC_BindProgramFragment, false },
    { "_Z19rsgBindProgramStore16rs_program_store", (void *)&SC_BindProgramStore, false },
    { "_Z20rsgBindProgramVertex17rs_program_vertex", (void *)&SC_BindProgramVertex, false },
    { "_Z20rsgBindProgramRaster17rs_program_raster", (void *)&SC_BindProgramRaster, false },
    { "_Z14rsgBindSampler19rs_program_fragmentj10rs_sampler", (void *)&SC_BindSampler, false },
    { "_Z14rsgBindTexture19rs_program_fragmentj13rs_allocation", (void *)&SC_BindTexture, false },

    { "_Z36rsgProgramVertexLoadProjectionMatrixPK12rs_matrix4x4", (void *)&SC_VpLoadProjectionMatrix, false },
    { "_Z31rsgProgramVertexLoadModelMatrixPK12rs_matrix4x4", (void *)&SC_VpLoadModelMatrix, false },
    { "_Z33rsgProgramVertexLoadTextureMatrixPK12rs_matrix4x4", (void *)&SC_VpLoadTextureMatrix, false },

    { "_Z35rsgProgramVertexGetProjectionMatrixP12rs_matrix4x4", (void *)&SC_VpGetProjectionMatrix, false },

    { "_Z31rsgProgramFragmentConstantColor19rs_program_fragmentffff", (void *)&SC_PfConstantColor, false },

    { "_Z11rsgGetWidthv", (void *)&SC_GetWidth, false },
    { "_Z12rsgGetHeightv", (void *)&SC_GetHeight, false },


    { "_Z11rsgDrawRectfffff", (void *)&SC_DrawRect, false },
    { "_Z11rsgDrawQuadffffffffffff", (void *)&SC_DrawQuad, false },
    { "_Z20rsgDrawQuadTexCoordsffffffffffffffffffff", (void *)&SC_DrawQuadTexCoords, false },
    { "_Z24rsgDrawSpriteScreenspacefffff", (void *)&SC_DrawSpriteScreenspace, false },

    { "_Z11rsgDrawMesh7rs_mesh", (void *)&SC_DrawMesh, false },
    { "_Z11rsgDrawMesh7rs_meshj", (void *)&SC_DrawMeshPrimitive, false },
    { "_Z11rsgDrawMesh7rs_meshjjj", (void *)&SC_DrawMeshPrimitiveRange, false },
    { "_Z25rsgMeshComputeBoundingBox7rs_meshPfS0_S0_S0_S0_S0_", (void *)&SC_MeshComputeBoundingBox, false },

    { "_Z13rsgClearColorffff", (void *)&SC_ClearColor, false },
    { "_Z13rsgClearDepthf", (void *)&SC_ClearDepth, false },

    { "_Z11rsgDrawTextPKcii", (void *)&SC_DrawText, false },
    { "_Z11rsgDrawText13rs_allocationii", (void *)&SC_DrawTextAlloc, false },
    { "_Z14rsgMeasureTextPKcPiS1_S1_S1_", (void *)&SC_MeasureText, false },
    { "_Z14rsgMeasureText13rs_allocationPiS0_S0_S0_", (void *)&SC_MeasureTextAlloc, false },

    { "_Z11rsgBindFont7rs_font", (void *)&SC_BindFont, false },
    { "_Z12rsgFontColorffff", (void *)&SC_FontColor, false },

    { "_Z18rsgBindColorTarget13rs_allocationj", (void *)&SC_BindFrameBufferObjectColorTarget, false },
    { "_Z18rsgBindDepthTarget13rs_allocation", (void *)&SC_BindFrameBufferObjectDepthTarget, false },
    { "_Z19rsgClearColorTargetj", (void *)&SC_ClearFrameBufferObjectColorTarget, false },
    { "_Z19rsgClearDepthTargetv", (void *)&SC_ClearFrameBufferObjectDepthTarget, false },
    { "_Z24rsgClearAllRenderTargetsv", (void *)&SC_ClearFrameBufferObjectTargets, false },

    { "_Z9rsForEach9rs_script13rs_allocationS0_", (void *)&SC_ForEach_SAA, false },
    { "_Z9rsForEach9rs_script13rs_allocationS0_PKv", (void *)&SC_ForEach_SAAU, false },
    { "_Z9rsForEach9rs_script13rs_allocationS0_PKvPK16rs_script_call_t", (void *)&SC_ForEach_SAAUS, false },
    { "_Z9rsForEach9rs_script13rs_allocationS0_PKvj", (void *)&SC_ForEach_SAAUL, false },
    { "_Z9rsForEach9rs_script13rs_allocationS0_PKvjPK16rs_script_call_t", (void *)&SC_ForEach_SAAULS, false },

    // time
    { "_Z6rsTimePi", (void *)&SC_Time, true },
    { "_Z11rsLocaltimeP5rs_tmPKi", (void *)&SC_LocalTime, true },
    { "_Z14rsUptimeMillisv", (void*)&SC_UptimeMillis, true },
    { "_Z13rsUptimeNanosv", (void*)&SC_UptimeNanos, true },
    { "_Z7rsGetDtv", (void*)&SC_GetDt, false },

    // misc
    { "_Z5colorffff", (void *)&SC_Color, false },
    { "_Z9rsgFinishv", (void *)&SC_Finish, false },

    // Debug
    { "_Z7rsDebugPKcf", (void *)&SC_debugF, true },
    { "_Z7rsDebugPKcff", (void *)&SC_debugFv2, true },
    { "_Z7rsDebugPKcfff", (void *)&SC_debugFv3, true },
    { "_Z7rsDebugPKcffff", (void *)&SC_debugFv4, true },
    { "_Z7rsDebugPKcd", (void *)&SC_debugD, true },
    { "_Z7rsDebugPKcPK12rs_matrix4x4", (void *)&SC_debugFM4v4, true },
    { "_Z7rsDebugPKcPK12rs_matrix3x3", (void *)&SC_debugFM3v3, true },
    { "_Z7rsDebugPKcPK12rs_matrix2x2", (void *)&SC_debugFM2v2, true },
    { "_Z7rsDebugPKci", (void *)&SC_debugI32, true },
    { "_Z7rsDebugPKcj", (void *)&SC_debugU32, true },
    // Both "long" and "unsigned long" need to be redirected to their
    // 64-bit counterparts, since we have hacked Slang to use 64-bit
    // for "long" on Arm (to be similar to Java).
    { "_Z7rsDebugPKcl", (void *)&SC_debugLL64, true },
    { "_Z7rsDebugPKcm", (void *)&SC_debugULL64, true },
    { "_Z7rsDebugPKcx", (void *)&SC_debugLL64, true },
    { "_Z7rsDebugPKcy", (void *)&SC_debugULL64, true },
    { "_Z7rsDebugPKcPKv", (void *)&SC_debugP, true },

    { NULL, NULL, false }
};


void* rsdLookupRuntimeStub(void* pContext, char const* name) {
    ScriptC *s = (ScriptC *)pContext;
    if (!strcmp(name, "__isThreadable")) {
      return (void*) s->mHal.info.isThreadable;
    } else if (!strcmp(name, "__clearThreadable")) {
      s->mHal.info.isThreadable = false;
      return NULL;
    }

    RsdSymbolTable *syms = gSyms;
    const RsdSymbolTable *sym = rsdLookupSymbolMath(name);

    if (!sym) {
        while (syms->mPtr) {
            if (!strcmp(syms->mName, name)) {
                sym = syms;
            }
            syms++;
        }
    }

    if (sym) {
        s->mHal.info.isThreadable &= sym->threadable;
        return sym->mPtr;
    }
    LOGE("ScriptC sym lookup failed for %s", name);
    return NULL;
}


