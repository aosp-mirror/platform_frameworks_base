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

/** @file rs_cl.rsh
 *  \brief Additional compute routines
 *
 *
 */

#ifndef __RS_CL_RSH__
#define __RS_CL_RSH__

// Conversions
#define CVT_FUNC_2(typeout, typein)                             \
_RS_RUNTIME typeout##2 __attribute__((overloadable))             \
        convert_##typeout##2(typein##2 v);                      \
_RS_RUNTIME typeout##3 __attribute__((overloadable))             \
        convert_##typeout##3(typein##3 v);                      \
_RS_RUNTIME typeout##4 __attribute__((overloadable))             \
        convert_##typeout##4(typein##4 v);


#define CVT_FUNC(type)  CVT_FUNC_2(type, uchar)     \
                        CVT_FUNC_2(type, char)      \
                        CVT_FUNC_2(type, ushort)    \
                        CVT_FUNC_2(type, short)     \
                        CVT_FUNC_2(type, uint)      \
                        CVT_FUNC_2(type, int)       \
                        CVT_FUNC_2(type, float)

CVT_FUNC(char)
CVT_FUNC(uchar)
CVT_FUNC(short)
CVT_FUNC(ushort)
CVT_FUNC(int)
CVT_FUNC(uint)
CVT_FUNC(float)

// Float ops, 6.11.2

#define FN_FUNC_FN(fnc)                                         \
_RS_RUNTIME float2 __attribute__((overloadable)) fnc(float2 v);  \
_RS_RUNTIME float3 __attribute__((overloadable)) fnc(float3 v);  \
_RS_RUNTIME float4 __attribute__((overloadable)) fnc(float4 v);

#define IN_FUNC_FN(fnc)                                         \
_RS_RUNTIME int2 __attribute__((overloadable)) fnc(float2 v);    \
_RS_RUNTIME int3 __attribute__((overloadable)) fnc(float3 v);    \
_RS_RUNTIME int4 __attribute__((overloadable)) fnc(float4 v);

#define FN_FUNC_FN_FN(fnc)                                                  \
_RS_RUNTIME float2 __attribute__((overloadable)) fnc(float2 v1, float2 v2);  \
_RS_RUNTIME float3 __attribute__((overloadable)) fnc(float3 v1, float3 v2);  \
_RS_RUNTIME float4 __attribute__((overloadable)) fnc(float4 v1, float4 v2);

#define FN_FUNC_FN_F(fnc)                                                   \
_RS_RUNTIME float2 __attribute__((overloadable)) fnc(float2 v1, float v2);   \
_RS_RUNTIME float3 __attribute__((overloadable)) fnc(float3 v1, float v2);   \
_RS_RUNTIME float4 __attribute__((overloadable)) fnc(float4 v1, float v2);

#define FN_FUNC_FN_IN(fnc)                                                  \
_RS_RUNTIME float2 __attribute__((overloadable)) fnc(float2 v1, int2 v2);    \
_RS_RUNTIME float3 __attribute__((overloadable)) fnc(float3 v1, int3 v2);    \
_RS_RUNTIME float4 __attribute__((overloadable)) fnc(float4 v1, int4 v2);    \

#define FN_FUNC_FN_I(fnc)                                                   \
_RS_RUNTIME float2 __attribute__((overloadable)) fnc(float2 v1, int v2);     \
_RS_RUNTIME float3 __attribute__((overloadable)) fnc(float3 v1, int v2);     \
_RS_RUNTIME float4 __attribute__((overloadable)) fnc(float4 v1, int v2);

#define FN_FUNC_FN_PFN(fnc)                     \
_RS_RUNTIME float2 __attribute__((overloadable)) \
        fnc(float2 v1, float2 *v2);             \
_RS_RUNTIME float3 __attribute__((overloadable)) \
        fnc(float3 v1, float3 *v2);             \
_RS_RUNTIME float4 __attribute__((overloadable)) \
        fnc(float4 v1, float4 *v2);

#define FN_FUNC_FN_PIN(fnc)                                                 \
_RS_RUNTIME float2 __attribute__((overloadable)) fnc(float2 v1, int2 *v2);   \
_RS_RUNTIME float3 __attribute__((overloadable)) fnc(float3 v1, int3 *v2);   \
_RS_RUNTIME float4 __attribute__((overloadable)) fnc(float4 v1, int4 *v2);

