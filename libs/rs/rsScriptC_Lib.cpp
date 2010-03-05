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
#include "rsMatrix.h"
#include "rsNoise.h"

#include "acc/acc.h"
#include "utils/Timers.h"

#define GL_GLEXT_PROTOTYPES

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <time.h>

using namespace android;
using namespace android::renderscript;

#define GET_TLS()  Context::ScriptTLSStruct * tls = \
    (Context::ScriptTLSStruct *)pthread_getspecific(Context::gThreadTLSKey); \
    Context * rsc = tls->mContext; \
    ScriptC * sc = (ScriptC *) tls->mScript

typedef struct {
    float x;
    float y;
    float z;
} vec3_t;

typedef struct {
    float x;
    float y;
    float z;
    float w;
} vec4_t;

typedef struct {
    float x;
    float y;
} vec2_t;

//////////////////////////////////////////////////////////////////////////////
// IO routines
//////////////////////////////////////////////////////////////////////////////

static float SC_loadF(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const float *f = static_cast<const float *>(vp);
    //LOGE("loadF %i %i = %f %x", bank, offset, f, ((int *)&f)[0]);
    return f[offset];
}

static int32_t SC_loadI32(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const int32_t *i = static_cast<const int32_t *>(vp);
    //LOGE("loadI32 %i %i = %i", bank, offset, t);
    return i[offset];
}

static float* SC_loadArrayF(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    float *f = static_cast<float *>(vp);
    return f + offset;
}

static int32_t* SC_loadArrayI32(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    int32_t *i = static_cast<int32_t *>(vp);
    return i + offset;
}

static float* SC_loadSimpleMeshVerticesF(RsSimpleMesh mesh, uint32_t idx)
{
    SimpleMesh *tm = static_cast<SimpleMesh *>(mesh);
    void *vp = tm->mVertexBuffers[idx]->getPtr();;
    return static_cast<float *>(vp);
}

static void SC_updateSimpleMesh(RsSimpleMesh mesh)
{
    GET_TLS();
    SimpleMesh *sm = static_cast<SimpleMesh *>(mesh);
    sm->uploadAll(rsc);
}

static uint32_t SC_loadU32(uint32_t bank, uint32_t offset)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const uint32_t *i = static_cast<const uint32_t *>(vp);
    return i[offset];
}

static void SC_loadVec4(uint32_t bank, uint32_t offset, rsc_Vector4 *v)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const float *f = static_cast<const float *>(vp);
    memcpy(v, &f[offset], sizeof(rsc_Vector4));
}

static void SC_loadMatrix(uint32_t bank, uint32_t offset, rsc_Matrix *m)
{
    GET_TLS();
    const void *vp = sc->mSlots[bank]->getPtr();
    const float *f = static_cast<const float *>(vp);
    memcpy(m, &f[offset], sizeof(rsc_Matrix));
}


static void SC_storeF(uint32_t bank, uint32_t offset, float v)
{
    //LOGE("storeF %i %i %f", bank, offset, v);
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    float *f = static_cast<float *>(vp);
    f[offset] = v;
}

static void SC_storeI32(uint32_t bank, uint32_t offset, int32_t v)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    int32_t *f = static_cast<int32_t *>(vp);
    static_cast<int32_t *>(sc->mSlots[bank]->getPtr())[offset] = v;
}

static void SC_storeU32(uint32_t bank, uint32_t offset, uint32_t v)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    uint32_t *f = static_cast<uint32_t *>(vp);
    static_cast<uint32_t *>(sc->mSlots[bank]->getPtr())[offset] = v;
}

static void SC_storeVec4(uint32_t bank, uint32_t offset, const rsc_Vector4 *v)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    float *f = static_cast<float *>(vp);
    memcpy(&f[offset], v, sizeof(rsc_Vector4));
}

static void SC_storeMatrix(uint32_t bank, uint32_t offset, const rsc_Matrix *m)
{
    GET_TLS();
    void *vp = sc->mSlots[bank]->getPtr();
    float *f = static_cast<float *>(vp);
    memcpy(&f[offset], m, sizeof(rsc_Matrix));
}

//////////////////////////////////////////////////////////////////////////////
// Vec3 routines
//////////////////////////////////////////////////////////////////////////////

static void SC_vec3Norm(vec3_t *v)
{
    float len = sqrtf(v->x * v->x + v->y * v->y + v->z * v->z);
    len = 1 / len;
    v->x *= len;
    v->y *= len;
    v->z *= len;
}

static float SC_vec3Length(const vec3_t *v)
{
    return sqrtf(v->x * v->x + v->y * v->y + v->z * v->z);
}

static void SC_vec3Add(vec3_t *dest, const vec3_t *lhs, const vec3_t *rhs)
{
    dest->x = lhs->x + rhs->x;
    dest->y = lhs->y + rhs->y;
    dest->z = lhs->z + rhs->z;
}

static void SC_vec3Sub(vec3_t *dest, const vec3_t *lhs, const vec3_t *rhs)
{
    dest->x = lhs->x - rhs->x;
    dest->y = lhs->y - rhs->y;
    dest->z = lhs->z - rhs->z;
}

static void SC_vec3Cross(vec3_t *dest, const vec3_t *lhs, const vec3_t *rhs)
{
    float x = lhs->y * rhs->z  - lhs->z * rhs->y;
    float y = lhs->z * rhs->x  - lhs->x * rhs->z;
    float z = lhs->x * rhs->y  - lhs->y * rhs->x;
    dest->x = x;
    dest->y = y;
    dest->z = z;
}

