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


static float SC_acospi(float v) {
    return acosf(v)/ M_PI;
}

static float SC_asinpi(float v) {
    return asinf(v) / M_PI;
}

static float SC_atanpi(float v) {
    return atanf(v) / M_PI;
}

static float SC_atan2pi(float y, float x) {
    return atan2f(y, x) / M_PI;
}

static float SC_cospi(float v) {
    return cosf(v * M_PI);
}

static float SC_exp10(float v) {
    return pow(10.f, v);

}

static float SC_fract(float v, int *iptr) {
    int i = (int)floor(v);
    iptr[0] = i;
    return fmin(v - i, 0x1.fffffep-1f);
}

static float SC_log2(float v) {
    return log10(v) / log10(2.f);
}

static float SC_pown(float v, int p) {
    return powf(v, (float)p);
}

static float SC_powr(float v, float p) {
    return powf(v, p);
}

float SC_rootn(float v, int r) {
    return pow(v, 1.f / r);
}

float SC_rsqrt(float v) {
    return 1.f / sqrtf(v);
}

float SC_sincos(float v, float *cosptr) {
    *cosptr = cosf(v);
    return sinf(v);
}

static float SC_sinpi(float v) {
    return sinf(v * M_PI);
}

static float SC_tanpi(float v) {
    return tanf(v * M_PI);
}

    //{ "logb", (void *)& },
    //{ "mad", (void *)& },
    //{ "nan", (void *)& },
    //{ "tgamma", (void *)& },

//////////////////////////////////////////////////////////////////////////////
// Integer
//////////////////////////////////////////////////////////////////////////////


static uint32_t SC_abs_i32(int32_t v) {return abs(v);}
static uint16_t SC_abs_i16(int16_t v) {return (uint16_t)abs(v);}
static uint8_t SC_abs_i8(int8_t v) {return (uint8_t)abs(v);}

static uint32_t SC_clz_u32(uint32_t v) {return __builtin_clz(v);}
static uint16_t SC_clz_u16(uint16_t v) {return (uint16_t)__builtin_clz(v);}
static uint8_t SC_clz_u8(uint8_t v) {return (uint8_t)__builtin_clz(v);}
static int32_t SC_clz_i32(int32_t v) {return (int32_t)__builtin_clz((uint32_t)v);}
static int16_t SC_clz_i16(int16_t v) {return (int16_t)__builtin_clz(v);}
static int8_t SC_clz_i8(int8_t v) {return (int8_t)__builtin_clz(v);}

static uint32_t SC_max_u32(uint32_t v, uint32_t v2) {return rsMax(v, v2);}
static uint16_t SC_max_u16(uint16_t v, uint16_t v2) {return rsMax(v, v2);}
static uint8_t SC_max_u8(uint8_t v, uint8_t v2) {return rsMax(v, v2);}
static int32_t SC_max_i32(int32_t v, int32_t v2) {return rsMax(v, v2);}
static int16_t SC_max_i16(int16_t v, int16_t v2) {return rsMax(v, v2);}
static int8_t SC_max_i8(int8_t v, int8_t v2) {return rsMax(v, v2);}

static uint32_t SC_min_u32(uint32_t v, uint32_t v2) {return rsMin(v, v2);}
static uint16_t SC_min_u16(uint16_t v, uint16_t v2) {return rsMin(v, v2);}
static uint8_t SC_min_u8(uint8_t v, uint8_t v2) {return rsMin(v, v2);}
static int32_t SC_min_i32(int32_t v, int32_t v2) {return rsMin(v, v2);}
static int16_t SC_min_i16(int16_t v, int16_t v2) {return rsMin(v, v2);}
static int8_t SC_min_i8(int8_t v, int8_t v2) {return rsMin(v, v2);}

//////////////////////////////////////////////////////////////////////////////
// Float util
//////////////////////////////////////////////////////////////////////////////

static float SC_clamp_f32(float amount, float low, float high)
{
    return amount < low ? low : (amount > high ? high : amount);
}

static float SC_degrees(float radians)
{
    return radians * (180.f / M_PI);
}

static float SC_max_f32(float v, float v2)
{
    return rsMax(v, v2);
}

static float SC_min_f32(float v, float v2)
{
    return rsMin(v, v2);
}

static float SC_mix_f32(float start, float stop, float amount)
{
    //LOGE("lerpf %f  %f  %f", start, stop, amount);
    return start + (stop - start) * amount;
}