#define FN_FUNC_FN_FN_FN(fnc)                   \
_RS_RUNTIME float2 __attribute__((overloadable)) \
        fnc(float2 v1, float2 v2, float2 v3);   \
_RS_RUNTIME float3 __attribute__((overloadable)) \
        fnc(float3 v1, float3 v2, float3 v3);   \
_RS_RUNTIME float4 __attribute__((overloadable)) \
        fnc(float4 v1, float4 v2, float4 v3);

#define FN_FUNC_FN_FN_PIN(fnc)                  \
_RS_RUNTIME float2 __attribute__((overloadable)) \
        fnc(float2 v1, float2 v2, int2 *v3);    \
_RS_RUNTIME float3 __attribute__((overloadable)) \
        fnc(float3 v1, float3 v2, int3 *v3);    \
_RS_RUNTIME float4 __attribute__((overloadable)) \
        fnc(float4 v1, float4 v2, int4 *v3);


extern float __attribute__((overloadable)) acos(float);
FN_FUNC_FN(acos)

extern float __attribute__((overloadable)) acosh(float);
FN_FUNC_FN(acosh)

_RS_RUNTIME float __attribute__((overloadable)) acospi(float v);


FN_FUNC_FN(acospi)

extern float __attribute__((overloadable)) asin(float);
FN_FUNC_FN(asin)

extern float __attribute__((overloadable)) asinh(float);
FN_FUNC_FN(asinh)


_RS_RUNTIME float __attribute__((overloadable)) asinpi(float v);
FN_FUNC_FN(asinpi)

extern float __attribute__((overloadable)) atan(float);
FN_FUNC_FN(atan)

extern float __attribute__((overloadable)) atan2(float, float);
FN_FUNC_FN_FN(atan2)

extern float __attribute__((overloadable)) atanh(float);
FN_FUNC_FN(atanh)


_RS_RUNTIME float __attribute__((overloadable)) atanpi(float v);
FN_FUNC_FN(atanpi)


_RS_RUNTIME float __attribute__((overloadable)) atan2pi(float y, float x);
FN_FUNC_FN_FN(atan2pi)

extern float __attribute__((overloadable)) cbrt(float);
FN_FUNC_FN(cbrt)

extern float __attribute__((overloadable)) ceil(float);
FN_FUNC_FN(ceil)

extern float __attribute__((overloadable)) copysign(float, float);
FN_FUNC_FN_FN(copysign)

extern float __attribute__((overloadable)) cos(float);
FN_FUNC_FN(cos)

extern float __attribute__((overloadable)) cosh(float);
FN_FUNC_FN(cosh)


_RS_RUNTIME float __attribute__((overloadable)) cospi(float v);
FN_FUNC_FN(cospi)

extern float __attribute__((overloadable)) erfc(float);
FN_FUNC_FN(erfc)

extern float __attribute__((overloadable)) erf(float);
FN_FUNC_FN(erf)

extern float __attribute__((overloadable)) exp(float);
FN_FUNC_FN(exp)

extern float __attribute__((overloadable)) exp2(float);
FN_FUNC_FN(exp2)

extern float __attribute__((overloadable)) pow(float, float);

_RS_RUNTIME float __attribute__((overloadable)) exp10(float v);
FN_FUNC_FN(exp10)

extern float __attribute__((overloadable)) expm1(float);
FN_FUNC_FN(expm1)

extern float __attribute__((overloadable)) fabs(float);
FN_FUNC_FN(fabs)

extern float __attribute__((overloadable)) fdim(float, float);
FN_FUNC_FN_FN(fdim)

extern float __attribute__((overloadable)) floor(float);
FN_FUNC_FN(floor)

extern float __attribute__((overloadable)) fma(float, float, float);
FN_FUNC_FN_FN_FN(fma)

extern float __attribute__((overloadable)) fmax(float, float);
FN_FUNC_FN_FN(fmax);
FN_FUNC_FN_F(fmax);

extern float __attribute__((overloadable)) fmin(float, float);
FN_FUNC_FN_FN(fmin);
FN_FUNC_FN_F(fmin);

extern float __attribute__((overloadable)) fmod(float, float);
FN_FUNC_FN_FN(fmod)


