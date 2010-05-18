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

#include <time.h>

using namespace android;
using namespace android::renderscript;

#define GET_TLS()  Context::ScriptTLSStruct * tls = \
    (Context::ScriptTLSStruct *)pthread_getspecific(Context::gThreadTLSKey); \
    Context * rsc = tls->mContext; \
    ScriptC * sc = (ScriptC *) tls->mScript




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

static ScriptCState::SymbolTable_t gSyms[] = {
    { "__divsi3", (void *)&SC_divsi3 },

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

    // allocation
    { "allocGetDimX", (void *)&SC_allocGetDimX },
    { "allocGetDimY", (void *)&SC_allocGetDimY },
    { "allocGetDimZ", (void *)&SC_allocGetDimZ },
    { "allocGetDimLOD", (void *)&SC_allocGetDimLOD },
    { "allocGetDimFaces", (void *)&SC_allocGetDimFaces },


    // misc
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

