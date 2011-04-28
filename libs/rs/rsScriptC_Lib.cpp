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

#if 0
static float SC_sinf_fast(float x) {
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

static float SC_cosf_fast(float x) {
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
#endif

static float SC_randf(float max) {
    float r = (float)rand();
    r *= max;
    r /= RAND_MAX;
    return r;
}

static float SC_randf2(float min, float max) {
    float r = (float)rand();
    r /= RAND_MAX;
    r = r * (max - min) + min;
    return r;
}

static int SC_randi(int max) {
    return (int)SC_randf(max);
}

static int SC_randi2(int min, int max) {
    return (int)SC_randf2(min, max);
}

static float SC_frac(float v) {
    int i = (int)floor(v);
    return fmin(v - i, 0x1.fffffep-1f);
}

//////////////////////////////////////////////////////////////////////////////
// Time routines
//////////////////////////////////////////////////////////////////////////////

static time_t SC_time(time_t *timer) {
    GET_TLS();
    return time(timer);
}

static tm* SC_localtime(tm *local, time_t *timer) {
    GET_TLS();
    if (!local) {
      return NULL;
    }

    // The native localtime function is not thread-safe, so we
    // have to apply locking for proper behavior in RenderScript.
    pthread_mutex_lock(&rsc->gLibMutex);
    tm *tmp = localtime(timer);
    memcpy(local, tmp, sizeof(*tmp));
    pthread_mutex_unlock(&rsc->gLibMutex);
    return local;
}

static int64_t SC_uptimeMillis() {
    return nanoseconds_to_milliseconds(systemTime(SYSTEM_TIME_MONOTONIC));
}

static int64_t SC_uptimeNanos() {
    return systemTime(SYSTEM_TIME_MONOTONIC);
}

static float SC_getDt() {
    GET_TLS();
    int64_t l = sc->mEnviroment.mLastDtTime;
    sc->mEnviroment.mLastDtTime = systemTime(SYSTEM_TIME_MONOTONIC);
    return ((float)(sc->mEnviroment.mLastDtTime - l)) / 1.0e9;
}

//////////////////////////////////////////////////////////////////////////////
//
//////////////////////////////////////////////////////////////////////////////

static uint32_t SC_allocGetDimX(RsAllocation va) {
    const Allocation *a = static_cast<const Allocation *>(va);
    CHECK_OBJ(a);
    //LOGE("SC_allocGetDimX a=%p  type=%p", a, a->getType());
    return a->getType()->getDimX();
}

static uint32_t SC_allocGetDimY(RsAllocation va) {
    const Allocation *a = static_cast<const Allocation *>(va);
    CHECK_OBJ(a);
    return a->getType()->getDimY();
}

static uint32_t SC_allocGetDimZ(RsAllocation va) {
    const Allocation *a = static_cast<const Allocation *>(va);
    CHECK_OBJ(a);
    return a->getType()->getDimZ();
}

static uint32_t SC_allocGetDimLOD(RsAllocation va) {
    const Allocation *a = static_cast<const Allocation *>(va);
    CHECK_OBJ(a);
    return a->getType()->getDimLOD();
}

static uint32_t SC_allocGetDimFaces(RsAllocation va) {
    const Allocation *a = static_cast<const Allocation *>(va);
    CHECK_OBJ(a);
    return a->getType()->getDimFaces();
}

static const void * SC_getElementAtX(RsAllocation va, uint32_t x) {
    const Allocation *a = static_cast<const Allocation *>(va);
    CHECK_OBJ(a);
    const Type *t = a->getType();
    CHECK_OBJ(t);
    const uint8_t *p = (const uint8_t *)a->getPtr();
    return &p[t->getElementSizeBytes() * x];
}

static const void * SC_getElementAtXY(RsAllocation va, uint32_t x, uint32_t y) {
    const Allocation *a = static_cast<const Allocation *>(va);
    CHECK_OBJ(a);
    const Type *t = a->getType();
    CHECK_OBJ(t);
    const uint8_t *p = (const uint8_t *)a->getPtr();
    return &p[t->getElementSizeBytes() * (x + y*t->getDimX())];
}

static const void * SC_getElementAtXYZ(RsAllocation va, uint32_t x, uint32_t y, uint32_t z) {
    const Allocation *a = static_cast<const Allocation *>(va);
    CHECK_OBJ(a);
    const Type *t = a->getType();
    CHECK_OBJ(t);
    const uint8_t *p = (const uint8_t *)a->getPtr();
    return &p[t->getElementSizeBytes() * (x + y*t->getDimX())];
}

static void SC_setObject(void **vdst, void * vsrc) {
    //LOGE("SC_setObject  %p,%p  %p", vdst, *vdst, vsrc);
    if (vsrc) {
        CHECK_OBJ(vsrc);
        static_cast<ObjectBase *>(vsrc)->incSysRef();
    }
    if (vdst[0]) {
        CHECK_OBJ(vdst[0]);
        static_cast<ObjectBase *>(vdst[0])->decSysRef();
    }
    *vdst = vsrc;
    //LOGE("SC_setObject *");
}

static void SC_clearObject(void **vdst) {
    //LOGE("SC_clearObject  %p,%p", vdst, *vdst);
    if (vdst[0]) {
        CHECK_OBJ(vdst[0]);
        static_cast<ObjectBase *>(vdst[0])->decSysRef();
    }
    *vdst = NULL;
    //LOGE("SC_clearObject *");
}

static bool SC_isObject(RsAllocation vsrc) {
    return vsrc != NULL;
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

static uint32_t SC_toClient2(int cmdID, void *data, int len) {
    GET_TLS();
    //LOGE("SC_toClient %i %i %i", cmdID, len);
    return rsc->sendMessageToClient(data, RS_MESSAGE_TO_CLIENT_USER, cmdID, len, false);
}

static uint32_t SC_toClient(int cmdID) {
    GET_TLS();
    //LOGE("SC_toClient %i", cmdID);
    return rsc->sendMessageToClient(NULL, RS_MESSAGE_TO_CLIENT_USER, cmdID, 0, false);
}

static uint32_t SC_toClientBlocking2(int cmdID, void *data, int len) {
    GET_TLS();
    //LOGE("SC_toClientBlocking %i %i", cmdID, len);
    return rsc->sendMessageToClient(data, RS_MESSAGE_TO_CLIENT_USER, cmdID, len, true);
}

static uint32_t SC_toClientBlocking(int cmdID) {
    GET_TLS();
    //LOGE("SC_toClientBlocking %i", cmdID);
    return rsc->sendMessageToClient(NULL, RS_MESSAGE_TO_CLIENT_USER, cmdID, 0, true);
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

int SC_getAllocation(const void *ptr) {
    GET_TLS();
    const Allocation *alloc = sc->ptrToAllocation(ptr);
    return (int)alloc;
}

void SC_allocationMarkDirty(RsAllocation a) {
    Allocation *alloc = static_cast<Allocation *>(a);
    alloc->sendDirty();
}

void SC_ForEach(RsScript vs,
                RsAllocation vin,
                RsAllocation vout,
                const void *usr) {
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
                const RsScriptCall *call) {
    GET_TLS();
    const Allocation *ain = static_cast<const Allocation *>(vin);
    Allocation *aout = static_cast<Allocation *>(vout);
    Script *s = static_cast<Script *>(vs);
    s->runForEach(rsc, ain, aout, usr, call);
}


//////////////////////////////////////////////////////////////////////////////
// Heavy math functions
//////////////////////////////////////////////////////////////////////////////

typedef struct {
    float m[16];
} rs_matrix4x4;

typedef struct {
    float m[9];
} rs_matrix3x3;

typedef struct {
    float m[4];
} rs_matrix2x2;

static inline void
rsMatrixSet(rs_matrix4x4 *m, uint32_t row, uint32_t col, float v) {
    m->m[row * 4 + col] = v;
}

static inline float
rsMatrixGet(const rs_matrix4x4 *m, uint32_t row, uint32_t col) {
    return m->m[row * 4 + col];
}

static inline void
rsMatrixSet(rs_matrix3x3 *m, uint32_t row, uint32_t col, float v) {
    m->m[row * 3 + col] = v;
}

static inline float
rsMatrixGet(const rs_matrix3x3 *m, uint32_t row, uint32_t col) {
    return m->m[row * 3 + col];
}

static inline void
rsMatrixSet(rs_matrix2x2 *m, uint32_t row, uint32_t col, float v) {
    m->m[row * 2 + col] = v;
}

static inline float
rsMatrixGet(const rs_matrix2x2 *m, uint32_t row, uint32_t col) {
    return m->m[row * 2 + col];
}


static void SC_MatrixLoadIdentity_4x4(rs_matrix4x4 *m) {
    m->m[0] = 1.f;
    m->m[1] = 0.f;
    m->m[2] = 0.f;
    m->m[3] = 0.f;
    m->m[4] = 0.f;
    m->m[5] = 1.f;
    m->m[6] = 0.f;
    m->m[7] = 0.f;
    m->m[8] = 0.f;
    m->m[9] = 0.f;
    m->m[10] = 1.f;
    m->m[11] = 0.f;
    m->m[12] = 0.f;
    m->m[13] = 0.f;
    m->m[14] = 0.f;
    m->m[15] = 1.f;
}

static void SC_MatrixLoadIdentity_3x3(rs_matrix3x3 *m) {
    m->m[0] = 1.f;
    m->m[1] = 0.f;
    m->m[2] = 0.f;
    m->m[3] = 0.f;
    m->m[4] = 1.f;
    m->m[5] = 0.f;
    m->m[6] = 0.f;
    m->m[7] = 0.f;
    m->m[8] = 1.f;
}

static void SC_MatrixLoadIdentity_2x2(rs_matrix2x2 *m) {
    m->m[0] = 1.f;
    m->m[1] = 0.f;
    m->m[2] = 0.f;
    m->m[3] = 1.f;
}

static void SC_MatrixLoad_4x4_f(rs_matrix4x4 *m, const float *v) {
    m->m[0] = v[0];
    m->m[1] = v[1];
    m->m[2] = v[2];
    m->m[3] = v[3];
    m->m[4] = v[4];
    m->m[5] = v[5];
    m->m[6] = v[6];
    m->m[7] = v[7];
    m->m[8] = v[8];
    m->m[9] = v[9];
    m->m[10] = v[10];
    m->m[11] = v[11];
    m->m[12] = v[12];
    m->m[13] = v[13];
    m->m[14] = v[14];
    m->m[15] = v[15];
}

static void SC_MatrixLoad_3x3_f(rs_matrix3x3 *m, const float *v) {
    m->m[0] = v[0];
    m->m[1] = v[1];
    m->m[2] = v[2];
    m->m[3] = v[3];
    m->m[4] = v[4];
    m->m[5] = v[5];
    m->m[6] = v[6];
    m->m[7] = v[7];
    m->m[8] = v[8];
}

static void SC_MatrixLoad_2x2_f(rs_matrix2x2 *m, const float *v) {
    m->m[0] = v[0];
    m->m[1] = v[1];
    m->m[2] = v[2];
    m->m[3] = v[3];
}

static void SC_MatrixLoad_4x4_4x4(rs_matrix4x4 *m, const rs_matrix4x4 *v) {
    m->m[0] = v->m[0];
    m->m[1] = v->m[1];
    m->m[2] = v->m[2];
    m->m[3] = v->m[3];
    m->m[4] = v->m[4];
    m->m[5] = v->m[5];
    m->m[6] = v->m[6];
    m->m[7] = v->m[7];
    m->m[8] = v->m[8];
    m->m[9] = v->m[9];
    m->m[10] = v->m[10];
    m->m[11] = v->m[11];
    m->m[12] = v->m[12];
    m->m[13] = v->m[13];
    m->m[14] = v->m[14];
    m->m[15] = v->m[15];
}

static void SC_MatrixLoad_4x4_3x3(rs_matrix4x4 *m, const rs_matrix3x3 *v) {
    m->m[0] = v->m[0];
    m->m[1] = v->m[1];
    m->m[2] = v->m[2];
    m->m[3] = 0.f;
    m->m[4] = v->m[3];
    m->m[5] = v->m[4];
    m->m[6] = v->m[5];
    m->m[7] = 0.f;
    m->m[8] = v->m[6];
    m->m[9] = v->m[7];
    m->m[10] = v->m[8];
    m->m[11] = 0.f;
    m->m[12] = 0.f;
    m->m[13] = 0.f;
    m->m[14] = 0.f;
    m->m[15] = 1.f;
}

static void SC_MatrixLoad_4x4_2x2(rs_matrix4x4 *m, const rs_matrix2x2 *v) {
    m->m[0] = v->m[0];
    m->m[1] = v->m[1];
    m->m[2] = 0.f;
    m->m[3] = 0.f;
    m->m[4] = v->m[2];
    m->m[5] = v->m[3];
    m->m[6] = 0.f;
    m->m[7] = 0.f;
    m->m[8] = 0.f;
    m->m[9] = 0.f;
    m->m[10] = 1.f;
    m->m[11] = 0.f;
    m->m[12] = 0.f;
    m->m[13] = 0.f;
    m->m[14] = 0.f;
    m->m[15] = 1.f;
}

static void SC_MatrixLoad_3x3_3x3(rs_matrix3x3 *m, const rs_matrix3x3 *v) {
    m->m[0] = v->m[0];
    m->m[1] = v->m[1];
    m->m[2] = v->m[2];
    m->m[3] = v->m[3];
    m->m[4] = v->m[4];
    m->m[5] = v->m[5];
    m->m[6] = v->m[6];
    m->m[7] = v->m[7];
    m->m[8] = v->m[8];
}

static void SC_MatrixLoad_2x2_2x2(rs_matrix2x2 *m, const rs_matrix2x2 *v) {
    m->m[0] = v->m[0];
    m->m[1] = v->m[1];
    m->m[2] = v->m[2];
    m->m[3] = v->m[3];
}

static void SC_MatrixLoadRotate(rs_matrix4x4 *m, float rot, float x, float y, float z) {
    float c, s;
    m->m[3] = 0;
    m->m[7] = 0;
    m->m[11]= 0;
    m->m[12]= 0;
    m->m[13]= 0;
    m->m[14]= 0;
    m->m[15]= 1;
    rot *= (float)(M_PI / 180.0f);
    c = cos(rot);
    s = sin(rot);

    const float len = x*x + y*y + z*z;
    if (len != 1) {
        const float recipLen = 1.f / sqrt(len);
        x *= recipLen;
        y *= recipLen;
        z *= recipLen;
    }
    const float nc = 1.0f - c;
    const float xy = x * y;
    const float yz = y * z;
    const float zx = z * x;
    const float xs = x * s;
    const float ys = y * s;
    const float zs = z * s;
    m->m[ 0] = x*x*nc +  c;
    m->m[ 4] =  xy*nc - zs;
    m->m[ 8] =  zx*nc + ys;
    m->m[ 1] =  xy*nc + zs;
    m->m[ 5] = y*y*nc +  c;
    m->m[ 9] =  yz*nc - xs;
    m->m[ 2] =  zx*nc - ys;
    m->m[ 6] =  yz*nc + xs;
    m->m[10] = z*z*nc +  c;
}

static void SC_MatrixLoadScale(rs_matrix4x4 *m, float x, float y, float z) {
    SC_MatrixLoadIdentity_4x4(m);
    m->m[0] = x;
    m->m[5] = y;
    m->m[10] = z;
}

static void SC_MatrixLoadTranslate(rs_matrix4x4 *m, float x, float y, float z) {
    SC_MatrixLoadIdentity_4x4(m);
    m->m[12] = x;
    m->m[13] = y;
    m->m[14] = z;
}

static void SC_MatrixLoadMultiply_4x4_4x4_4x4(rs_matrix4x4 *m, const rs_matrix4x4 *lhs, const rs_matrix4x4 *rhs) {
    for (int i=0 ; i<4 ; i++) {
        float ri0 = 0;
        float ri1 = 0;
        float ri2 = 0;
        float ri3 = 0;
        for (int j=0 ; j<4 ; j++) {
            const float rhs_ij = rsMatrixGet(rhs, i,j);
            ri0 += rsMatrixGet(lhs, j, 0) * rhs_ij;
            ri1 += rsMatrixGet(lhs, j, 1) * rhs_ij;
            ri2 += rsMatrixGet(lhs, j, 2) * rhs_ij;
            ri3 += rsMatrixGet(lhs, j, 3) * rhs_ij;
        }
        rsMatrixSet(m, i, 0, ri0);
        rsMatrixSet(m, i, 1, ri1);
        rsMatrixSet(m, i, 2, ri2);
        rsMatrixSet(m, i, 3, ri3);
    }
}

static void SC_MatrixMultiply_4x4_4x4(rs_matrix4x4 *m, const rs_matrix4x4 *rhs) {
    rs_matrix4x4 mt;
    SC_MatrixLoadMultiply_4x4_4x4_4x4(&mt, m, rhs);
    SC_MatrixLoad_4x4_4x4(m, &mt);
}

static void SC_MatrixLoadMultiply_3x3_3x3_3x3(rs_matrix3x3 *m, const rs_matrix3x3 *lhs, const rs_matrix3x3 *rhs) {
    for (int i=0 ; i<3 ; i++) {
        float ri0 = 0;
        float ri1 = 0;
        float ri2 = 0;
        for (int j=0 ; j<3 ; j++) {
            const float rhs_ij = rsMatrixGet(rhs, i,j);
            ri0 += rsMatrixGet(lhs, j, 0) * rhs_ij;
            ri1 += rsMatrixGet(lhs, j, 1) * rhs_ij;
            ri2 += rsMatrixGet(lhs, j, 2) * rhs_ij;
        }
        rsMatrixSet(m, i, 0, ri0);
        rsMatrixSet(m, i, 1, ri1);
        rsMatrixSet(m, i, 2, ri2);
    }
}

static void SC_MatrixMultiply_3x3_3x3(rs_matrix3x3 *m, const rs_matrix3x3 *rhs) {
    rs_matrix3x3 mt;
    SC_MatrixLoadMultiply_3x3_3x3_3x3(&mt, m, rhs);
    SC_MatrixLoad_3x3_3x3(m, &mt);
}

static void SC_MatrixLoadMultiply_2x2_2x2_2x2(rs_matrix2x2 *m, const rs_matrix2x2 *lhs, const rs_matrix2x2 *rhs) {
    for (int i=0 ; i<2 ; i++) {
        float ri0 = 0;
        float ri1 = 0;
        for (int j=0 ; j<2 ; j++) {
            const float rhs_ij = rsMatrixGet(rhs, i,j);
            ri0 += rsMatrixGet(lhs, j, 0) * rhs_ij;
            ri1 += rsMatrixGet(lhs, j, 1) * rhs_ij;
        }
        rsMatrixSet(m, i, 0, ri0);
        rsMatrixSet(m, i, 1, ri1);
    }
}

static void SC_MatrixMultiply_2x2_2x2(rs_matrix2x2 *m, const rs_matrix2x2 *rhs) {
    rs_matrix2x2 mt;
    SC_MatrixLoadMultiply_2x2_2x2_2x2(&mt, m, rhs);
    SC_MatrixLoad_2x2_2x2(m, &mt);
}

static void SC_MatrixRotate(rs_matrix4x4 *m, float rot, float x, float y, float z) {
    rs_matrix4x4 m1;
    SC_MatrixLoadRotate(&m1, rot, x, y, z);
    SC_MatrixMultiply_4x4_4x4(m, &m1);
}

static void SC_MatrixScale(rs_matrix4x4 *m, float x, float y, float z) {
    rs_matrix4x4 m1;
    SC_MatrixLoadScale(&m1, x, y, z);
    SC_MatrixMultiply_4x4_4x4(m, &m1);
}

static void SC_MatrixTranslate(rs_matrix4x4 *m, float x, float y, float z) {
    rs_matrix4x4 m1;
    SC_MatrixLoadTranslate(&m1, x, y, z);
    SC_MatrixMultiply_4x4_4x4(m, &m1);
}

static void SC_MatrixLoadOrtho(rs_matrix4x4 *m, float left, float right, float bottom, float top, float near, float far) {
    SC_MatrixLoadIdentity_4x4(m);
    m->m[0] = 2.f / (right - left);
    m->m[5] = 2.f / (top - bottom);
    m->m[10]= -2.f / (far - near);
    m->m[12]= -(right + left) / (right - left);
    m->m[13]= -(top + bottom) / (top - bottom);
    m->m[14]= -(far + near) / (far - near);
}

static void SC_MatrixLoadFrustum(rs_matrix4x4 *m, float left, float right, float bottom, float top, float near, float far) {
    SC_MatrixLoadIdentity_4x4(m);
    m->m[0] = 2.f * near / (right - left);
    m->m[5] = 2.f * near / (top - bottom);
    m->m[8] = (right + left) / (right - left);
    m->m[9] = (top + bottom) / (top - bottom);
    m->m[10]= -(far + near) / (far - near);
    m->m[11]= -1.f;
    m->m[14]= -2.f * far * near / (far - near);
    m->m[15]= 0.f;
}

static void SC_MatrixLoadPerspective(rs_matrix4x4* m, float fovy, float aspect, float near, float far) {
    float top = near * tan((float) (fovy * M_PI / 360.0f));
    float bottom = -top;
    float left = bottom * aspect;
    float right = top * aspect;
    SC_MatrixLoadFrustum(m, left, right, bottom, top, near, far);
}


// Returns true if the matrix was successfully inversed
static bool SC_MatrixInverse_4x4(rs_matrix4x4 *m) {
    rs_matrix4x4 result;

    int i, j;
    for (i = 0; i < 4; ++i) {
        for (j = 0; j < 4; ++j) {
            // computeCofactor for int i, int j
            int c0 = (i+1) % 4;
            int c1 = (i+2) % 4;
            int c2 = (i+3) % 4;
            int r0 = (j+1) % 4;
            int r1 = (j+2) % 4;
            int r2 = (j+3) % 4;

            float minor = (m->m[c0 + 4*r0] * (m->m[c1 + 4*r1] * m->m[c2 + 4*r2] - m->m[c1 + 4*r2] * m->m[c2 + 4*r1]))
                         - (m->m[c0 + 4*r1] * (m->m[c1 + 4*r0] * m->m[c2 + 4*r2] - m->m[c1 + 4*r2] * m->m[c2 + 4*r0]))
                         + (m->m[c0 + 4*r2] * (m->m[c1 + 4*r0] * m->m[c2 + 4*r1] - m->m[c1 + 4*r1] * m->m[c2 + 4*r0]));

            float cofactor = (i+j) & 1 ? -minor : minor;

            result.m[4*i + j] = cofactor;
        }
    }

    // Dot product of 0th column of source and 0th row of result
    float det = m->m[0]*result.m[0] + m->m[4]*result.m[1] +
                 m->m[8]*result.m[2] + m->m[12]*result.m[3];

    if (fabs(det) < 1e-6) {
        return false;
    }

    det = 1.0f / det;
    for (i = 0; i < 16; ++i) {
        m->m[i] = result.m[i] * det;
    }

    return true;
}

// Returns true if the matrix was successfully inversed
static bool SC_MatrixInverseTranspose_4x4(rs_matrix4x4 *m) {
    rs_matrix4x4 result;

    int i, j;
    for (i = 0; i < 4; ++i) {
        for (j = 0; j < 4; ++j) {
            // computeCofactor for int i, int j
            int c0 = (i+1) % 4;
            int c1 = (i+2) % 4;
            int c2 = (i+3) % 4;
            int r0 = (j+1) % 4;
            int r1 = (j+2) % 4;
            int r2 = (j+3) % 4;

            float minor = (m->m[c0 + 4*r0] * (m->m[c1 + 4*r1] * m->m[c2 + 4*r2] - m->m[c1 + 4*r2] * m->m[c2 + 4*r1]))
                         - (m->m[c0 + 4*r1] * (m->m[c1 + 4*r0] * m->m[c2 + 4*r2] - m->m[c1 + 4*r2] * m->m[c2 + 4*r0]))
                         + (m->m[c0 + 4*r2] * (m->m[c1 + 4*r0] * m->m[c2 + 4*r1] - m->m[c1 + 4*r1] * m->m[c2 + 4*r0]));

            float cofactor = (i+j) & 1 ? -minor : minor;

            result.m[4*j + i] = cofactor;
        }
    }

    // Dot product of 0th column of source and 0th column of result
    float det = m->m[0]*result.m[0] + m->m[4]*result.m[4] +
                 m->m[8]*result.m[8] + m->m[12]*result.m[12];

    if (fabs(det) < 1e-6) {
        return false;
    }

    det = 1.0f / det;
    for (i = 0; i < 16; ++i) {
        m->m[i] = result.m[i] * det;
    }

    return true;
}

static void SC_MatrixTranspose_4x4(rs_matrix4x4 *m) {
    int i, j;
    float temp;
    for (i = 0; i < 3; ++i) {
        for (j = i + 1; j < 4; ++j) {
            temp = m->m[i*4 + j];
            m->m[i*4 + j] = m->m[j*4 + i];
            m->m[j*4 + i] = temp;
        }
    }
}

static void SC_MatrixTranspose_3x3(rs_matrix3x3 *m) {
    int i, j;
    float temp;
    for (i = 0; i < 2; ++i) {
        for (j = i + 1; j < 3; ++j) {
            temp = m->m[i*3 + j];
            m->m[i*3 + j] = m->m[j*4 + i];
            m->m[j*3 + i] = temp;
        }
    }
}

static void SC_MatrixTranspose_2x2(rs_matrix2x2 *m) {
    float temp = m->m[1];
    m->m[1] = m->m[2];
    m->m[2] = temp;
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
    { "__divsi3", (void *)&SC_divsi3, true },
    { "__modsi3", (void *)&SC_modsi3, true },
    { "__udivsi3", (void *)&SC_udivsi3, true },
    { "__umodsi3", (void *)&SC_umodsi3, true },
    { "memset", (void *)&memset, true },
    { "memcpy", (void *)&memcpy, true },

    // allocation
    { "_Z19rsAllocationGetDimX13rs_allocation", (void *)&SC_allocGetDimX, true },
    { "_Z19rsAllocationGetDimY13rs_allocation", (void *)&SC_allocGetDimY, true },
    { "_Z19rsAllocationGetDimZ13rs_allocation", (void *)&SC_allocGetDimZ, true },
    { "_Z21rsAllocationGetDimLOD13rs_allocation", (void *)&SC_allocGetDimLOD, true },
    { "_Z23rsAllocationGetDimFaces13rs_allocation", (void *)&SC_allocGetDimFaces, true },
    { "_Z15rsGetAllocationPKv", (void *)&SC_getAllocation, true },

    { "_Z14rsGetElementAt13rs_allocationj", (void *)&SC_getElementAtX, true },
    { "_Z14rsGetElementAt13rs_allocationjj", (void *)&SC_getElementAtXY, true },
    { "_Z14rsGetElementAt13rs_allocationjjj", (void *)&SC_getElementAtXYZ, true },

    { "_Z11rsSetObjectP10rs_elementS_", (void *)&SC_setObject, true },
    { "_Z13rsClearObjectP10rs_element", (void *)&SC_clearObject, true },
    { "_Z10rsIsObject10rs_element", (void *)&SC_isObject, true },

    { "_Z11rsSetObjectP7rs_typeS_", (void *)&SC_setObject, true },
    { "_Z13rsClearObjectP7rs_type", (void *)&SC_clearObject, true },
    { "_Z10rsIsObject7rs_type", (void *)&SC_isObject, true },

    { "_Z11rsSetObjectP13rs_allocationS_", (void *)&SC_setObject, true },
    { "_Z13rsClearObjectP13rs_allocation", (void *)&SC_clearObject, true },
    { "_Z10rsIsObject13rs_allocation", (void *)&SC_isObject, true },

    { "_Z11rsSetObjectP10rs_samplerS_", (void *)&SC_setObject, true },
    { "_Z13rsClearObjectP10rs_sampler", (void *)&SC_clearObject, true },
    { "_Z10rsIsObject10rs_sampler", (void *)&SC_isObject, true },

    { "_Z11rsSetObjectP9rs_scriptS_", (void *)&SC_setObject, true },
    { "_Z13rsClearObjectP9rs_script", (void *)&SC_clearObject, true },
    { "_Z10rsIsObject9rs_script", (void *)&SC_isObject, true },

    { "_Z11rsSetObjectP7rs_meshS_", (void *)&SC_setObject, true },
    { "_Z13rsClearObjectP7rs_mesh", (void *)&SC_clearObject, true },
    { "_Z10rsIsObject7rs_mesh", (void *)&SC_isObject, true },

    { "_Z11rsSetObjectP19rs_program_fragmentS_", (void *)&SC_setObject, true },
    { "_Z13rsClearObjectP19rs_program_fragment", (void *)&SC_clearObject, true },
    { "_Z10rsIsObject19rs_program_fragment", (void *)&SC_isObject, true },

    { "_Z11rsSetObjectP17rs_program_vertexS_", (void *)&SC_setObject, true },
    { "_Z13rsClearObjectP17rs_program_vertex", (void *)&SC_clearObject, true },
    { "_Z10rsIsObject17rs_program_vertex", (void *)&SC_isObject, true },

    { "_Z11rsSetObjectP17rs_program_rasterS_", (void *)&SC_setObject, true },
    { "_Z13rsClearObjectP17rs_program_raster", (void *)&SC_clearObject, true },
    { "_Z10rsIsObject17rs_program_raster", (void *)&SC_isObject, true },

    { "_Z11rsSetObjectP16rs_program_storeS_", (void *)&SC_setObject, true },
    { "_Z13rsClearObjectP16rs_program_store", (void *)&SC_clearObject, true },
    { "_Z10rsIsObject16rs_program_store", (void *)&SC_isObject, true },

    { "_Z11rsSetObjectP7rs_fontS_", (void *)&SC_setObject, true },
    { "_Z13rsClearObjectP7rs_font", (void *)&SC_clearObject, true },
    { "_Z10rsIsObject7rs_font", (void *)&SC_isObject, true },


    { "_Z21rsAllocationMarkDirty13rs_allocation", (void *)&SC_allocationMarkDirty, true },


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

    // RS Math
    { "_Z6rsRandi", (void *)&SC_randi, true },
    { "_Z6rsRandii", (void *)&SC_randi2, true },
    { "_Z6rsRandf", (void *)&SC_randf, true },
    { "_Z6rsRandff", (void *)&SC_randf2, true },
    { "_Z6rsFracf", (void *)&SC_frac, true },

    // time
    { "_Z6rsTimePi", (void *)&SC_time, true },
    { "_Z11rsLocaltimeP5rs_tmPKi", (void *)&SC_localtime, true },
    { "_Z14rsUptimeMillisv", (void*)&SC_uptimeMillis, true },
    { "_Z13rsUptimeNanosv", (void*)&SC_uptimeNanos, true },
    { "_Z7rsGetDtv", (void*)&SC_getDt, false },

    { "_Z14rsSendToClienti", (void *)&SC_toClient, false },
    { "_Z14rsSendToClientiPKvj", (void *)&SC_toClient2, false },
    { "_Z22rsSendToClientBlockingi", (void *)&SC_toClientBlocking, false },
    { "_Z22rsSendToClientBlockingiPKvj", (void *)&SC_toClientBlocking2, false },

    // matrix
    { "_Z20rsMatrixLoadIdentityP12rs_matrix4x4", (void *)&SC_MatrixLoadIdentity_4x4, true },
    { "_Z20rsMatrixLoadIdentityP12rs_matrix3x3", (void *)&SC_MatrixLoadIdentity_3x3, true },
    { "_Z20rsMatrixLoadIdentityP12rs_matrix2x2", (void *)&SC_MatrixLoadIdentity_2x2, true },

    { "_Z12rsMatrixLoadP12rs_matrix4x4PKf", (void *)&SC_MatrixLoad_4x4_f, true },
    { "_Z12rsMatrixLoadP12rs_matrix3x3PKf", (void *)&SC_MatrixLoad_3x3_f, true },
    { "_Z12rsMatrixLoadP12rs_matrix2x2PKf", (void *)&SC_MatrixLoad_2x2_f, true },

    { "_Z12rsMatrixLoadP12rs_matrix4x4PKS_", (void *)&SC_MatrixLoad_4x4_4x4, true },
    { "_Z12rsMatrixLoadP12rs_matrix4x4PK12rs_matrix3x3", (void *)&SC_MatrixLoad_4x4_3x3, true },
    { "_Z12rsMatrixLoadP12rs_matrix4x4PK12rs_matrix2x2", (void *)&SC_MatrixLoad_4x4_2x2, true },
    { "_Z12rsMatrixLoadP12rs_matrix3x3PKS_", (void *)&SC_MatrixLoad_3x3_3x3, true },
    { "_Z12rsMatrixLoadP12rs_matrix2x2PKS_", (void *)&SC_MatrixLoad_2x2_2x2, true },

    { "_Z18rsMatrixLoadRotateP12rs_matrix4x4ffff", (void *)&SC_MatrixLoadRotate, true },
    { "_Z17rsMatrixLoadScaleP12rs_matrix4x4fff", (void *)&SC_MatrixLoadScale, true },
    { "_Z21rsMatrixLoadTranslateP12rs_matrix4x4fff", (void *)&SC_MatrixLoadTranslate, true },
    { "_Z14rsMatrixRotateP12rs_matrix4x4ffff", (void *)&SC_MatrixRotate, true },
    { "_Z13rsMatrixScaleP12rs_matrix4x4fff", (void *)&SC_MatrixScale, true },
    { "_Z17rsMatrixTranslateP12rs_matrix4x4fff", (void *)&SC_MatrixTranslate, true },

    { "_Z20rsMatrixLoadMultiplyP12rs_matrix4x4PKS_S2_", (void *)&SC_MatrixLoadMultiply_4x4_4x4_4x4, true },
    { "_Z16rsMatrixMultiplyP12rs_matrix4x4PKS_", (void *)&SC_MatrixMultiply_4x4_4x4, true },
    { "_Z20rsMatrixLoadMultiplyP12rs_matrix3x3PKS_S2_", (void *)&SC_MatrixLoadMultiply_3x3_3x3_3x3, true },
    { "_Z16rsMatrixMultiplyP12rs_matrix3x3PKS_", (void *)&SC_MatrixMultiply_3x3_3x3, true },
    { "_Z20rsMatrixLoadMultiplyP12rs_matrix2x2PKS_S2_", (void *)&SC_MatrixLoadMultiply_2x2_2x2_2x2, true },
    { "_Z16rsMatrixMultiplyP12rs_matrix2x2PKS_", (void *)&SC_MatrixMultiply_2x2_2x2, true },

    { "_Z17rsMatrixLoadOrthoP12rs_matrix4x4ffffff", (void *)&SC_MatrixLoadOrtho, true },
    { "_Z19rsMatrixLoadFrustumP12rs_matrix4x4ffffff", (void *)&SC_MatrixLoadFrustum, true },
    { "_Z23rsMatrixLoadPerspectiveP12rs_matrix4x4ffff", (void *)&SC_MatrixLoadPerspective, true },

    { "_Z15rsMatrixInverseP12rs_matrix4x4", (void *)&SC_MatrixInverse_4x4, true },
    { "_Z24rsMatrixInverseTransposeP12rs_matrix4x4", (void *)&SC_MatrixInverseTranspose_4x4, true },
    { "_Z17rsMatrixTransposeP12rs_matrix4x4", (void *)&SC_MatrixTranspose_4x4, true },
    { "_Z17rsMatrixTransposeP12rs_matrix4x4", (void *)&SC_MatrixTranspose_3x3, true },
    { "_Z17rsMatrixTransposeP12rs_matrix4x4", (void *)&SC_MatrixTranspose_2x2, true },

    { "_Z9rsForEach9rs_script13rs_allocationS0_PKv", (void *)&SC_ForEach, false },
    //{ "_Z9rsForEach9rs_script13rs_allocationS0_PKv", (void *)&SC_ForEach2, true },

////////////////////////////////////////////////////////////////////

    //{ "sinf_fast", (void *)&SC_sinf_fast, true },
    //{ "cosf_fast", (void *)&SC_cosf_fast, true },

    { NULL, NULL, false }
};

const ScriptCState::SymbolTable_t * ScriptCState::lookupSymbol(const char *sym) {
    ScriptCState::SymbolTable_t *syms = gSyms;

    while (syms->mPtr) {
        if (!strcmp(syms->mName, sym)) {
            return syms;
        }
        syms++;
    }
    return NULL;
}