_RS_RUNTIME float __attribute__((overloadable)) fract(float v, float *iptr);
FN_FUNC_FN_PFN(fract)

extern float __attribute__((overloadable)) frexp(float, int *);
FN_FUNC_FN_PIN(frexp)

extern float __attribute__((overloadable)) hypot(float, float);
FN_FUNC_FN_FN(hypot)

extern int __attribute__((overloadable)) ilogb(float);
IN_FUNC_FN(ilogb)

extern float __attribute__((overloadable)) ldexp(float, int);
FN_FUNC_FN_IN(ldexp)
FN_FUNC_FN_I(ldexp)

extern float __attribute__((overloadable)) lgamma(float);
FN_FUNC_FN(lgamma)
extern float __attribute__((overloadable)) lgamma(float, int*);
FN_FUNC_FN_PIN(lgamma)

extern float __attribute__((overloadable)) log(float);
FN_FUNC_FN(log)


extern float __attribute__((overloadable)) log10(float);
FN_FUNC_FN(log10)


_RS_RUNTIME float __attribute__((overloadable)) log2(float v);
FN_FUNC_FN(log2)

extern float __attribute__((overloadable)) log1p(float);
FN_FUNC_FN(log1p)

extern float __attribute__((overloadable)) logb(float);
FN_FUNC_FN(logb)

extern float __attribute__((overloadable)) mad(float, float, float);
FN_FUNC_FN_FN_FN(mad)

extern float __attribute__((overloadable)) modf(float, float *);
FN_FUNC_FN_PFN(modf);

//extern float __attribute__((overloadable)) nan(uint);

extern float __attribute__((overloadable)) nextafter(float, float);
FN_FUNC_FN_FN(nextafter)

FN_FUNC_FN_FN(pow)

_RS_RUNTIME float __attribute__((overloadable)) pown(float v, int p);
_RS_RUNTIME float2 __attribute__((overloadable)) pown(float2 v, int2 p);
_RS_RUNTIME float3 __attribute__((overloadable)) pown(float3 v, int3 p);
_RS_RUNTIME float4 __attribute__((overloadable)) pown(float4 v, int4 p);

_RS_RUNTIME float __attribute__((overloadable)) powr(float v, float p);
_RS_RUNTIME float2 __attribute__((overloadable)) powr(float2 v, float2 p);
_RS_RUNTIME float3 __attribute__((overloadable)) powr(float3 v, float3 p);
_RS_RUNTIME float4 __attribute__((overloadable)) powr(float4 v, float4 p);

extern float __attribute__((overloadable)) remainder(float, float);
FN_FUNC_FN_FN(remainder)

extern float __attribute__((overloadable)) remquo(float, float, int *);
FN_FUNC_FN_FN_PIN(remquo)

extern float __attribute__((overloadable)) rint(float);
FN_FUNC_FN(rint)


_RS_RUNTIME float __attribute__((overloadable)) rootn(float v, int r);
_RS_RUNTIME float2 __attribute__((overloadable)) rootn(float2 v, int2 r);
_RS_RUNTIME float3 __attribute__((overloadable)) rootn(float3 v, int3 r);
_RS_RUNTIME float4 __attribute__((overloadable)) rootn(float4 v, int4 r);


extern float __attribute__((overloadable)) round(float);
FN_FUNC_FN(round)


extern float __attribute__((overloadable)) sqrt(float);
_RS_RUNTIME float __attribute__((overloadable)) rsqrt(float v);
FN_FUNC_FN(rsqrt)

extern float __attribute__((overloadable)) sin(float);
FN_FUNC_FN(sin)

_RS_RUNTIME float __attribute__((overloadable)) sincos(float v, float *cosptr);
_RS_RUNTIME float2 __attribute__((overloadable)) sincos(float2 v, float2 *cosptr);
_RS_RUNTIME float3 __attribute__((overloadable)) sincos(float3 v, float3 *cosptr);
_RS_RUNTIME float4 __attribute__((overloadable)) sincos(float4 v, float4 *cosptr);

extern float __attribute__((overloadable)) sinh(float);
FN_FUNC_FN(sinh)

_RS_RUNTIME float __attribute__((overloadable)) sinpi(float v);
FN_FUNC_FN(sinpi)

FN_FUNC_FN(sqrt)

extern float __attribute__((overloadable)) tan(float);
FN_FUNC_FN(tan)

