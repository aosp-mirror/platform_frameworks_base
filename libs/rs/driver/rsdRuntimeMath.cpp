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

#include <cutils/compiler.h>

#include "rsContext.h"
#include "rsScriptC.h"
#include "rsMatrix4x4.h"
#include "rsMatrix3x3.h"
#include "rsMatrix2x2.h"

#include "rsdCore.h"
#include "rsdRuntime.h"


using namespace android;
using namespace android::renderscript;


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

static float SC_mad(float v1, float v2, float v3) {
    return v1 * v2 + v3;
}

#if 0
static float SC_pown(float v, int p) {
    return powf(v, (float)p);
}

static float SC_powr(float v, float p) {
    return powf(v, p);
}
#endif

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

static float SC_clamp_f32(float amount, float low, float high) {
    return amount < low ? low : (amount > high ? high : amount);
}

static float SC_degrees(float radians) {
    return radians * (180.f / M_PI);
}

static float SC_max_f32(float v, float v2) {
    return rsMax(v, v2);
}

static float SC_min_f32(float v, float v2) {
    return rsMin(v, v2);
}

static float SC_mix_f32(float start, float stop, float amount) {
    //LOGE("lerpf %f  %f  %f", start, stop, amount);
    return start + (stop - start) * amount;
}

static float SC_radians(float degrees) {
    return degrees * (M_PI / 180.f);
}

static float SC_step_f32(float edge, float v) {
    if (v < edge) return 0.f;
    return 1.f;
}

static float SC_sign_f32(float value) {
    if (value > 0) return 1.f;
    if (value < 0) return -1.f;
    return value;
}

static void SC_MatrixLoadIdentity_4x4(Matrix4x4 *m) {
    m->loadIdentity();
}
static void SC_MatrixLoadIdentity_3x3(Matrix3x3 *m) {
    m->loadIdentity();
}
static void SC_MatrixLoadIdentity_2x2(Matrix2x2 *m) {
    m->loadIdentity();
}

static void SC_MatrixLoad_4x4_f(Matrix4x4 *m, const float *f) {
    m->load(f);
}
static void SC_MatrixLoad_3x3_f(Matrix3x3 *m, const float *f) {
    m->load(f);
}
static void SC_MatrixLoad_2x2_f(Matrix2x2 *m, const float *f) {
    m->load(f);
}

static void SC_MatrixLoad_4x4_4x4(Matrix4x4 *m, const Matrix4x4 *s) {
    m->load(s);
}
static void SC_MatrixLoad_4x4_3x3(Matrix4x4 *m, const Matrix3x3 *s) {
    m->load(s);
}
static void SC_MatrixLoad_4x4_2x2(Matrix4x4 *m, const Matrix2x2 *s) {
    m->load(s);
}
static void SC_MatrixLoad_3x3_3x3(Matrix3x3 *m, const Matrix3x3 *s) {
    m->load(s);
}
static void SC_MatrixLoad_2x2_2x2(Matrix2x2 *m, const Matrix2x2 *s) {
    m->load(s);
}

static void SC_MatrixLoadRotate(Matrix4x4 *m, float rot, float x, float y, float z) {
    m->loadRotate(rot, x, y, z);
}
static void SC_MatrixLoadScale(Matrix4x4 *m, float x, float y, float z) {
    m->loadScale(x, y, z);
}
static void SC_MatrixLoadTranslate(Matrix4x4 *m, float x, float y, float z) {
    m->loadTranslate(x, y, z);
}
static void SC_MatrixRotate(Matrix4x4 *m, float rot, float x, float y, float z) {
    m->rotate(rot, x, y, z);
}
static void SC_MatrixScale(Matrix4x4 *m, float x, float y, float z) {
    m->scale(x, y, z);
}
static void SC_MatrixTranslate(Matrix4x4 *m, float x, float y, float z) {
    m->translate(x, y, z);
}

static void SC_MatrixLoadMultiply_4x4_4x4_4x4(Matrix4x4 *m, const Matrix4x4 *lhs, const Matrix4x4 *rhs) {
    m->loadMultiply(lhs, rhs);
}
static void SC_MatrixLoadMultiply_3x3_3x3_3x3(Matrix3x3 *m, const Matrix3x3 *lhs, const Matrix3x3 *rhs) {
    m->loadMultiply(lhs, rhs);
}
static void SC_MatrixLoadMultiply_2x2_2x2_2x2(Matrix2x2 *m, const Matrix2x2 *lhs, const Matrix2x2 *rhs) {
    m->loadMultiply(lhs, rhs);
}