static float SC_vec3Dot(const vec3_t *lhs, const vec3_t *rhs)
{
    return lhs->x * rhs->x + lhs->y * rhs->y + lhs->z * rhs->z;
}

static void SC_vec3Scale(vec3_t *lhs, float scale)
{
    lhs->x *= scale;
    lhs->y *= scale;
    lhs->z *= scale;
}

//////////////////////////////////////////////////////////////////////////////
// Vec4 routines
//////////////////////////////////////////////////////////////////////////////

static void SC_vec4Norm(vec4_t *v)
{
    float len = sqrtf(v->x * v->x + v->y * v->y + v->z * v->z + v->w * v->w);
    len = 1 / len;
    v->x *= len;
    v->y *= len;
    v->z *= len;
    v->w *= len;
}

static float SC_vec4Length(const vec4_t *v)
{
    return sqrtf(v->x * v->x + v->y * v->y + v->z * v->z + v->w * v->w);
}

static void SC_vec4Add(vec4_t *dest, const vec4_t *lhs, const vec4_t *rhs)
{
    dest->x = lhs->x + rhs->x;
    dest->y = lhs->y + rhs->y;
    dest->z = lhs->z + rhs->z;
    dest->w = lhs->w + rhs->w;
}

static void SC_vec4Sub(vec4_t *dest, const vec4_t *lhs, const vec4_t *rhs)
{
    dest->x = lhs->x - rhs->x;
    dest->y = lhs->y - rhs->y;
    dest->z = lhs->z - rhs->z;
    dest->w = lhs->w - rhs->w;
}

static float SC_vec4Dot(const vec4_t *lhs, const vec4_t *rhs)
{
    return lhs->x * rhs->x + lhs->y * rhs->y + lhs->z * rhs->z + lhs->w * rhs->w;
}

static void SC_vec4Scale(vec4_t *lhs, float scale)
{
    lhs->x *= scale;
    lhs->y *= scale;
    lhs->z *= scale;
    lhs->w *= scale;
}

//////////////////////////////////////////////////////////////////////////////
// Math routines
//////////////////////////////////////////////////////////////////////////////

#define PI 3.1415926f
#define DEG_TO_RAD PI / 180.0f
#define RAD_TO_DEG 180.0f / PI

static float SC_sinf_fast(float x)
{
    const float A =   1.0f / (2.0f * M_PI);
    const float B = -16.0f;
    const float C =   8.0f;

    // scale angle for easy argument reduction
    x *= A;

    if (fabsf(x) >= 0.5f) {
        // argument reduction
        x = x - ceilf(x + 0.5f) + 1.0f;
    }

    const float y = B * x * fabsf(x) + C * x;
    return 0.2215f * (y * fabsf(y) - y) + y;
}

static float SC_cosf_fast(float x)
{
    x += float(M_PI / 2);

    const float A =   1.0f / (2.0f * M_PI);
    const float B = -16.0f;
    const float C =   8.0f;

    // scale angle for easy argument reduction
    x *= A;

    if (fabsf(x) >= 0.5f) {
        // argument reduction
        x = x - ceilf(x + 0.5f) + 1.0f;
    }

    const float y = B * x * fabsf(x) + C * x;
    return 0.2215f * (y * fabsf(y) - y) + y;
}

static float SC_randf(float max)
{
    float r = (float)rand();
    return r / RAND_MAX * max;
}

static float SC_randf2(float min, float max)
{
    float r = (float)rand();
    return r / RAND_MAX * (max - min) + min;
}

static int SC_sign(int value)
{
    return (value > 0) - (value < 0);
}

static float SC_signf(float value)
{
    return (value > 0) - (value < 0);
}

static float SC_clampf(float amount, float low, float high)
{
    return amount < low ? low : (amount > high ? high : amount);
}

static int SC_clamp(int amount, int low, int high)
{
    return amount < low ? low : (amount > high ? high : amount);
}

static float SC_maxf(float a, float b)
{
    return a > b ? a : b;
}

static float SC_minf(float a, float b)
{
    return a < b ? a : b;
}

static float SC_sqrf(float v)
{
    return v * v;
}

static int SC_sqr(int v)
{
    return v * v;
}

static float SC_fracf(float v)
{
    return v - floorf(v);
}

static float SC_roundf(float v)
{
    return floorf(v + 0.4999999999);
}

static float SC_distf2(float x1, float y1, float x2, float y2)
{
    float x = x2 - x1;
    float y = y2 - y1;
    return sqrtf(x * x + y * y);
}

static float SC_distf3(float x1, float y1, float z1, float x2, float y2, float z2)
{
    float x = x2 - x1;
    float y = y2 - y1;
    float z = z2 - z1;
    return sqrtf(x * x + y * y + z * z);
}

static float SC_magf2(float a, float b)
{
    return sqrtf(a * a + b * b);
}

static float SC_magf3(float a, float b, float c)
{
    return sqrtf(a * a + b * b + c * c);
}

static float SC_radf(float degrees)
{
    return degrees * DEG_TO_RAD;
}

static float SC_degf(float radians)
{
    return radians * RAD_TO_DEG;
}

static float SC_lerpf(float start, float stop, float amount)
{
    return start + (stop - start) * amount;
}

static float SC_normf(float start, float stop, float value)
{
    return (value - start) / (stop - start);
}

static float SC_mapf(float minStart, float minStop, float maxStart, float maxStop, float value)
{
    return maxStart + (maxStart - maxStop) * ((value - minStart) / (minStop - minStart));
}

