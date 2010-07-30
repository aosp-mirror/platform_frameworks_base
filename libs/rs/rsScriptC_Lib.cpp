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
    float r = (float)rand();
    return r / RAND_MAX * max;
}

static float SC_randf2(float min, float max)
{
    float r = (float)rand();
    return r / RAND_MAX * (max - min) + min;
}

static int SC_randi(int max)
{
    return (int)SC_randf(max);
}

static int SC_randi2(int min, int max)
{
    return (int)SC_randf2(min, max);
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

static int64_t SC_uptimeMillis()
{
    return nanoseconds_to_milliseconds(systemTime(SYSTEM_TIME_MONOTONIC));
}

static int64_t SC_uptimeNanos()
{
    return systemTime(SYSTEM_TIME_MONOTONIC);
}

static float SC_getDt()
{
    GET_TLS();
    int64_t l = sc->mEnviroment.mLastDtTime;
    sc->mEnviroment.mLastDtTime = systemTime(SYSTEM_TIME_MONOTONIC);
    return ((float)(sc->mEnviroment.mLastDtTime - l)) / 1.0e9;
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

const void * SC_getElementAtX(RsAllocation va, uint32_t x)
{
    const Allocation *a = static_cast<const Allocation *>(va);
    const Type *t = a->getType();
    const uint8_t *p = (const uint8_t *)a->getPtr();
    return &p[t->getElementSizeBytes() * x];
}

const void * SC_getElementAtXY(RsAllocation va, uint32_t x, uint32_t y)
{
    const Allocation *a = static_cast<const Allocation *>(va);
    const Type *t = a->getType();
    const uint8_t *p = (const uint8_t *)a->getPtr();
    return &p[t->getElementSizeBytes() * (x + y*t->getDimX())];
}

const void * SC_getElementAtXYZ(RsAllocation va, uint32_t x, uint32_t y, uint32_t z)
{
    const Allocation *a = static_cast<const Allocation *>(va);
    const Type *t = a->getType();
    const uint8_t *p = (const uint8_t *)a->getPtr();
    return &p[t->getElementSizeBytes() * (x + y*t->getDimX())];
}


static void SC_debugF(const char *s, float f) {
    LOGE("%s %f, 0x%08x", s, f, *((int *) (&f)));
}
static void SC_debugFv2(const char *s, rsvF_2 fv) {
    float *f = (float *)&fv;
    LOGE("%s {%f, %f}", s, f[0], f[1]);
}
static void SC_debugFv3(const char *s, rsvF_4 fv) {
    float *f = (float *)&fv;
    LOGE("%s {%f, %f, %f}", s, f[0], f[1], f[2]);
}
static void SC_debugFv4(const char *s, rsvF_4 fv) {
    float *f = (float *)&fv;
    LOGE("%s {%f, %f, %f, %f}", s, f[0], f[1], f[2], f[3]);
}
static void SC_debugI32(const char *s, int32_t i) {
    LOGE("%s %i  0x%x", s, i, i);
}
static void SC_debugU32(const char *s, uint32_t i) {
    LOGE("%s %i  0x%x", s, i, i);
}

static void SC_debugP(const char *s, const void *p) {
    LOGE("%s %p", s, p);
}

static uint32_t SC_toClient2(int cmdID, void *data, int len)
{
    GET_TLS();
    //LOGE("SC_toClient %i %i %i", cmdID, len);
    return rsc->sendMessageToClient(data, cmdID, len, false);
}

static uint32_t SC_toClient(int cmdID)
{
    GET_TLS();
    //LOGE("SC_toClient %i", cmdID);
    return rsc->sendMessageToClient(NULL, cmdID, 0, false);
}

static uint32_t SC_toClientBlocking2(int cmdID, void *data, int len)
{
    GET_TLS();
    //LOGE("SC_toClientBlocking %i %i", cmdID, len);
    return rsc->sendMessageToClient(data, cmdID, len, true);
}

static uint32_t SC_toClientBlocking(int cmdID)
{
    GET_TLS();
    //LOGE("SC_toClientBlocking %i", cmdID);
    return rsc->sendMessageToClient(NULL, cmdID, 0, true);
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


void SC_ForEach(RsScript vs,
                RsAllocation vin,
                RsAllocation vout,
                const void *usr)
{
    GET_TLS();
    const Allocation *ain = static_cast<const Allocation *>(vin);
    Allocation *aout = static_cast<Allocation *>(vout);
    Script *s = static_cast<Script *>(vs);
    s->runForEach(rsc, ain, aout, usr);
}

void SC_ForEach2(RsScript vs,
                RsAllocation vin,
                RsAllocation vout,
                const void *usr,
                const RsScriptCall *call)
{
    GET_TLS();
    const Allocation *ain = static_cast<const Allocation *>(vin);
    Allocation *aout = static_cast<Allocation *>(vout);
    Script *s = static_cast<Script *>(vs);
    s->runForEach(rsc, ain, aout, usr, call);
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

    // allocation
    { "_Z19rsAllocationGetDimX13rs_allocation", (void *)&SC_allocGetDimX },
    { "_Z19rsAllocationGetDimY13rs_allocation", (void *)&SC_allocGetDimY },
    { "_Z19rsAllocationGetDimZ13rs_allocation", (void *)&SC_allocGetDimZ },
    { "_Z21rsAllocationGetDimLOD13rs_allocation", (void *)&SC_allocGetDimLOD },
    { "_Z23rsAllocationGetDimFaces13rs_allocation", (void *)&SC_allocGetDimFaces },
    { "_Z15rsGetAllocationPKv", (void *)&SC_getAllocation },

    { "_Z14rsGetElementAt13rs_allocationj", (void *)&SC_getElementAtX },
    { "_Z14rsGetElementAt13rs_allocationjj", (void *)&SC_getElementAtXY },
    { "_Z14rsGetElementAt13rs_allocationjjj", (void *)&SC_getElementAtXYZ },

    // Debug
    { "_Z7rsDebugPKcf", (void *)&SC_debugF },
    { "_Z7rsDebugPKcDv2_f", (void *)&SC_debugFv2 },
    { "_Z7rsDebugPKcDv3_f", (void *)&SC_debugFv3 },
    { "_Z7rsDebugPKcDv4_f", (void *)&SC_debugFv4 },
    { "_Z7rsDebugPKci", (void *)&SC_debugI32 },
    { "_Z7rsDebugPKcj", (void *)&SC_debugU32 },
    { "_Z7rsDebugPKcPKv", (void *)&SC_debugP },

    // RS Math
    { "_Z6rsRandi", (void *)&SC_randi },
    { "_Z6rsRandii", (void *)&SC_randi2 },
    { "_Z6rsRandf", (void *)&SC_randf },
    { "_Z6rsRandff", (void *)&SC_randf2 },
    { "_Z6rsFracf", (void *)&SC_frac },

    // time
    { "_Z8rsSecondv", (void *)&SC_second },
    { "_Z8rsMinutev", (void *)&SC_minute },
    { "_Z6rsHourv", (void *)&SC_hour },
    { "_Z5rsDayv", (void *)&SC_day },
    { "_Z7rsMonthv", (void *)&SC_month },
    { "_Z6rsYearv", (void *)&SC_year },
    { "_Z14rsUptimeMillisv", (void*)&SC_uptimeMillis },
    { "_Z13rsUptimeNanosv", (void*)&SC_uptimeNanos },
    { "_Z7rsGetDtv", (void*)&SC_getDt },

    { "_Z14rsSendToClienti", (void *)&SC_toClient },
    { "_Z14rsSendToClientiPKvj", (void *)&SC_toClient2 },
    { "_Z22rsSendToClientBlockingi", (void *)&SC_toClientBlocking },
    { "_Z22rsSendToClientBlockingiPKvj", (void *)&SC_toClientBlocking2 },

    { "_Z9rsForEach9rs_script13rs_allocationS0_PKv", (void *)&SC_ForEach },
    //{ "_Z9rsForEach9rs_script13rs_allocationS0_PKv", (void *)&SC_ForEach2 },

////////////////////////////////////////////////////////////////////

    //{ "sinf_fast", (void *)&SC_sinf_fast },
    //{ "cosf_fast", (void *)&SC_cosf_fast },

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