static float SC_radians(float degrees)
{
    return degrees * (M_PI / 180.f);
}

static float SC_step_f32(float edge, float v)
{
    if (v < edge) return 0.f;
    return 1.f;
}

static float SC_sign_f32(float value)
{
    if (value > 0) return 1.f;
    if (value < 0) return -1.f;
    return value;
}



//////////////////////////////////////////////////////////////////////////////
// Non-Updated code below
//////////////////////////////////////////////////////////////////////////////

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

static void SC_updateSimpleMesh(RsSimpleMesh mesh)
{
    GET_TLS();
    SimpleMesh *sm = static_cast<SimpleMesh *>(mesh);
    sm->uploadAll(rsc);
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
    //LOGE("max %f", max);
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

static int SC_clamp(int amount, int low, int high)
{
    return amount < low ? low : (amount > high ? high : amount);
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

static float SC_normf(float start, float stop, float value)
{
    return (value - start) / (stop - start);
}

static float SC_mapf(float minStart, float minStop, float maxStart, float maxStop, float value)
{
    return maxStart + (maxStart - maxStop) * ((value - minStart) / (minStop - minStart));
}

static float SC_frac(float v)
{
    int i = (int)floor(v);
    return fmin(v - i, 0x1.fffffep-1f);
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


static rsvF_2 SC_vec2Rand(float maxLen)
{
    float2 t;
    float angle = SC_randf(M_PI * 2);
    float len = SC_randf(maxLen);
    t.f[0] = len * sinf(angle);
    t.f[1] = len * cosf(angle);
    return t.v;
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

static void SC_bindProgramStore(RsProgramStore pfs)
{
    GET_TLS();
    rsi_ContextBindProgramStore(rsc, pfs);
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

static void SC_bindProgramRaster(RsProgramRaster pv)
{
    GET_TLS();
    rsi_ContextBindProgramRaster(rsc, pv);
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
    //LOGE("SC_drawRect %f,%f  %f,%f  %f", x1, y1, x2, y2, z);
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

static uint32_t SC_allocGetDimX(RsAllocation va)
{
    GET_TLS();
    const Allocation *a = static_cast<const Allocation *>(va);
    //LOGE("SC_allocGetDimX a=%p", a);
    //LOGE(" type=%p", a->getType());
    return a->getType()->getDimX();
}

static uint32_t SC_allocGetDimY(RsAllocation va)
{
    GET_TLS();
    const Allocation *a = static_cast<const Allocation *>(va);
    return a->getType()->getDimY();
}

static uint32_t SC_allocGetDimZ(RsAllocation va)
{
    GET_TLS();
    const Allocation *a = static_cast<const Allocation *>(va);
    return a->getType()->getDimZ();
}

static uint32_t SC_allocGetDimLOD(RsAllocation va)
{
    GET_TLS();
    const Allocation *a = static_cast<const Allocation *>(va);
    return a->getType()->getDimLOD();
}

static uint32_t SC_allocGetDimFaces(RsAllocation va)
{
    GET_TLS();
    const Allocation *a = static_cast<const Allocation *>(va);
    return a->getType()->getDimFaces();
}


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
    //LOGE("hsb a %f, %f, %f    %f", h, s, b, a);
    float rgb[3];
    SC_hsbToRgb(h, s, b, rgb);
    //LOGE("rgb  %f, %f, %f ", rgb[0], rgb[1], rgb[2]);
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

static uchar4 SC_convertColorTo8888_f3(float r, float g, float b) {
    uchar4 t;
    t.f[0] = (uint8_t)(r * 255.f);
    t.f[1] = (uint8_t)(g * 255.f);
    t.f[2] = (uint8_t)(b * 255.f);
    t.f[3] = 0xff;
    return t;
}

static uchar4 SC_convertColorTo8888_f4(float r, float g, float b, float a) {
    uchar4 t;
    t.f[0] = (uint8_t)(r * 255.f);
    t.f[1] = (uint8_t)(g * 255.f);
    t.f[2] = (uint8_t)(b * 255.f);
    t.f[3] = (uint8_t)(a * 255.f);
    return t;
}

static uint32_t SC_toClient(void *data, int cmdID, int len, int waitForSpace)
{
    GET_TLS();
    //LOGE("SC_toClient %i %i %i", cmdID, len, waitForSpace);
    return rsc->sendMessageToClient(data, cmdID, len, waitForSpace != 0);
}

static void SC_scriptCall(int scriptID)
{
    GET_TLS();
    rsc->runScript((Script *)scriptID, 0);
}

static void SC_debugP(int i, void *p)
{
    LOGE("debug P  %i  %p, %i", i, p, (int)p);
}

static void SC_debugPi(int i, int p)
{
    LOGE("debug Pi %i  0x%08x, %i", i, p, (int)p);
}

static void SC_debugPf(int i, float p)
{
    LOGE("debug Pf  %i  %f,   0x%08x", i, p, reinterpret_cast<uint32_t *>(&p)[0]);
}

int SC_divsi3(int a, int b)
{
    return a / b;
}

int SC_getAllocation(const void *ptr)
{
    GET_TLS();
    const Allocation *alloc = sc->ptrToAllocation(ptr);
    return (int)alloc;
}


//////////////////////////////////////////////////////////////////////////////
// Class implementation
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

ScriptCState::SymbolTable_t ScriptCState::gSyms[] = {
    { "__divsi3", (void *)&SC_divsi3 },

    // IO
    { "updateSimpleMesh", (void *)&SC_updateSimpleMesh },

    // OpenCL math
    { "_Z4acosf", (void *)&acosf },
    { "_Z5acoshf", (void *)&acoshf },
    { "_Z6acospif", (void *)&SC_acospi },
    { "_Z4asinf", (void *)&asinf },
    { "_Z5asinhf", (void *)&asinhf },
    { "_Z6asinpif", (void *)&SC_asinpi },
    { "_Z4atanf", (void *)&atanf },
    { "_Z5atan2f", (void *)&atan2f },
    { "_Z6atanpif", (void *)&SC_atanpi },
    { "_Z7atan2pif", (void *)&SC_atan2pi },
    { "_Z4cbrtf", (void *)&cbrtf },
    { "_Z4ceilf", (void *)&ceilf },
    { "_Z8copysignff", (void *)&copysignf },
    { "_Z3cosf", (void *)&cosf },
    { "_Z4coshf", (void *)&coshf },
    { "_Z5cospif", (void *)&SC_cospi },
    { "_Z4erfcf", (void *)&erfcf },
    { "_Z3erff", (void *)&erff },
    { "_Z3expf", (void *)&expf },
    { "_Z4exp2f", (void *)&exp2f },
    { "_Z5exp10f", (void *)&SC_exp10 },
    { "_Z5expm1f", (void *)&expm1f },
    { "_Z4fabsf", (void *)&fabsf },
    { "_Z4fdimff", (void *)&fdimf },
    { "_Z5floorf", (void *)&floorf },
    { "_Z3fmafff", (void *)&fmaf },
    { "_Z4fmaxff", (void *)&fmaxf },
    { "_Z4fminff", (void *)&fminf },  // float fmin(float, float)
    { "_Z4fmodff", (void *)&fmodf },
    { "_Z5fractfPf", (void *)&SC_fract },
    { "_Z5frexpfPi", (void *)&frexpf },
    { "_Z5hypotff", (void *)&hypotf },
    { "_Z5ilogbf", (void *)&ilogbf },
    { "_Z5ldexpfi", (void *)&ldexpf },
    { "_Z6lgammaf", (void *)&lgammaf },
    { "_Z3logf", (void *)&logf },
    { "_Z4log2f", (void *)&SC_log2 },
    { "_Z5log10f", (void *)&log10f },
    { "_Z5log1pf", (void *)&log1pf },
    //{ "logb", (void *)& },
    //{ "mad", (void *)& },
    { "modf", (void *)&modff },
    //{ "nan", (void *)& },
    { "_Z9nextafterff", (void *)&nextafterf },
    { "_Z3powff", (void *)&powf },
    { "_Z4pownfi", (void *)&SC_pown },
    { "_Z4powrff", (void *)&SC_powr },
    { "_Z9remainderff", (void *)&remainderf },
    { "remquo", (void *)&remquof },
    { "_Z4rintf", (void *)&rintf },
    { "_Z5rootnfi", (void *)&SC_rootn },
    { "_Z5roundf", (void *)&roundf },
    { "_Z5rsqrtf", (void *)&SC_rsqrt },
    { "_Z3sinf", (void *)&sinf },
    { "sincos", (void *)&SC_sincos },
    { "_Z4sinhf", (void *)&sinhf },
    { "_Z5sinpif", (void *)&SC_sinpi },
    { "_Z4sqrtf", (void *)&sqrtf },
    { "_Z3tanf", (void *)&tanf },
    { "_Z4tanhf", (void *)&tanhf },
    { "_Z5tanpif", (void *)&SC_tanpi },
    //{ "tgamma", (void *)& },
    { "_Z5truncf", (void *)&truncf },

    // OpenCL Int
    { "_Z3absi", (void *)&SC_abs_i32 },
    { "_Z3abss", (void *)&SC_abs_i16 },
    { "_Z3absc", (void *)&SC_abs_i8 },
    { "_Z3clzj", (void *)&SC_clz_u32 },
    { "_Z3clzt", (void *)&SC_clz_u16 },
    { "_Z3clzh", (void *)&SC_clz_u8 },
    { "_Z3clzi", (void *)&SC_clz_i32 },
    { "_Z3clzs", (void *)&SC_clz_i16 },
    { "_Z3clzc", (void *)&SC_clz_i8 },
    { "_Z3maxjj", (void *)&SC_max_u32 },
    { "_Z3maxtt", (void *)&SC_max_u16 },
    { "_Z3maxhh", (void *)&SC_max_u8 },
    { "_Z3maxii", (void *)&SC_max_i32 },
    { "_Z3maxss", (void *)&SC_max_i16 },
    { "_Z3maxcc", (void *)&SC_max_i8 },
    { "_Z3minjj", (void *)&SC_min_u32 },
    { "_Z3mintt", (void *)&SC_min_u16 },
    { "_Z3minhh", (void *)&SC_min_u8 },
    { "_Z3minii", (void *)&SC_min_i32 },
    { "_Z3minss", (void *)&SC_min_i16 },
    { "_Z3mincc", (void *)&SC_min_i8 },

    // OpenCL 6.11.4
    { "_Z5clampfff", (void *)&SC_clamp_f32 },
    { "_Z7degreesf", (void *)&SC_degrees },
    { "_Z3maxff", (void *)&SC_max_f32 },
    { "_Z3minff", (void *)&SC_min_f32 },
    { "_Z3mixfff", (void *)&SC_mix_f32 },
    { "_Z7radiansf", (void *)&SC_radians },
    { "_Z4stepff", (void *)&SC_step_f32 },
    //{ "smoothstep", (void *)& },
    { "_Z4signf", (void *)&SC_sign_f32 },




    { "modf", (void *)&fmod },
    { "_Z4fracf", (void *)&SC_frac },
    //{ "sinf_fast", (void *)&SC_sinf_fast },
    //{ "cosf_fast", (void *)&SC_cosf_fast },
    { "randf", (void *)&SC_randf },
    { "randf2", (void *)&SC_randf2 },
    { "sign", (void *)&SC_sign },
    { "clamp", (void *)&SC_clamp },
    { "distf2", (void *)&SC_distf2 },
    { "distf3", (void *)&SC_distf3 },
    { "magf2", (void *)&SC_magf2 },
    { "magf3", (void *)&SC_magf3 },
    { "normf", (void *)&SC_normf },
    { "mapf", (void *)&SC_mapf },
    { "noisef", (void *)&SC_noisef },
    { "noisef2", (void *)&SC_noisef2 },
    { "noisef3", (void *)&SC_noisef3 },
    { "turbulencef2", (void *)&SC_turbulencef2 },
    { "turbulencef3", (void *)&SC_turbulencef3 },

    // time
    { "second", (void *)&SC_second },
    { "minute", (void *)&SC_minute },
    { "hour", (void *)&SC_hour },
    { "day", (void *)&SC_day },
    { "month", (void *)&SC_month },
    { "year", (void *)&SC_year },
    { "uptimeMillis", (void*)&SC_uptimeMillis },      // TODO: use long instead
    { "startTimeMillis", (void*)&SC_startTimeMillis },      // TODO: use long instead
    { "elapsedTimeMillis", (void*)&SC_elapsedTimeMillis },      // TODO: use long instead

    // matrix
    { "matrixLoadIdentity", (void *)&SC_matrixLoadIdentity },
    { "matrixLoadFloat", (void *)&SC_matrixLoadFloat },
    { "matrixLoadMat", (void *)&SC_matrixLoadMat },
    { "matrixLoadRotate", (void *)&SC_matrixLoadRotate },
    { "matrixLoadScale", (void *)&SC_matrixLoadScale },
    { "matrixLoadTranslate", (void *)&SC_matrixLoadTranslate },
    { "matrixLoadMultiply", (void *)&SC_matrixLoadMultiply },
    { "matrixMultiply", (void *)&SC_matrixMultiply },
    { "matrixRotate", (void *)&SC_matrixRotate },
    { "matrixScale", (void *)&SC_matrixScale },
    { "matrixTranslate", (void *)&SC_matrixTranslate },

    // vector
    { "vec2Rand", (void *)&SC_vec2Rand },

    // vec3
    { "vec3Norm", (void *)&SC_vec3Norm },
    { "vec3Length", (void *)&SC_vec3Length },
    { "vec3Add", (void *)&SC_vec3Add },
    { "vec3Sub", (void *)&SC_vec3Sub },
    { "vec3Cross", (void *)&SC_vec3Cross },
    { "vec3Dot", (void *)&SC_vec3Dot },
    { "vec3Scale", (void *)&SC_vec3Scale },

    // vec4
    { "vec4Norm", (void *)&SC_vec4Norm },
    { "vec4Length", (void *)&SC_vec4Length },
    { "vec4Add", (void *)&SC_vec4Add },
    { "vec4Sub", (void *)&SC_vec4Sub },
    { "vec4Dot", (void *)&SC_vec4Dot },
    { "vec4Scale", (void *)&SC_vec4Scale },

    // context
    { "bindProgramFragment", (void *)&SC_bindProgramFragment },
    { "bindProgramStore", (void *)&SC_bindProgramStore },
    { "bindProgramVertex", (void *)&SC_bindProgramVertex },
    { "bindProgramRaster", (void *)&SC_bindProgramRaster },
    { "bindSampler", (void *)&SC_bindSampler },
    { "bindTexture", (void *)&SC_bindTexture },

    // vp
    { "vpLoadModelMatrix", (void *)&SC_vpLoadModelMatrix },
    { "vpLoadTextureMatrix", (void *)&SC_vpLoadTextureMatrix },

    // allocation
    { "allocGetDimX", (void *)&SC_allocGetDimX },
    { "allocGetDimY", (void *)&SC_allocGetDimY },
    { "allocGetDimZ", (void *)&SC_allocGetDimZ },
    { "allocGetDimLOD", (void *)&SC_allocGetDimLOD },
    { "allocGetDimFaces", (void *)&SC_allocGetDimFaces },

    // drawing
    { "drawRect", (void *)&SC_drawRect },
    { "drawQuad", (void *)&SC_drawQuad },
    { "drawQuadTexCoords", (void *)&SC_drawQuadTexCoords },
    { "drawSprite", (void *)&SC_drawSprite },
    { "drawSpriteScreenspace", (void *)&SC_drawSpriteScreenspace },
    { "drawSpriteScreenspaceCropped", (void *)&SC_drawSpriteScreenspaceCropped },
    { "drawLine", (void *)&SC_drawLine },
    { "drawPoint", (void *)&SC_drawPoint },
    { "drawSimpleMesh", (void *)&SC_drawSimpleMesh },
    { "drawSimpleMeshRange", (void *)&SC_drawSimpleMeshRange },


    // misc
    { "pfClearColor", (void *)&SC_ClearColor },
    { "color", (void *)&SC_color },
    { "hsb", (void *)&SC_hsb },
    { "hsbToRgb", (void *)&SC_hsbToRgb },
    { "hsbToAbgr", (void *)&SC_hsbToAbgr },
    { "pointAttenuation", (void *)&SC_pointAttenuation },

    { "uploadToTexture", (void *)&SC_uploadToTexture },
    { "uploadToBufferObject", (void *)&SC_uploadToBufferObject },

    { "syncToGL", (void *)&SC_syncToGL },

    { "getWidth", (void *)&SC_getWidth },
    { "getHeight", (void *)&SC_getHeight },

    { "sendToClient", (void *)&SC_toClient },

    { "_Z18convertColorTo8888fff", (void *)&SC_convertColorTo8888_f3 },
    { "_Z18convertColorTo8888ffff", (void *)&SC_convertColorTo8888_f4 },

    { "debugF", (void *)&SC_debugF },
    { "debugI32", (void *)&SC_debugI32 },
    { "debugHexF", (void *)&SC_debugHexF },
    { "debugHexI32", (void *)&SC_debugHexI32 },
    { "debugP", (void *)&SC_debugP },
    { "debugPf", (void *)&SC_debugPf },
    { "debugPi", (void *)&SC_debugPi },

    { "scriptCall", (void *)&SC_scriptCall },
    { "rsGetAllocation", (void *)&SC_getAllocation },


    { NULL, NULL }
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