static void SC_MatrixMultiply_4x4_4x4(Matrix4x4 *m, const Matrix4x4 *rhs) {
    m->multiply(rhs);
}
static void SC_MatrixMultiply_3x3_3x3(Matrix3x3 *m, const Matrix3x3 *rhs) {
    m->multiply(rhs);
}
static void SC_MatrixMultiply_2x2_2x2(Matrix2x2 *m, const Matrix2x2 *rhs) {
    m->multiply(rhs);
}

static void SC_MatrixLoadOrtho(Matrix4x4 *m, float l, float r, float b, float t, float n, float f) {
    m->loadOrtho(l, r, b, t, n, f);
}
static void SC_MatrixLoadFrustum(Matrix4x4 *m, float l, float r, float b, float t, float n, float f) {
    m->loadFrustum(l, r, b, t, n, f);
}
static void SC_MatrixLoadPerspective(Matrix4x4 *m, float fovy, float aspect, float near, float far) {
    m->loadPerspective(fovy, aspect, near, far);
}

static bool SC_MatrixInverse_4x4(Matrix4x4 *m) {
    return m->inverse();
}
static bool SC_MatrixInverseTranspose_4x4(Matrix4x4 *m) {
    return m->inverseTranspose();
}
static void SC_MatrixTranspose_4x4(Matrix4x4 *m) {
    m->transpose();
}
static void SC_MatrixTranspose_3x3(Matrix3x3 *m) {
    m->transpose();
}
static void SC_MatrixTranspose_2x2(Matrix2x2 *m) {
    m->transpose();
}

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


static int32_t SC_AtomicCas(volatile int32_t *ptr, int32_t expectedValue, int32_t newValue) {
    int32_t prev;

    do {
        int32_t ret = android_atomic_release_cas(expectedValue, newValue, ptr);
        if (!ret) {
            // The android cas return 0 if it wrote the value.  This means the
            // previous value was the expected value and we can return.
            return expectedValue;
        }
        // We didn't write the value and need to load the "previous" value.
        prev = *ptr;

        // A race condition exists where the expected value could appear after our cas failed
        // above.  In this case loop until we have a legit previous value or the
        // write passes.
        } while (prev == expectedValue);
    return prev;
}


static int32_t SC_AtomicInc(volatile int32_t *ptr) {
    return android_atomic_inc(ptr);
}

static int32_t SC_AtomicDec(volatile int32_t *ptr) {
    return android_atomic_dec(ptr);
}

static int32_t SC_AtomicAdd(volatile int32_t *ptr, int32_t value) {
    return android_atomic_add(value, ptr);
}

static int32_t SC_AtomicSub(volatile int32_t *ptr, int32_t value) {
    int32_t prev, status;
    do {
        prev = *ptr;
        status = android_atomic_release_cas(prev, prev - value, ptr);
    } while (CC_UNLIKELY(status != 0));
    return prev;
}

static int32_t SC_AtomicAnd(volatile int32_t *ptr, int32_t value) {
    return android_atomic_and(value, ptr);
}

static int32_t SC_AtomicOr(volatile int32_t *ptr, int32_t value) {
    return android_atomic_or(value, ptr);
}

static int32_t SC_AtomicXor(volatile int32_t *ptr, int32_t value) {
    int32_t prev, status;
    do {
        prev = *ptr;
        status = android_atomic_release_cas(prev, prev ^ value, ptr);
    } while (CC_UNLIKELY(status != 0));
    return prev;
}

static int32_t SC_AtomicMin(volatile int32_t *ptr, int32_t value) {
    int32_t prev, status;
    do {
        prev = *ptr;
        int32_t n = rsMin(value, prev);
        status = android_atomic_release_cas(prev, n, ptr);
    } while (CC_UNLIKELY(status != 0));
    return prev;
}