extern float __attribute__((overloadable)) tanh(float);
FN_FUNC_FN(tanh)

_RS_RUNTIME float __attribute__((overloadable)) tanpi(float v);
FN_FUNC_FN(tanpi)


extern float __attribute__((overloadable)) tgamma(float);
FN_FUNC_FN(tgamma)

extern float __attribute__((overloadable)) trunc(float);
FN_FUNC_FN(trunc)

// Int ops (partial), 6.11.3

#define XN_FUNC_YN(typeout, fnc, typein)                                \
extern typeout __attribute__((overloadable)) fnc(typein);               \
_RS_RUNTIME typeout##2 __attribute__((overloadable)) fnc(typein##2 v);   \
_RS_RUNTIME typeout##3 __attribute__((overloadable)) fnc(typein##3 v);   \
_RS_RUNTIME typeout##4 __attribute__((overloadable)) fnc(typein##4 v);

#define UIN_FUNC_IN(fnc)          \
XN_FUNC_YN(uchar, fnc, char)      \
XN_FUNC_YN(ushort, fnc, short)    \
XN_FUNC_YN(uint, fnc, int)

#define IN_FUNC_IN(fnc)           \
XN_FUNC_YN(uchar, fnc, uchar)     \
XN_FUNC_YN(char, fnc, char)       \
XN_FUNC_YN(ushort, fnc, ushort)   \
XN_FUNC_YN(short, fnc, short)     \
XN_FUNC_YN(uint, fnc, uint)       \
XN_FUNC_YN(int, fnc, int)


#define XN_FUNC_XN_XN_BODY(type, fnc, body)         \
_RS_RUNTIME type __attribute__((overloadable))       \
        fnc(type v1, type v2);                      \