//////////////////////////////////////////////////////////////////////////////
// Time routines
//////////////////////////////////////////////////////////////////////////////

static int32_t SC_second()
{
    GET_TLS();

    time_t rawtime;
    time(&rawtime);

    struct tm *timeinfo;
    timeinfo = localtime(&rawtime);
    return timeinfo->tm_sec;
}

static int32_t SC_minute()
{
    GET_TLS();

    time_t rawtime;
    time(&rawtime);

    struct tm *timeinfo;
    timeinfo = localtime(&rawtime);
    return timeinfo->tm_min;
}

static int32_t SC_hour()
{
    GET_TLS();

    time_t rawtime;
    time(&rawtime);

    struct tm *timeinfo;
    timeinfo = localtime(&rawtime);
    return timeinfo->tm_hour;
}

static int32_t SC_day()
{
    GET_TLS();

    time_t rawtime;
    time(&rawtime);

    struct tm *timeinfo;
    timeinfo = localtime(&rawtime);
    return timeinfo->tm_mday;
}

static int32_t SC_month()
{
    GET_TLS();

    time_t rawtime;
    time(&rawtime);

    struct tm *timeinfo;
    timeinfo = localtime(&rawtime);
    return timeinfo->tm_mon;
}

static int32_t SC_year()
{
    GET_TLS();

    time_t rawtime;
    time(&rawtime);

    struct tm *timeinfo;
    timeinfo = localtime(&rawtime);
    return timeinfo->tm_year;
}

static int32_t SC_uptimeMillis()
{
    return nanoseconds_to_milliseconds(systemTime(SYSTEM_TIME_MONOTONIC));
}

static int32_t SC_startTimeMillis()
{
    GET_TLS();
    return sc->mEnviroment.mStartTimeMillis;
}

static int32_t SC_elapsedTimeMillis()
{
    GET_TLS();
    return nanoseconds_to_milliseconds(systemTime(SYSTEM_TIME_MONOTONIC))
            - sc->mEnviroment.mStartTimeMillis;
}

//////////////////////////////////////////////////////////////////////////////
// Matrix routines
//////////////////////////////////////////////////////////////////////////////


static void SC_matrixLoadIdentity(rsc_Matrix *mat)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadIdentity();
}

static void SC_matrixLoadFloat(rsc_Matrix *mat, const float *f)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->load(f);
}

static void SC_matrixLoadMat(rsc_Matrix *mat, const rsc_Matrix *newmat)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->load(reinterpret_cast<const Matrix *>(newmat));
}

static void SC_matrixLoadRotate(rsc_Matrix *mat, float rot, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadRotate(rot, x, y, z);
}

static void SC_matrixLoadScale(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadScale(x, y, z);
}

static void SC_matrixLoadTranslate(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadTranslate(x, y, z);
}

static void SC_matrixLoadMultiply(rsc_Matrix *mat, const rsc_Matrix *lhs, const rsc_Matrix *rhs)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->loadMultiply(reinterpret_cast<const Matrix *>(lhs),
                    reinterpret_cast<const Matrix *>(rhs));
}

static void SC_matrixMultiply(rsc_Matrix *mat, const rsc_Matrix *rhs)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->multiply(reinterpret_cast<const Matrix *>(rhs));
}

static void SC_matrixRotate(rsc_Matrix *mat, float rot, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->rotate(rot, x, y, z);
}

static void SC_matrixScale(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->scale(x, y, z);
}

static void SC_matrixTranslate(rsc_Matrix *mat, float x, float y, float z)
{
    Matrix *m = reinterpret_cast<Matrix *>(mat);
    m->translate(x, y, z);
}


static void SC_vec2Rand(float *vec, float maxLen)
{
    float angle = SC_randf(PI * 2);
    float len = SC_randf(maxLen);
    vec[0] = len * sinf(angle);
    vec[1] = len * cosf(angle);
}



//////////////////////////////////////////////////////////////////////////////
// Context
//////////////////////////////////////////////////////////////////////////////

static void SC_bindTexture(RsProgramFragment vpf, uint32_t slot, RsAllocation va)
{
    GET_TLS();
    rsi_ProgramBindTexture(rsc,
                           static_cast<ProgramFragment *>(vpf),
                           slot,
                           static_cast<Allocation *>(va));

}

static void SC_bindSampler(RsProgramFragment vpf, uint32_t slot, RsSampler vs)
{
    GET_TLS();
    rsi_ProgramBindSampler(rsc,
                           static_cast<ProgramFragment *>(vpf),
                           slot,
                           static_cast<Sampler *>(vs));

}

static void SC_bindProgramFragmentStore(RsProgramFragmentStore pfs)
{
    GET_TLS();
    rsi_ContextBindProgramFragmentStore(rsc, pfs);

}

static void SC_bindProgramFragment(RsProgramFragment pf)
{
    GET_TLS();
    rsi_ContextBindProgramFragment(rsc, pf);

}

static void SC_bindProgramVertex(RsProgramVertex pv)
{
    GET_TLS();
    rsi_ContextBindProgramVertex(rsc, pv);

}

//////////////////////////////////////////////////////////////////////////////
// VP
//////////////////////////////////////////////////////////////////////////////

static void SC_vpLoadModelMatrix(const rsc_Matrix *m)
{
    GET_TLS();
    rsc->getVertex()->setModelviewMatrix(m);
}

static void SC_vpLoadTextureMatrix(const rsc_Matrix *m)
{
    GET_TLS();
    rsc->getVertex()->setTextureMatrix(m);
}



