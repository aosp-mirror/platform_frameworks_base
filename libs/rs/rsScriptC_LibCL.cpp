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

// Implements rs_cl.rsh


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
    // OpenCL math
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

    // OpenCL Int
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

    // OpenCL 6.11.4
    { "_Z5clampfff", (void *)&SC_clamp_f32, true },
    { "_Z7degreesf", (void *)&SC_degrees, true },
    { "_Z3maxff", (void *)&SC_max_f32, true },
    { "_Z3minff", (void *)&SC_min_f32, true },
    { "_Z3mixfff", (void *)&SC_mix_f32, true },
    { "_Z7radiansf", (void *)&SC_radians, true },
    { "_Z4stepff", (void *)&SC_step_f32, true },
    //{ "smoothstep", (void *)&, true },
    { "_Z4signf", (void *)&SC_sign_f32, true },

    { NULL, NULL, false }
};

const ScriptCState::SymbolTable_t * ScriptCState::lookupSymbolCL(const char *sym) {
    ScriptCState::SymbolTable_t *syms = gSyms;

    while (syms->mPtr) {
        if (!strcmp(syms->mName, sym)) {
            return syms;
        }
        syms++;
    }
    return NULL;
}