static int32_t SC_AtomicMax(volatile int32_t *ptr, int32_t value) {
    int32_t prev, status;
    do {
        prev = *ptr;
        int32_t n = rsMax(value, prev);
        status = android_atomic_release_cas(prev, n, ptr);
    } while (CC_UNLIKELY(status != 0));
    return prev;
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

static RsdSymbolTable gSyms[] = {
    { "_Z4acosf", (void *)&acosf, true },
    { "_Z5acoshf", (void *)&acoshf, true },
    { "_Z4asinf", (void *)&asinf, true },
    { "_Z5asinhf", (void *)&asinhf, true },
    { "_Z4atanf", (void *)&atanf, true },
    { "_Z5atan2ff", (void *)&atan2f, true },
    { "_Z5atanhf", (void *)&atanhf, true },
    { "_Z4cbrtf", (void *)&cbrtf, true },
    { "_Z4ceilf", (void *)&ceilf, true },
    { "_Z8copysignff", (void *)&copysignf, true },
    { "_Z3cosf", (void *)&cosf, true },
    { "_Z4coshf", (void *)&coshf, true },
    { "_Z4erfcf", (void *)&erfcf, true },
    { "_Z3erff", (void *)&erff, true },
    { "_Z3expf", (void *)&expf, true },
    { "_Z4exp2f", (void *)&exp2f, true },
    { "_Z5exp10f", (void *)&SC_exp10, true },
    { "_Z5expm1f", (void *)&expm1f, true },
    { "_Z4fabsf", (void *)&fabsf, true },
    { "_Z4fdimff", (void *)&fdimf, true },
    { "_Z5floorf", (void *)&floorf, true },
    { "_Z3fmafff", (void *)&fmaf, true },
    { "_Z4fmaxff", (void *)&fmaxf, true },
    { "_Z4fminff", (void *)&fminf, true },  // float fmin(float, float)
    { "_Z4fmodff", (void *)&fmodf, true },
    { "_Z5fractfPf", (void *)&SC_fract, true },
    { "_Z5frexpfPi", (void *)&frexpf, true },
    { "_Z5hypotff", (void *)&hypotf, true },
    { "_Z5ilogbf", (void *)&ilogbf, true },
    { "_Z5ldexpfi", (void *)&ldexpf, true },
    { "_Z6lgammaf", (void *)&lgammaf, true },
    { "_Z6lgammafPi", (void *)&lgammaf_r, true },
    { "_Z3logf", (void *)&logf, true },
    { "_Z4log2f", (void *)&SC_log2, true },
    { "_Z5log10f", (void *)&log10f, true },
    { "_Z5log1pf", (void *)&log1pf, true },
    { "_Z4logbf", (void *)&logbf, true },
    { "_Z3madfff", (void *)&SC_mad, true },
    { "_Z4modffPf", (void *)&modff, true },
    //{ "_Z3nanj", (void *)&SC_nan, true },
    { "_Z9nextafterff", (void *)&nextafterf, true },
    { "_Z3powff", (void *)&powf, true },
    { "_Z9remainderff", (void *)&remainderf, true },
    { "_Z6remquoffPi", (void *)&remquof, true },
    { "_Z4rintf", (void *)&rintf, true },
    { "_Z5rootnfi", (void *)&SC_rootn, true },
    { "_Z5roundf", (void *)&roundf, true },
    { "_Z5rsqrtf", (void *)&SC_rsqrt, true },
    { "_Z3sinf", (void *)&sinf, true },
    { "_Z6sincosfPf", (void *)&SC_sincos, true },
    { "_Z4sinhf", (void *)&sinhf, true },
    { "_Z4sqrtf", (void *)&sqrtf, true },
    { "_Z3tanf", (void *)&tanf, true },
    { "_Z4tanhf", (void *)&tanhf, true },
    { "_Z6tgammaf", (void *)&tgammaf, true },
    { "_Z5truncf", (void *)&truncf, true },

    { "_Z3absi", (void *)&SC_abs_i32, true },
    { "_Z3abss", (void *)&SC_abs_i16, true },
    { "_Z3absc", (void *)&SC_abs_i8, true },
    { "_Z3clzj", (void *)&SC_clz_u32, true },
    { "_Z3clzt", (void *)&SC_clz_u16, true },
    { "_Z3clzh", (void *)&SC_clz_u8, true },
    { "_Z3clzi", (void *)&SC_clz_i32, true },
    { "_Z3clzs", (void *)&SC_clz_i16, true },
    { "_Z3clzc", (void *)&SC_clz_i8, true },
    { "_Z3maxjj", (void *)&SC_max_u32, true },
    { "_Z3maxtt", (void *)&SC_max_u16, true },
    { "_Z3maxhh", (void *)&SC_max_u8, true },
    { "_Z3maxii", (void *)&SC_max_i32, true },
    { "_Z3maxss", (void *)&SC_max_i16, true },
    { "_Z3maxcc", (void *)&SC_max_i8, true },
    { "_Z3minjj", (void *)&SC_min_u32, true },
    { "_Z3mintt", (void *)&SC_min_u16, true },
    { "_Z3minhh", (void *)&SC_min_u8, true },
    { "_Z3minii", (void *)&SC_min_i32, true },
    { "_Z3minss", (void *)&SC_min_i16, true },
    { "_Z3mincc", (void *)&SC_min_i8, true },

    { "_Z5clampfff", (void *)&SC_clamp_f32, true },
    { "_Z7degreesf", (void *)&SC_degrees, true },
    { "_Z3maxff", (void *)&SC_max_f32, true },
    { "_Z3minff", (void *)&SC_min_f32, true },
    { "_Z3mixfff", (void *)&SC_mix_f32, true },
    { "_Z7radiansf", (void *)&SC_radians, true },
    { "_Z4stepff", (void *)&SC_step_f32, true },
    //{ "smoothstep", (void *)&, true },
    { "_Z4signf", (void *)&SC_sign_f32, true },

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

    // RS Math
    { "_Z6rsRandi", (void *)&SC_randi, true },
    { "_Z6rsRandii", (void *)&SC_randi2, true },
    { "_Z6rsRandf", (void *)&SC_randf, true },
    { "_Z6rsRandff", (void *)&SC_randf2, true },
    { "_Z6rsFracf", (void *)&SC_frac, true },

    // Atomics
    { "_Z11rsAtomicIncPVi", (void *)&SC_AtomicInc, true },
    { "_Z11rsAtomicIncPVj", (void *)&SC_AtomicInc, true },
    { "_Z11rsAtomicDecPVi", (void *)&SC_AtomicDec, true },
    { "_Z11rsAtomicDecPVj", (void *)&SC_AtomicDec, true },
    { "_Z11rsAtomicAddPVii", (void *)&SC_AtomicAdd, true },
    { "_Z11rsAtomicAddPVjj", (void *)&SC_AtomicAdd, true },
    { "_Z11rsAtomicSubPVii", (void *)&SC_AtomicSub, true },
    { "_Z11rsAtomicSubPVjj", (void *)&SC_AtomicSub, true },
    { "_Z11rsAtomicAndPVii", (void *)&SC_AtomicAnd, true },
    { "_Z11rsAtomicAndPVjj", (void *)&SC_AtomicAnd, true },
    { "_Z10rsAtomicOrPVii", (void *)&SC_AtomicOr, true },
    { "_Z10rsAtomicOrPVjj", (void *)&SC_AtomicOr, true },
    { "_Z11rsAtomicXorPVii", (void *)&SC_AtomicXor, true },
    { "_Z11rsAtomicXorPVjj", (void *)&SC_AtomicXor, true },
    { "_Z11rsAtomicMinPVii", (void *)&SC_AtomicMin, true },
    { "_Z11rsAtomicMinPVjj", (void *)&SC_AtomicMin, true },
    { "_Z11rsAtomicMaxPVii", (void *)&SC_AtomicMax, true },
    { "_Z11rsAtomicMaxPVjj", (void *)&SC_AtomicMax, true },
    { "_Z11rsAtomicCasPViii", (void *)&SC_AtomicCas, true },
    { "_Z11rsAtomicCasPVjjj", (void *)&SC_AtomicCas, true },

    { NULL, NULL, false }
};

const RsdSymbolTable * rsdLookupSymbolMath(const char *sym) {
    const RsdSymbolTable *syms = gSyms;

    while (syms->mPtr) {
        if (!strcmp(syms->mName, sym)) {
            return syms;
        }
        syms++;
    }
    return NULL;
}