//////////////////////////////////////////////////////////////////////////////
// Drawing
//////////////////////////////////////////////////////////////////////////////

static void SC_drawLine(float x1, float y1, float z1,
                        float x2, float y2, float z2)
{
    GET_TLS();
    if (!rsc->setupCheck()) {
        return;
    }

    float vtx[] = { x1, y1, z1, x2, y2, z2 };
    VertexArray va;
    va.addLegacy(GL_FLOAT, 3, 12, RS_KIND_POSITION, false, (uint32_t)vtx);
    if (rsc->checkVersion2_0()) {
        va.setupGL2(rsc, &rsc->mStateVertexArray, &rsc->mShaderCache);
    } else {
        va.setupGL(rsc, &rsc->mStateVertexArray);
    }

    glDrawArrays(GL_LINES, 0, 2);
}

static void SC_drawPoint(float x, float y, float z)
{
    GET_TLS();
    if (!rsc->setupCheck()) {
        return;
    }

    float vtx[] = { x, y, z };

    VertexArray va;
    va.addLegacy(GL_FLOAT, 3, 12, RS_KIND_POSITION, false, (uint32_t)vtx);
    if (rsc->checkVersion2_0()) {
        va.setupGL2(rsc, &rsc->mStateVertexArray, &rsc->mShaderCache);
    } else {
        va.setupGL(rsc, &rsc->mStateVertexArray);
    }

    glDrawArrays(GL_POINTS, 0, 1);
}

static void SC_drawQuadTexCoords(float x1, float y1, float z1,
                                 float u1, float v1,
                                 float x2, float y2, float z2,
                                 float u2, float v2,
                                 float x3, float y3, float z3,
                                 float u3, float v3,
                                 float x4, float y4, float z4,
                                 float u4, float v4)
{
    GET_TLS();
    if (!rsc->setupCheck()) {
        return;
    }

    //LOGE("Quad");
    //LOGE("%4.2f, %4.2f, %4.2f", x1, y1, z1);
    //LOGE("%4.2f, %4.2f, %4.2f", x2, y2, z2);
    //LOGE("%4.2f, %4.2f, %4.2f", x3, y3, z3);
    //LOGE("%4.2f, %4.2f, %4.2f", x4, y4, z4);

    float vtx[] = {x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4};
    const float tex[] = {u1,v1, u2,v2, u3,v3, u4,v4};

    VertexArray va;
    va.addLegacy(GL_FLOAT, 3, 12, RS_KIND_POSITION, false, (uint32_t)vtx);
    va.addLegacy(GL_FLOAT, 2, 8, RS_KIND_TEXTURE, false, (uint32_t)tex);
    if (rsc->checkVersion2_0()) {
        va.setupGL2(rsc, &rsc->mStateVertexArray, &rsc->mShaderCache);
    } else {
        va.setupGL(rsc, &rsc->mStateVertexArray);
    }


    glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
}

static void SC_drawQuad(float x1, float y1, float z1,
                        float x2, float y2, float z2,
                        float x3, float y3, float z3,
                        float x4, float y4, float z4)
{
    SC_drawQuadTexCoords(x1, y1, z1, 0, 1,
                         x2, y2, z2, 1, 1,
                         x3, y3, z3, 1, 0,
                         x4, y4, z4, 0, 0);
}

static void SC_drawSpriteScreenspace(float x, float y, float z, float w, float h)
{
    GET_TLS();
    ObjectBaseRef<const ProgramVertex> tmp(rsc->getVertex());
    rsc->setVertex(rsc->getDefaultProgramVertex());
    //rsc->setupCheck();

    //GLint crop[4] = {0, h, w, -h};

    float sh = rsc->getHeight();

    SC_drawQuad(x,   sh - y,     z,
                x+w, sh - y,     z,
                x+w, sh - (y+h), z,
                x,   sh - (y+h), z);
    rsc->setVertex((ProgramVertex *)tmp.get());
}

static void SC_drawSpriteScreenspaceCropped(float x, float y, float z, float w, float h,
        float cx0, float cy0, float cx1, float cy1)
{
    GET_TLS();
    if (!rsc->setupCheck()) {
        return;
    }

    GLint crop[4] = {cx0, cy0, cx1, cy1};
    glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
    glDrawTexfOES(x, y, z, w, h);
}

static void SC_drawSprite(float x, float y, float z, float w, float h)
{
    GET_TLS();
    float vin[3] = {x, y, z};
    float vout[4];

    //LOGE("ds  in %f %f %f", x, y, z);
    rsc->getVertex()->transformToScreen(rsc, vout, vin);
    //LOGE("ds  out %f %f %f %f", vout[0], vout[1], vout[2], vout[3]);
    vout[0] /= vout[3];
    vout[1] /= vout[3];
    vout[2] /= vout[3];

    vout[0] *= rsc->getWidth() / 2;
    vout[1] *= rsc->getHeight() / 2;
    vout[0] += rsc->getWidth() / 2;
    vout[1] += rsc->getHeight() / 2;

    vout[0] -= w/2;
    vout[1] -= h/2;

    //LOGE("ds  out2 %f %f %f", vout[0], vout[1], vout[2]);

    // U, V, W, H
    SC_drawSpriteScreenspace(vout[0], vout[1], z, h, w);
    //rsc->setupCheck();
}


static void SC_drawRect(float x1, float y1,
                        float x2, float y2, float z)
{
    SC_drawQuad(x1, y2, z,
                x2, y2, z,
                x2, y1, z,
                x1, y1, z);
}