_RS_RUNTIME type##2 __attribute__((overloadable))    \
        fnc(type##2 v1, type##2 v2);                \
_RS_RUNTIME type##3 __attribute__((overloadable))    \
        fnc(type##3 v1, type##3 v2);                \
_RS_RUNTIME type##4 __attribute__((overloadable))    \
        fnc(type##4 v1, type##4 v2);

#define IN_FUNC_IN_IN_BODY(fnc, body) \
XN_FUNC_XN_XN_BODY(uchar, fnc, body)  \
XN_FUNC_XN_XN_BODY(char, fnc, body)   \
XN_FUNC_XN_XN_BODY(ushort, fnc, body) \
XN_FUNC_XN_XN_BODY(short, fnc, body)  \
XN_FUNC_XN_XN_BODY(uint, fnc, body)   \
XN_FUNC_XN_XN_BODY(int, fnc, body)    \
XN_FUNC_XN_XN_BODY(float, fnc, body)

UIN_FUNC_IN(abs)
IN_FUNC_IN(clz)

IN_FUNC_IN_IN_BODY(min, (v1 < v2 ? v1 : v2))
FN_FUNC_FN_F(min)

IN_FUNC_IN_IN_BODY(max, (v1 > v2 ? v1 : v2))
FN_FUNC_FN_F(max)

// 6.11.4

_RS_RUNTIME float __attribute__((overloadable)) clamp(float amount, float low, float high);
_RS_RUNTIME float2 __attribute__((overloadable)) clamp(float2 amount, float2 low, float2 high);
_RS_RUNTIME float3 __attribute__((overloadable)) clamp(float3 amount, float3 low, float3 high);
_RS_RUNTIME float4 __attribute__((overloadable)) clamp(float4 amount, float4 low, float4 high);
_RS_RUNTIME float2 __attribute__((overloadable)) clamp(float2 amount, float low, float high);
_RS_RUNTIME float3 __attribute__((overloadable)) clamp(float3 amount, float low, float high);
_RS_RUNTIME float4 __attribute__((overloadable)) clamp(float4 amount, float low, float high);

_RS_RUNTIME float __attribute__((overloadable)) degrees(float radians);
FN_FUNC_FN(degrees)

_RS_RUNTIME float __attribute__((overloadable)) mix(float start, float stop, float amount);
_RS_RUNTIME float2 __attribute__((overloadable)) mix(float2 start, float2 stop, float2 amount);
_RS_RUNTIME float3 __attribute__((overloadable)) mix(float3 start, float3 stop, float3 amount);
_RS_RUNTIME float4 __attribute__((overloadable)) mix(float4 start, float4 stop, float4 amount);
_RS_RUNTIME float2 __attribute__((overloadable)) mix(float2 start, float2 stop, float amount);
_RS_RUNTIME float3 __attribute__((overloadable)) mix(float3 start, float3 stop, float amount);
_RS_RUNTIME float4 __attribute__((overloadable)) mix(float4 start, float4 stop, float amount);

_RS_RUNTIME float __attribute__((overloadable)) radians(float degrees);
FN_FUNC_FN(radians)

_RS_RUNTIME float __attribute__((overloadable)) step(float edge, float v);
_RS_RUNTIME float2 __attribute__((overloadable)) step(float2 edge, float2 v);
_RS_RUNTIME float3 __attribute__((overloadable)) step(float3 edge, float3 v);
_RS_RUNTIME float4 __attribute__((overloadable)) step(float4 edge, float4 v);
_RS_RUNTIME float2 __attribute__((overloadable)) step(float2 edge, float v);
_RS_RUNTIME float3 __attribute__((overloadable)) step(float3 edge, float v);
_RS_RUNTIME float4 __attribute__((overloadable)) step(float4 edge, float v);

extern float __attribute__((overloadable)) smoothstep(float, float, float);
extern float2 __attribute__((overloadable)) smoothstep(float2, float2, float2);
extern float3 __attribute__((overloadable)) smoothstep(float3, float3, float3);
extern float4 __attribute__((overloadable)) smoothstep(float4, float4, float4);
extern float2 __attribute__((overloadable)) smoothstep(float, float, float2);
extern float3 __attribute__((overloadable)) smoothstep(float, float, float3);
extern float4 __attribute__((overloadable)) smoothstep(float, float, float4);

_RS_RUNTIME float __attribute__((overloadable)) sign(float v);
FN_FUNC_FN(sign)

// 6.11.5
_RS_RUNTIME float3 __attribute__((overloadable)) cross(float3 lhs, float3 rhs);

_RS_RUNTIME float4 __attribute__((overloadable)) cross(float4 lhs, float4 rhs);

_RS_RUNTIME float __attribute__((overloadable)) dot(float lhs, float rhs);
_RS_RUNTIME float __attribute__((overloadable)) dot(float2 lhs, float2 rhs);
_RS_RUNTIME float __attribute__((overloadable)) dot(float3 lhs, float3 rhs);
_RS_RUNTIME float __attribute__((overloadable)) dot(float4 lhs, float4 rhs);

_RS_RUNTIME float __attribute__((overloadable)) length(float v);
_RS_RUNTIME float __attribute__((overloadable)) length(float2 v);
_RS_RUNTIME float __attribute__((overloadable)) length(float3 v);
_RS_RUNTIME float __attribute__((overloadable)) length(float4 v);

_RS_RUNTIME float __attribute__((overloadable)) distance(float lhs, float rhs);
_RS_RUNTIME float __attribute__((overloadable)) distance(float2 lhs, float2 rhs);
_RS_RUNTIME float __attribute__((overloadable)) distance(float3 lhs, float3 rhs);
_RS_RUNTIME float __attribute__((overloadable)) distance(float4 lhs, float4 rhs);

_RS_RUNTIME float __attribute__((overloadable)) normalize(float v);
_RS_RUNTIME float2 __attribute__((overloadable)) normalize(float2 v);
_RS_RUNTIME float3 __attribute__((overloadable)) normalize(float3 v);
_RS_RUNTIME float4 __attribute__((overloadable)) normalize(float4 v);

#undef CVT_FUNC
#undef CVT_FUNC_2
#undef FN_FUNC_FN
#undef IN_FUNC_FN
#undef FN_FUNC_FN_FN
#undef FN_FUNC_FN_F
#undef FN_FUNC_FN_IN
#undef FN_FUNC_FN_I
#undef FN_FUNC_FN_PFN
#undef FN_FUNC_FN_PIN
#undef FN_FUNC_FN_FN_FN
#undef FN_FUNC_FN_FN_PIN
#undef XN_FUNC_YN
#undef UIN_FUNC_IN
#undef IN_FUNC_IN
#undef XN_FUNC_XN_XN_BODY
#undef IN_FUNC_IN_IN_BODY

#endif
