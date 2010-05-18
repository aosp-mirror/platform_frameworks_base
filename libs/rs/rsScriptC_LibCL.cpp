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

    { NULL, NULL }
};

const ScriptCState::SymbolTable_t * ScriptCState::lookupSymbolCL(const char *sym)
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