static void SC_drawSimpleMesh(RsSimpleMesh vsm)
{
    GET_TLS();
    SimpleMesh *sm = static_cast<SimpleMesh *>(vsm);
    if (!rsc->setupCheck()) {
        return;
    }
    sm->render(rsc);
}

static void SC_drawSimpleMeshRange(RsSimpleMesh vsm, uint32_t start, uint32_t len)
{
    GET_TLS();
    SimpleMesh *sm = static_cast<SimpleMesh *>(vsm);
    if (!rsc->setupCheck()) {
        return;
    }
    sm->renderRange(rsc, start, len);
}


//////////////////////////////////////////////////////////////////////////////
//
//////////////////////////////////////////////////////////////////////////////

static void SC_color(float r, float g, float b, float a)
{
    GET_TLS();
    rsc->mStateVertex.color[0] = r;
    rsc->mStateVertex.color[1] = g;
    rsc->mStateVertex.color[2] = b;
    rsc->mStateVertex.color[3] = a;
    if (!rsc->checkVersion2_0()) {
        glColor4f(r, g, b, a);
    }
}

static void SC_ambient(float r, float g, float b, float a)
{
    GLfloat params[] = { r, g, b, a };
    glMaterialfv(GL_FRONT_AND_BACK, GL_AMBIENT, params);
}

static void SC_diffuse(float r, float g, float b, float a)
{
    GLfloat params[] = { r, g, b, a };
    glMaterialfv(GL_FRONT_AND_BACK, GL_DIFFUSE, params);
}

static void SC_specular(float r, float g, float b, float a)
{
    GLfloat params[] = { r, g, b, a };
    glMaterialfv(GL_FRONT_AND_BACK, GL_SPECULAR, params);
}

static void SC_emission(float r, float g, float b, float a)
{
    GLfloat params[] = { r, g, b, a };
    glMaterialfv(GL_FRONT_AND_BACK, GL_EMISSION, params);
}

static void SC_shininess(float s)
{
    glMaterialf(GL_FRONT_AND_BACK, GL_SHININESS, s);
}

static void SC_pointAttenuation(float a, float b, float c)
{
    GLfloat params[] = { a, b, c };
    glPointParameterfv(GL_POINT_DISTANCE_ATTENUATION, params);
}

static void SC_hsbToRgb(float h, float s, float b, float* rgb)
{
    float red = 0.0f;
    float green = 0.0f;
    float blue = 0.0f;

    float x = h;
    float y = s;
    float z = b;

    float hf = (x - (int) x) * 6.0f;
    int ihf = (int) hf;
    float f = hf - ihf;
    float pv = z * (1.0f - y);
    float qv = z * (1.0f - y * f);
    float tv = z * (1.0f - y * (1.0f - f));

    switch (ihf) {
        case 0:         // Red is the dominant color
            red = z;
            green = tv;
            blue = pv;
            break;
        case 1:         // Green is the dominant color
            red = qv;
            green = z;
            blue = pv;
            break;
        case 2:
            red = pv;
            green = z;
            blue = tv;
            break;
        case 3:         // Blue is the dominant color
            red = pv;
            green = qv;
            blue = z;
            break;
        case 4:
            red = tv;
            green = pv;
            blue = z;
            break;
        case 5:         // Red is the dominant color
            red = z;
            green = pv;
            blue = qv;
            break;
    }

    rgb[0] = red;
    rgb[1] = green;
    rgb[2] = blue;
}

static int SC_hsbToAbgr(float h, float s, float b, float a)
{
    float rgb[3];
    SC_hsbToRgb(h, s, b, rgb);
    return int(a      * 255.0f) << 24 |
           int(rgb[2] * 255.0f) << 16 |
           int(rgb[1] * 255.0f) <<  8 |
           int(rgb[0] * 255.0f);
}

static void SC_hsb(float h, float s, float b, float a)
{
    GET_TLS();
    float rgb[3];
    SC_hsbToRgb(h, s, b, rgb);
    if (rsc->checkVersion2_0()) {
        glVertexAttrib4f(1, rgb[0], rgb[1], rgb[2], a);
    } else {
        glColor4f(rgb[0], rgb[1], rgb[2], a);
    }
}

static void SC_uploadToTexture(RsAllocation va, uint32_t baseMipLevel)
{
    GET_TLS();
    rsi_AllocationUploadToTexture(rsc, va, false, baseMipLevel);
}

static void SC_uploadToBufferObject(RsAllocation va)
{
    GET_TLS();
    rsi_AllocationUploadToBufferObject(rsc, va);
}

static void SC_syncToGL(RsAllocation va)
{
    GET_TLS();
    Allocation *a = static_cast<Allocation *>(va);

}

static void SC_ClearColor(float r, float g, float b, float a)
{
    //LOGE("c %f %f %f %f", r, g, b, a);
    GET_TLS();
    sc->mEnviroment.mClearColor[0] = r;
    sc->mEnviroment.mClearColor[1] = g;
    sc->mEnviroment.mClearColor[2] = b;
    sc->mEnviroment.mClearColor[3] = a;
}

static void SC_debugF(const char *s, float f)
{
    LOGE("%s %f", s, f);
}

static void SC_debugHexF(const char *s, float f)
{
    LOGE("%s 0x%x", s, *((int *) (&f)));
}

static void SC_debugI32(const char *s, int32_t i)
{
    LOGE("%s %i", s, i);
}

static void SC_debugHexI32(const char *s, int32_t i)
{
    LOGE("%s 0x%x", s, i);
}

static uint32_t SC_getWidth()
{
    GET_TLS();
    return rsc->getWidth();
}

static uint32_t SC_getHeight()
{
    GET_TLS();
    return rsc->getHeight();
}

static uint32_t SC_colorFloatRGBAtoUNorm8(float r, float g, float b, float a)
{
    uint32_t c = 0;
    c |= (uint32_t)(r * 255.f + 0.5f);
    c |= ((uint32_t)(g * 255.f + 0.5f)) << 8;
    c |= ((uint32_t)(b * 255.f + 0.5f)) << 16;
    c |= ((uint32_t)(a * 255.f + 0.5f)) << 24;
    return c;
}

static uint32_t SC_colorFloatRGBAto565(float r, float g, float b)
{
    uint32_t ir = (uint32_t)(r * 255.f + 0.5f);
    uint32_t ig = (uint32_t)(g * 255.f + 0.5f);
    uint32_t ib = (uint32_t)(b * 255.f + 0.5f);
    return rs888to565(ir, ig, ib);
}

static uint32_t SC_toClient(void *data, int cmdID, int len, int waitForSpace)
{
    GET_TLS();
    return rsc->sendMessageToClient(data, cmdID, len, waitForSpace != 0);
}

static void SC_scriptCall(int scriptID)
{
    GET_TLS();
    rsc->runScript((Script *)scriptID, 0);
}


//////////////////////////////////////////////////////////////////////////////
// Class implementation
//////////////////////////////////////////////////////////////////////////////

ScriptCState::SymbolTable_t ScriptCState::gSyms[] = {
    // IO
    { "loadI32", (void *)&SC_loadI32,
        "int", "(int, int)" },
    //{ "loadU32", (void *)&SC_loadU32, "unsigned int", "(int, int)" },
    { "loadF", (void *)&SC_loadF,
        "float", "(int, int)" },
    { "loadArrayF", (void *)&SC_loadArrayF,
        "float*", "(int, int)" },
    { "loadArrayI32", (void *)&SC_loadArrayI32,
        "int*", "(int, int)" },
    { "loadVec4", (void *)&SC_loadVec4,
        "void", "(int, int, float *)" },
    { "loadMatrix", (void *)&SC_loadMatrix,
        "void", "(int, int, float *)" },
    { "storeI32", (void *)&SC_storeI32,
        "void", "(int, int, int)" },
    //{ "storeU32", (void *)&SC_storeU32, "void", "(int, int, unsigned int)" },
    { "storeF", (void *)&SC_storeF,
        "void", "(int, int, float)" },
    { "storeVec4", (void *)&SC_storeVec4,
        "void", "(int, int, float *)" },
    { "storeMatrix", (void *)&SC_storeMatrix,
        "void", "(int, int, float *)" },
    { "loadSimpleMeshVerticesF", (void *)&SC_loadSimpleMeshVerticesF,
        "float*", "(int, int)" },
    { "updateSimpleMesh", (void *)&SC_updateSimpleMesh,
        "void", "(int)" },

    // math
    { "modf", (void *)&fmod,
        "float", "(float, float)" },
    { "abs", (void *)&abs,
        "int", "(int)" },
    { "absf", (void *)&fabsf,
        "float", "(float)" },
    { "sinf_fast", (void *)&SC_sinf_fast,
        "float", "(float)" },
    { "cosf_fast", (void *)&SC_cosf_fast,
        "float", "(float)" },
    { "sinf", (void *)&sinf,
        "float", "(float)" },
    { "cosf", (void *)&cosf,
        "float", "(float)" },
    { "asinf", (void *)&asinf,
        "float", "(float)" },
    { "acosf", (void *)&acosf,
        "float", "(float)" },
    { "atanf", (void *)&atanf,
        "float", "(float)" },
    { "atan2f", (void *)&atan2f,
        "float", "(float, float)" },
    { "fabsf", (void *)&fabsf,
        "float", "(float)" },
    { "randf", (void *)&SC_randf,
        "float", "(float)" },
    { "randf2", (void *)&SC_randf2,
        "float", "(float, float)" },
    { "floorf", (void *)&floorf,
        "float", "(float)" },
    { "fracf", (void *)&SC_fracf,
        "float", "(float)" },
    { "ceilf", (void *)&ceilf,
        "float", "(float)" },
    { "roundf", (void *)&SC_roundf,
        "float", "(float)" },
    { "expf", (void *)&expf,
        "float", "(float)" },
    { "logf", (void *)&logf,
        "float", "(float)" },
    { "powf", (void *)&powf,
        "float", "(float, float)" },
    { "maxf", (void *)&SC_maxf,
        "float", "(float, float)" },
    { "minf", (void *)&SC_minf,
        "float", "(float, float)" },
    { "sqrt", (void *)&sqrt,
        "int", "(int)" },
    { "sqrtf", (void *)&sqrtf,
        "float", "(float)" },
    { "sqr", (void *)&SC_sqr,
        "int", "(int)" },
    { "sqrf", (void *)&SC_sqrf,
        "float", "(float)" },
    { "sign", (void *)&SC_sign,
        "int", "(int)" },
    { "signf", (void *)&SC_signf,
        "float", "(float)" },
    { "clamp", (void *)&SC_clamp,
        "int", "(int, int, int)" },
    { "clampf", (void *)&SC_clampf,
        "float", "(float, float, float)" },
    { "distf2", (void *)&SC_distf2,
        "float", "(float, float, float, float)" },
    { "distf3", (void *)&SC_distf3,
        "float", "(float, float, float, float, float, float)" },
    { "magf2", (void *)&SC_magf2,
        "float", "(float, float)" },
    { "magf3", (void *)&SC_magf3,
        "float", "(float, float, float)" },
    { "radf", (void *)&SC_radf,
        "float", "(float)" },
    { "degf", (void *)&SC_degf,
        "float", "(float)" },
    { "lerpf", (void *)&SC_lerpf,
        "float", "(float, float, float)" },
    { "normf", (void *)&SC_normf,
        "float", "(float, float, float)" },
    { "mapf", (void *)&SC_mapf,
        "float", "(float, float, float, float, float)" },
    { "noisef", (void *)&SC_noisef,
        "float", "(float)" },
    { "noisef2", (void *)&SC_noisef2,
        "float", "(float, float)" },
    { "noisef3", (void *)&SC_noisef3,
        "float", "(float, float, float)" },
    { "turbulencef2", (void *)&SC_turbulencef2,
        "float", "(float, float, float)" },
    { "turbulencef3", (void *)&SC_turbulencef3,
        "float", "(float, float, float, float)" },

    // time
    { "second", (void *)&SC_second,
        "int", "()" },
    { "minute", (void *)&SC_minute,
        "int", "()" },
    { "hour", (void *)&SC_hour,
        "int", "()" },
    { "day", (void *)&SC_day,
        "int", "()" },
    { "month", (void *)&SC_month,
        "int", "()" },
    { "year", (void *)&SC_year,
        "int", "()" },
    { "uptimeMillis", (void*)&SC_uptimeMillis,
        "int", "()" },      // TODO: use long instead
    { "startTimeMillis", (void*)&SC_startTimeMillis,
        "int", "()" },      // TODO: use long instead
    { "elapsedTimeMillis", (void*)&SC_elapsedTimeMillis,
        "int", "()" },      // TODO: use long instead

    // matrix
    { "matrixLoadIdentity", (void *)&SC_matrixLoadIdentity,
        "void", "(float *mat)" },
    { "matrixLoadFloat", (void *)&SC_matrixLoadFloat,
        "void", "(float *mat, float *f)" },
    { "matrixLoadMat", (void *)&SC_matrixLoadMat,
        "void", "(float *mat, float *newmat)" },
    { "matrixLoadRotate", (void *)&SC_matrixLoadRotate,
        "void", "(float *mat, float rot, float x, float y, float z)" },
    { "matrixLoadScale", (void *)&SC_matrixLoadScale,
        "void", "(float *mat, float x, float y, float z)" },
    { "matrixLoadTranslate", (void *)&SC_matrixLoadTranslate,
        "void", "(float *mat, float x, float y, float z)" },
    { "matrixLoadMultiply", (void *)&SC_matrixLoadMultiply,
        "void", "(float *mat, float *lhs, float *rhs)" },
    { "matrixMultiply", (void *)&SC_matrixMultiply,
        "void", "(float *mat, float *rhs)" },
    { "matrixRotate", (void *)&SC_matrixRotate,
        "void", "(float *mat, float rot, float x, float y, float z)" },
    { "matrixScale", (void *)&SC_matrixScale,
        "void", "(float *mat, float x, float y, float z)" },
    { "matrixTranslate", (void *)&SC_matrixTranslate,
        "void", "(float *mat, float x, float y, float z)" },

    // vector
    { "vec2Rand", (void *)&SC_vec2Rand,
        "void", "(float *vec, float maxLen)" },

    // vec3
    { "vec3Norm", (void *)&SC_vec3Norm,
        "void", "(struct vecF32_3_s *)" },
    { "vec3Length", (void *)&SC_vec3Length,
        "float", "(struct vecF32_3_s *)" },
    { "vec3Add", (void *)&SC_vec3Add,
        "void", "(struct vecF32_3_s *dest, struct vecF32_3_s *lhs, struct vecF32_3_s *rhs)" },
    { "vec3Sub", (void *)&SC_vec3Sub,
        "void", "(struct vecF32_3_s *dest, struct vecF32_3_s *lhs, struct vecF32_3_s *rhs)" },
    { "vec3Cross", (void *)&SC_vec3Cross,
        "void", "(struct vecF32_3_s *dest, struct vecF32_3_s *lhs, struct vecF32_3_s *rhs)" },
    { "vec3Dot", (void *)&SC_vec3Dot,
        "float", "(struct vecF32_3_s *lhs, struct vecF32_3_s *rhs)" },
    { "vec3Scale", (void *)&SC_vec3Scale,
        "void", "(struct vecF32_3_s *lhs, float scale)" },

    // vec4
    { "vec4Norm", (void *)&SC_vec4Norm,
        "void", "(struct vecF32_4_s *)" },
    { "vec4Length", (void *)&SC_vec4Length,
        "float", "(struct vecF32_4_s *)" },
    { "vec4Add", (void *)&SC_vec4Add,
        "void", "(struct vecF32_4_s *dest, struct vecF32_4_s *lhs, struct vecF32_4_s *rhs)" },
    { "vec4Sub", (void *)&SC_vec4Sub,
        "void", "(struct vecF32_4_s *dest, struct vecF32_4_s *lhs, struct vecF32_4_s *rhs)" },
    { "vec4Dot", (void *)&SC_vec4Dot,
        "float", "(struct vecF32_4_s *lhs, struct vecF32_4_s *rhs)" },
    { "vec4Scale", (void *)&SC_vec4Scale,
        "void", "(struct vecF32_4_s *lhs, float scale)" },

    // context
    { "bindProgramFragment", (void *)&SC_bindProgramFragment,
        "void", "(int)" },
    { "bindProgramFragmentStore", (void *)&SC_bindProgramFragmentStore,
        "void", "(int)" },
    { "bindProgramStore", (void *)&SC_bindProgramFragmentStore,
        "void", "(int)" },
    { "bindProgramVertex", (void *)&SC_bindProgramVertex,
        "void", "(int)" },
    { "bindSampler", (void *)&SC_bindSampler,
        "void", "(int, int, int)" },
    { "bindTexture", (void *)&SC_bindTexture,
        "void", "(int, int, int)" },

    // vp
    { "vpLoadModelMatrix", (void *)&SC_vpLoadModelMatrix,
        "void", "(void *)" },
    { "vpLoadTextureMatrix", (void *)&SC_vpLoadTextureMatrix,
        "void", "(void *)" },



    // drawing
    { "drawRect", (void *)&SC_drawRect,
        "void", "(float x1, float y1, float x2, float y2, float z)" },
    { "drawQuad", (void *)&SC_drawQuad,
        "void", "(float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4)" },
    { "drawQuadTexCoords", (void *)&SC_drawQuadTexCoords,
        "void", "(float x1, float y1, float z1, float u1, float v1, float x2, float y2, float z2, float u2, float v2, float x3, float y3, float z3, float u3, float v3, float x4, float y4, float z4, float u4, float v4)" },
    { "drawSprite", (void *)&SC_drawSprite,
        "void", "(float x, float y, float z, float w, float h)" },
    { "drawSpriteScreenspace", (void *)&SC_drawSpriteScreenspace,
        "void", "(float x, float y, float z, float w, float h)" },
    { "drawSpriteScreenspaceCropped", (void *)&SC_drawSpriteScreenspaceCropped,
        "void", "(float x, float y, float z, float w, float h, float cx0, float cy0, float cx1, float cy1)" },
    { "drawLine", (void *)&SC_drawLine,
        "void", "(float x1, float y1, float z1, float x2, float y2, float z2)" },
    { "drawPoint", (void *)&SC_drawPoint,
        "void", "(float x1, float y1, float z1)" },
    { "drawSimpleMesh", (void *)&SC_drawSimpleMesh,
        "void", "(int ism)" },
    { "drawSimpleMeshRange", (void *)&SC_drawSimpleMeshRange,
        "void", "(int ism, int start, int len)" },


    // misc
    { "pfClearColor", (void *)&SC_ClearColor,
        "void", "(float, float, float, float)" },
    { "color", (void *)&SC_color,
        "void", "(float, float, float, float)" },
    { "hsb", (void *)&SC_hsb,
        "void", "(float, float, float, float)" },
    { "hsbToRgb", (void *)&SC_hsbToRgb,
        "void", "(float, float, float, float*)" },
    { "hsbToAbgr", (void *)&SC_hsbToAbgr,
        "int", "(float, float, float, float)" },
    { "ambient", (void *)&SC_ambient,
        "void", "(float, float, float, float)" },
    { "diffuse", (void *)&SC_diffuse,
        "void", "(float, float, float, float)" },
    { "specular", (void *)&SC_specular,
        "void", "(float, float, float, float)" },
    { "emission", (void *)&SC_emission,
        "void", "(float, float, float, float)" },
    { "shininess", (void *)&SC_shininess,
        "void", "(float)" },
    { "pointAttenuation", (void *)&SC_pointAttenuation,
        "void", "(float, float, float)" },

    { "uploadToTexture", (void *)&SC_uploadToTexture,
        "void", "(int, int)" },
    { "uploadToBufferObject", (void *)&SC_uploadToBufferObject,
        "void", "(int)" },

    { "syncToGL", (void *)&SC_syncToGL,
        "void", "(int)" },

    { "colorFloatRGBAtoUNorm8", (void *)&SC_colorFloatRGBAtoUNorm8,
        "int", "(float, float, float, float)" },
    { "colorFloatRGBto565", (void *)&SC_colorFloatRGBAto565,
        "int", "(float, float, float)" },


    { "getWidth", (void *)&SC_getWidth,
        "int", "()" },
    { "getHeight", (void *)&SC_getHeight,
        "int", "()" },

    { "sendToClient", (void *)&SC_toClient,
        "int", "(void *data, int cmdID, int len, int waitForSpace)" },


    { "debugF", (void *)&SC_debugF,
        "void", "(void *, float)" },
    { "debugI32", (void *)&SC_debugI32,
        "void", "(void *, int)" },
    { "debugHexF", (void *)&SC_debugHexF,
        "void", "(void *, float)" },
    { "debugHexI32", (void *)&SC_debugHexI32,
        "void", "(void *, int)" },

    { "scriptCall", (void *)&SC_scriptCall,
        "void", "(int)" },


    { NULL, NULL, NULL, NULL }
};

const ScriptCState::SymbolTable_t * ScriptCState::lookupSymbol(const char *sym)
{
    ScriptCState::SymbolTable_t *syms = gSyms;

    while (syms->mPtr) {
        if (!strcmp(syms->mName, sym)) {
            return syms;
        }
        syms++;
    }
    return NULL;
}

void ScriptCState::appendDecls(String8 *str)
{
    ScriptCState::SymbolTable_t *syms = gSyms;
    while (syms->mPtr) {
        str->append(syms->mRet);
        str->append(" ");
        str->append(syms->mName);
        str->append(syms->mParam);
        str->append(";\n");
        syms++;
    }
}


