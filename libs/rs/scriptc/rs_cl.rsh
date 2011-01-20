#ifndef __RS_CL_RSH__
#define __RS_CL_RSH__

#ifdef BCC_PREPARE_BC
#define _RS_STATIC  extern
#else
#define _RS_STATIC  static
#endif

// Conversions
#define CVT_FUNC_2(typeout, typein)                             \
_RS_STATIC typeout##2 __attribute__((overloadable))             \
        convert_##typeout##2(typein##2 v) {                     \
    typeout##2 r = {(typeout)v.x, (typeout)v.y};                \
    return r;                                                   \
}                                                               \
_RS_STATIC typeout##3 __attribute__((overloadable))             \
        convert_##typeout##3(typein##3 v) {                     \
    typeout##3 r = {(typeout)v.x, (typeout)v.y, (typeout)v.z};  \
    return r;                                                   \
}                                                               \
_RS_STATIC typeout##4 __attribute__((overloadable))             \
        convert_##typeout##4(typein##4 v) {                     \
    typeout##4 r = {(typeout)v.x, (typeout)v.y, (typeout)v.z,   \
                    (typeout)v.w};                              \
    return r;                                                   \
}

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
_RS_STATIC float2 __attribute__((overloadable)) fnc(float2 v) { \
    float2 r;                                                   \
    r.x = fnc(v.x);                                             \
    r.y = fnc(v.y);                                             \
    return r;                                                   \
}                                                               \
_RS_STATIC float3 __attribute__((overloadable)) fnc(float3 v) { \
    float3 r;                                                   \
    r.x = fnc(v.x);                                             \
    r.y = fnc(v.y);                                             \
    r.z = fnc(v.z);                                             \
    return r;                                                   \
}                                                               \
_RS_STATIC float4 __attribute__((overloadable)) fnc(float4 v) { \
    float4 r;                                                   \
    r.x = fnc(v.x);                                             \
    r.y = fnc(v.y);                                             \
    r.z = fnc(v.z);                                             \
    r.w = fnc(v.w);                                             \
    return r;                                                   \
}

#define IN_FUNC_FN(fnc)                                         \
_RS_STATIC int2 __attribute__((overloadable)) fnc(float2 v) {   \
    int2 r;                                                     \
    r.x = fnc(v.x);                                             \
    r.y = fnc(v.y);                                             \
    return r;                                                   \
}                                                               \
_RS_STATIC int3 __attribute__((overloadable)) fnc(float3 v) {   \
    int3 r;                                                     \
    r.x = fnc(v.x);                                             \
    r.y = fnc(v.y);                                             \
    r.z = fnc(v.z);                                             \
    return r;                                                   \
}                                                               \
_RS_STATIC int4 __attribute__((overloadable)) fnc(float4 v) {   \
    int4 r;                                                     \
    r.x = fnc(v.x);                                             \
    r.y = fnc(v.y);                                             \
    r.z = fnc(v.z);                                             \
    r.w = fnc(v.w);                                             \
    return r;                                                   \
}

#define FN_FUNC_FN_FN(fnc)                                                  \
_RS_STATIC float2 __attribute__((overloadable)) fnc(float2 v1, float2 v2) { \
    float2 r;                                                               \
    r.x = fnc(v1.x, v2.x);                                                  \
    r.y = fnc(v1.y, v2.y);                                                  \
    return r;                                                               \
}                                                                           \
_RS_STATIC float3 __attribute__((overloadable)) fnc(float3 v1, float3 v2) { \
    float3 r;                                                               \
    r.x = fnc(v1.x, v2.x);                                                  \
    r.y = fnc(v1.y, v2.y);                                                  \
    r.z = fnc(v1.z, v2.z);                                                  \
    return r;                                                               \
}                                                                           \
_RS_STATIC float4 __attribute__((overloadable)) fnc(float4 v1, float4 v2) { \
    float4 r;                                                               \
    r.x = fnc(v1.x, v2.x);                                                  \
    r.y = fnc(v1.y, v2.y);                                                  \
    r.z = fnc(v1.z, v2.z);                                                  \
    r.w = fnc(v1.w, v2.w);                                                  \
    return r;                                                               \
}

#define FN_FUNC_FN_F(fnc)                                                   \
_RS_STATIC float2 __attribute__((overloadable)) fnc(float2 v1, float v2) {  \
    float2 r;                                                               \
    r.x = fnc(v1.x, v2);                                                    \
    r.y = fnc(v1.y, v2);                                                    \
    return r;                                                               \
}                                                                           \
_RS_STATIC float3 __attribute__((overloadable)) fnc(float3 v1, float v2) {  \
    float3 r;                                                               \
    r.x = fnc(v1.x, v2);                                                    \
    r.y = fnc(v1.y, v2);                                                    \
    r.z = fnc(v1.z, v2);                                                    \
    return r;                                                               \
}                                                                           \
_RS_STATIC float4 __attribute__((overloadable)) fnc(float4 v1, float v2) {  \
    float4 r;                                                               \
    r.x = fnc(v1.x, v2);                                                    \
    r.y = fnc(v1.y, v2);                                                    \
    r.z = fnc(v1.z, v2);                                                    \
    r.w = fnc(v1.w, v2);                                                    \
    return r;                                                               \
}

#define FN_FUNC_FN_IN(fnc)                                                  \
_RS_STATIC float2 __attribute__((overloadable)) fnc(float2 v1, int2 v2) {   \
    float2 r;                                                               \
    r.x = fnc(v1.x, v2.x);                                                  \
    r.y = fnc(v1.y, v2.y);                                                  \
    return r;                                                               \
}                                                                           \
_RS_STATIC float3 __attribute__((overloadable)) fnc(float3 v1, int3 v2) {   \
    float3 r;                                                               \
    r.x = fnc(v1.x, v2.x);                                                  \
    r.y = fnc(v1.y, v2.y);                                                  \
    r.z = fnc(v1.z, v2.z);                                                  \
    return r;                                                               \
}                                                                           \
_RS_STATIC float4 __attribute__((overloadable)) fnc(float4 v1, int4 v2) {   \
    float4 r;                                                               \
    r.x = fnc(v1.x, v2.x);                                                  \
    r.y = fnc(v1.y, v2.y);                                                  \
    r.z = fnc(v1.z, v2.z);                                                  \
    r.w = fnc(v1.w, v2.w);                                                  \
    return r;                                                               \
}

#define FN_FUNC_FN_I(fnc)                                                   \
_RS_STATIC float2 __attribute__((overloadable)) fnc(float2 v1, int v2) {    \
    float2 r;                                                               \
    r.x = fnc(v1.x, v2);                                                    \
    r.y = fnc(v1.y, v2);                                                    \
    return r;                                                               \
}                                                                           \
_RS_STATIC float3 __attribute__((overloadable)) fnc(float3 v1, int v2) {    \
    float3 r;                                                               \
    r.x = fnc(v1.x, v2);                                                    \
    r.y = fnc(v1.y, v2);                                                    \
    r.z = fnc(v1.z, v2);                                                    \
    return r;                                                               \
}                                                                           \
_RS_STATIC float4 __attribute__((overloadable)) fnc(float4 v1, int v2) {    \
    float4 r;                                                               \
    r.x = fnc(v1.x, v2);                                                    \
    r.y = fnc(v1.y, v2);                                                    \
    r.z = fnc(v1.z, v2);                                                    \
    r.w = fnc(v1.w, v2);                                                    \
    return r;                                                               \
}

#define FN_FUNC_FN_PFN(fnc)                     \
_RS_STATIC float2 __attribute__((overloadable)) \
        fnc(float2 v1, float2 *v2) {            \
    float2 r;                                   \
    float t[2];                                 \
    r.x = fnc(v1.x, &t[0]);                     \
    r.y = fnc(v1.y, &t[1]);                     \
    v2->x = t[0];                               \
    v2->y = t[1];                               \
    return r;                                   \
}                                               \
_RS_STATIC float3 __attribute__((overloadable)) \
        fnc(float3 v1, float3 *v2) {            \
    float3 r;                                   \
    float t[3];                                 \
    r.x = fnc(v1.x, &t[0]);                     \
    r.y = fnc(v1.y, &t[1]);                     \
    r.z = fnc(v1.z, &t[2]);                     \
    v2->x = t[0];                               \
    v2->y = t[1];                               \
    v2->z = t[2];                               \
    return r;                                   \
}                                               \
_RS_STATIC float4 __attribute__((overloadable)) \
        fnc(float4 v1, float4 *v2) {            \
    float4 r;                                   \
    float t[4];                                 \
    r.x = fnc(v1.x, &t[0]);                     \
    r.y = fnc(v1.y, &t[1]);                     \
    r.z = fnc(v1.z, &t[2]);                     \
    r.w = fnc(v1.w, &t[3]);                     \
    v2->x = t[0];                               \
    v2->y = t[1];                               \
    v2->z = t[2];                               \
    v2->w = t[3];                               \
    return r;                                   \
}

#define FN_FUNC_FN_PIN(fnc)                                                 \
_RS_STATIC float2 __attribute__((overloadable)) fnc(float2 v1, int2 *v2) {  \
    float2 r;                                                               \
    int t[2];                                                               \
    r.x = fnc(v1.x, &t[0]);                                                 \
    r.y = fnc(v1.y, &t[1]);                                                 \
    v2->x = t[0];                                                           \
    v2->y = t[1];                                                           \
    return r;                                                               \
}                                                                           \
_RS_STATIC float3 __attribute__((overloadable)) fnc(float3 v1, int3 *v2) {  \
    float3 r;                                                               \
    int t[3];                                                               \
    r.x = fnc(v1.x, &t[0]);                                                 \
    r.y = fnc(v1.y, &t[1]);                                                 \
    r.z = fnc(v1.z, &t[2]);                                                 \
    v2->x = t[0];                                                           \
    v2->y = t[1];                                                           \
    v2->z = t[2];                                                           \
    return r;                                                               \
}                                                                           \
_RS_STATIC float4 __attribute__((overloadable)) fnc(float4 v1, int4 *v2) {  \
    float4 r;                                                               \
    int t[4];                                                               \
    r.x = fnc(v1.x, &t[0]);                                                 \
    r.y = fnc(v1.y, &t[1]);                                                 \
    r.z = fnc(v1.z, &t[2]);                                                 \
    r.w = fnc(v1.w, &t[3]);                                                 \
    v2->x = t[0];                                                           \
    v2->y = t[1];                                                           \
    v2->z = t[2];                                                           \
    v2->w = t[3];                                                           \
    return r;                                                               \
}

#define FN_FUNC_FN_FN_FN(fnc)                   \
_RS_STATIC float2 __attribute__((overloadable)) \
        fnc(float2 v1, float2 v2, float2 v3) {  \
    float2 r;                                   \
    r.x = fnc(v1.x, v2.x, v3.x);                \
    r.y = fnc(v1.y, v2.y, v3.y);                \
    return r;                                   \
}                                               \
_RS_STATIC float3 __attribute__((overloadable)) \
        fnc(float3 v1, float3 v2, float3 v3) {  \
    float3 r;                                   \
    r.x = fnc(v1.x, v2.x, v3.x);                \
    r.y = fnc(v1.y, v2.y, v3.y);                \
    r.z = fnc(v1.z, v2.z, v3.z);                \
    return r;                                   \
}                                               \
_RS_STATIC float4 __attribute__((overloadable)) \
        fnc(float4 v1, float4 v2, float4 v3) {  \
    float4 r;                                   \
    r.x = fnc(v1.x, v2.x, v3.x);                \
    r.y = fnc(v1.y, v2.y, v3.y);                \
    r.z = fnc(v1.z, v2.z, v3.z);                \
    r.w = fnc(v1.w, v2.w, v3.w);                \
    return r;                                   \
}

#define FN_FUNC_FN_FN_PIN(fnc)                  \
_RS_STATIC float2 __attribute__((overloadable)) \
        fnc(float2 v1, float2 v2, int2 *v3) {   \
    float2 r;                                   \
    int t[2];                                   \
    r.x = fnc(v1.x, v2.x, &t[0]);               \
    r.y = fnc(v1.y, v2.y, &t[1]);               \
    v3->x = t[0];                               \
    v3->y = t[1];                               \
    return r;                                   \
}                                               \
_RS_STATIC float3 __attribute__((overloadable)) \
        fnc(float3 v1, float3 v2, int3 *v3) {   \
    float3 r;                                   \
    int t[3];                                   \
    r.x = fnc(v1.x, v2.x, &t[0]);               \
    r.y = fnc(v1.y, v2.y, &t[1]);               \
    r.z = fnc(v1.z, v2.z, &t[2]);               \
    v3->x = t[0];                               \
    v3->y = t[1];                               \
    v3->z = t[2];                               \
    return r;                                   \
}                                               \
_RS_STATIC float4 __attribute__((overloadable)) \
        fnc(float4 v1, float4 v2, int4 *v3) {   \
    float4 r;                                   \
    int t[4];                                   \
    r.x = fnc(v1.x, v2.x, &t[0]);               \
    r.y = fnc(v1.y, v2.y, &t[1]);               \
    r.z = fnc(v1.z, v2.z, &t[2]);               \
    r.w = fnc(v1.w, v2.w, &t[3]);               \
    v3->x = t[0];                               \
    v3->y = t[1];                               \
    v3->z = t[2];                               \
    v3->w = t[3];                               \
    return r;                                   \
}


extern float __attribute__((overloadable)) acos(float);
FN_FUNC_FN(acos)

extern float __attribute__((overloadable)) acosh(float);
FN_FUNC_FN(acosh)

_RS_STATIC float __attribute__((overloadable)) acospi(float v) {
    return acos(v) / M_PI;
}
FN_FUNC_FN(acospi)

extern float __attribute__((overloadable)) asin(float);
FN_FUNC_FN(asin)

extern float __attribute__((overloadable)) asinh(float);
FN_FUNC_FN(asinh)

_RS_STATIC float __attribute__((overloadable)) asinpi(float v) {
    return asin(v) / M_PI;
}
FN_FUNC_FN(asinpi)

extern float __attribute__((overloadable)) atan(float);
FN_FUNC_FN(atan)

extern float __attribute__((overloadable)) atan2(float, float);
FN_FUNC_FN_FN(atan2)

extern float __attribute__((overloadable)) atanh(float);
FN_FUNC_FN(atanh)

_RS_STATIC float __attribute__((overloadable)) atanpi(float v) {
    return atan(v) / M_PI;
}
FN_FUNC_FN(atanpi)

_RS_STATIC float __attribute__((overloadable)) atan2pi(float y, float x) {
    return atan2(y, x) / M_PI;
}
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

_RS_STATIC float __attribute__((overloadable)) cospi(float v) {
    return cos(v * M_PI);
}
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
_RS_STATIC float __attribute__((overloadable)) exp10(float v) {
    return pow(10.f, v);
}
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

_RS_STATIC float __attribute__((overloadable)) fract(float v, float *iptr) {
    int i = (int)floor(v);
    iptr[0] = i;
    return fmin(v - i, 0x1.fffffep-1f);
}
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

_RS_STATIC float __attribute__((overloadable)) log2(float v) {
    return log10(v) / log10(2.f);
}
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

_RS_STATIC float __attribute__((overloadable)) pown(float v, int p) {
    return pow(v, (float)p);
}
_RS_STATIC float2 __attribute__((overloadable)) pown(float2 v, int2 p) {
    return pow(v, (float2)p);
}
_RS_STATIC float3 __attribute__((overloadable)) pown(float3 v, int3 p) {
    return pow(v, (float3)p);
}
_RS_STATIC float4 __attribute__((overloadable)) pown(float4 v, int4 p) {
    return pow(v, (float4)p);
}

_RS_STATIC float __attribute__((overloadable)) powr(float v, float p) {
    return pow(v, p);
}
_RS_STATIC float2 __attribute__((overloadable)) powr(float2 v, float2 p) {
    return pow(v, p);
}
_RS_STATIC float3 __attribute__((overloadable)) powr(float3 v, float3 p) {
    return pow(v, p);
}
_RS_STATIC float4 __attribute__((overloadable)) powr(float4 v, float4 p) {
    return pow(v, p);
}

extern float __attribute__((overloadable)) remainder(float, float);
FN_FUNC_FN_FN(remainder)

extern float __attribute__((overloadable)) remquo(float, float, int *);
FN_FUNC_FN_FN_PIN(remquo)

extern float __attribute__((overloadable)) rint(float);
FN_FUNC_FN(rint)

_RS_STATIC float __attribute__((overloadable)) rootn(float v, int r) {
    return pow(v, 1.f / r);
}
_RS_STATIC float2 __attribute__((overloadable)) rootn(float2 v, int2 r) {
    float2 t = {1.f / r.x, 1.f / r.y};
    return pow(v, t);
}
_RS_STATIC float3 __attribute__((overloadable)) rootn(float3 v, int3 r) {
    float3 t = {1.f / r.x, 1.f / r.y, 1.f / r.z};
    return pow(v, t);
}
_RS_STATIC float4 __attribute__((overloadable)) rootn(float4 v, int4 r) {
    float4 t = {1.f / r.x, 1.f / r.y, 1.f / r.z, 1.f / r.w};
    return pow(v, t);
}

extern float __attribute__((overloadable)) round(float);
FN_FUNC_FN(round)

extern float __attribute__((overloadable)) sqrt(float);
_RS_STATIC float __attribute__((overloadable)) rsqrt(float v) {
    return 1.f / sqrt(v);
}
FN_FUNC_FN(rsqrt)

extern float __attribute__((overloadable)) sin(float);
FN_FUNC_FN(sin)

_RS_STATIC float __attribute__((overloadable)) sincos(float v, float *cosptr) {
    *cosptr = cos(v);
    return sin(v);
}
_RS_STATIC float2 __attribute__((overloadable)) sincos(float2 v, float2 *cosptr) {
    *cosptr = cos(v);
    return sin(v);
}
_RS_STATIC float3 __attribute__((overloadable)) sincos(float3 v, float3 *cosptr) {
    *cosptr = cos(v);
    return sin(v);
}
_RS_STATIC float4 __attribute__((overloadable)) sincos(float4 v, float4 *cosptr) {
    *cosptr = cos(v);
    return sin(v);
}

extern float __attribute__((overloadable)) sinh(float);
FN_FUNC_FN(sinh)

_RS_STATIC float __attribute__((overloadable)) sinpi(float v) {
    return sin(v * M_PI);
}
FN_FUNC_FN(sinpi)

FN_FUNC_FN(sqrt)

extern float __attribute__((overloadable)) tan(float);
FN_FUNC_FN(tan)

extern float __attribute__((overloadable)) tanh(float);
FN_FUNC_FN(tanh)

_RS_STATIC float __attribute__((overloadable)) tanpi(float v) {
    return tan(v * M_PI);
}
FN_FUNC_FN(tanpi)

extern float __attribute__((overloadable)) tgamma(float);
FN_FUNC_FN(tgamma)

extern float __attribute__((overloadable)) trunc(float);
FN_FUNC_FN(trunc)

// Int ops (partial), 6.11.3

#define XN_FUNC_YN(typeout, fnc, typein)                                \
extern typeout __attribute__((overloadable)) fnc(typein);               \
_RS_STATIC typeout##2 __attribute__((overloadable)) fnc(typein##2 v) {  \
    typeout##2 r;                                                       \
    r.x = fnc(v.x);                                                     \
    r.y = fnc(v.y);                                                     \
    return r;                                                           \
}                                                                       \
_RS_STATIC typeout##3 __attribute__((overloadable)) fnc(typein##3 v) {  \
    typeout##3 r;                                                       \
    r.x = fnc(v.x);                                                     \
    r.y = fnc(v.y);                                                     \
    r.z = fnc(v.z);                                                     \
    return r;                                                           \
}                                                                       \
_RS_STATIC typeout##4 __attribute__((overloadable)) fnc(typein##4 v) {  \
    typeout##4 r;                                                       \
    r.x = fnc(v.x);                                                     \
    r.y = fnc(v.y);                                                     \
    r.z = fnc(v.z);                                                     \
    r.w = fnc(v.w);                                                     \
    return r;                                                           \
}

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
_RS_STATIC type __attribute__((overloadable))       \
        fnc(type v1, type v2) {                     \
    return body;                                    \
}                                                   \
_RS_STATIC type##2 __attribute__((overloadable))    \
        fnc(type##2 v1, type##2 v2) {               \
    type##2 r;                                      \
    r.x = fnc(v1.x, v2.x);                          \
    r.y = fnc(v1.y, v2.y);                          \
    return r;                                       \
}                                                   \
_RS_STATIC type##3 __attribute__((overloadable))    \
        fnc(type##3 v1, type##3 v2) {               \
    type##3 r;                                      \
    r.x = fnc(v1.x, v2.x);                          \
    r.y = fnc(v1.y, v2.y);                          \
    r.z = fnc(v1.z, v2.z);                          \
    return r;                                       \
}                                                   \
_RS_STATIC type##4 __attribute__((overloadable))    \
        fnc(type##4 v1, type##4 v2) {               \
    type##4 r;                                      \
    r.x = fnc(v1.x, v2.x);                          \
    r.y = fnc(v1.y, v2.y);                          \
    r.z = fnc(v1.z, v2.z);                          \
    r.w = fnc(v1.w, v2.w);                          \
    return r;                                       \
}

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

_RS_STATIC float __attribute__((overloadable)) clamp(float amount, float low, float high) {
    return amount < low ? low : (amount > high ? high : amount);
}
_RS_STATIC float2 __attribute__((overloadable)) clamp(float2 amount, float2 low, float2 high) {
    float2 r;
    r.x = amount.x < low.x ? low.x : (amount.x > high.x ? high.x : amount.x);
    r.y = amount.y < low.y ? low.y : (amount.y > high.y ? high.y : amount.y);
    return r;
}
_RS_STATIC float3 __attribute__((overloadable)) clamp(float3 amount, float3 low, float3 high) {
    float3 r;
    r.x = amount.x < low.x ? low.x : (amount.x > high.x ? high.x : amount.x);
    r.y = amount.y < low.y ? low.y : (amount.y > high.y ? high.y : amount.y);
    r.z = amount.z < low.z ? low.z : (amount.z > high.z ? high.z : amount.z);
    return r;
}
_RS_STATIC float4 __attribute__((overloadable)) clamp(float4 amount, float4 low, float4 high) {
    float4 r;
    r.x = amount.x < low.x ? low.x : (amount.x > high.x ? high.x : amount.x);
    r.y = amount.y < low.y ? low.y : (amount.y > high.y ? high.y : amount.y);
    r.z = amount.z < low.z ? low.z : (amount.z > high.z ? high.z : amount.z);
    r.w = amount.w < low.w ? low.w : (amount.w > high.w ? high.w : amount.w);
    return r;
}
_RS_STATIC float2 __attribute__((overloadable)) clamp(float2 amount, float low, float high) {
    float2 r;
    r.x = amount.x < low ? low : (amount.x > high ? high : amount.x);
    r.y = amount.y < low ? low : (amount.y > high ? high : amount.y);
    return r;
}
_RS_STATIC float3 __attribute__((overloadable)) clamp(float3 amount, float low, float high) {
    float3 r;
    r.x = amount.x < low ? low : (amount.x > high ? high : amount.x);
    r.y = amount.y < low ? low : (amount.y > high ? high : amount.y);
    r.z = amount.z < low ? low : (amount.z > high ? high : amount.z);
    return r;
}
_RS_STATIC float4 __attribute__((overloadable)) clamp(float4 amount, float low, float high) {
    float4 r;
    r.x = amount.x < low ? low : (amount.x > high ? high : amount.x);
    r.y = amount.y < low ? low : (amount.y > high ? high : amount.y);
    r.z = amount.z < low ? low : (amount.z > high ? high : amount.z);
    r.w = amount.w < low ? low : (amount.w > high ? high : amount.w);
    return r;
}

_RS_STATIC float __attribute__((overloadable)) degrees(float radians) {
    return radians * (180.f / M_PI);
}
FN_FUNC_FN(degrees)

_RS_STATIC float __attribute__((overloadable)) mix(float start, float stop, float amount) {
    return start + (stop - start) * amount;
}
_RS_STATIC float2 __attribute__((overloadable)) mix(float2 start, float2 stop, float2 amount) {
    return start + (stop - start) * amount;
}
_RS_STATIC float3 __attribute__((overloadable)) mix(float3 start, float3 stop, float3 amount) {
    return start + (stop - start) * amount;
}
_RS_STATIC float4 __attribute__((overloadable)) mix(float4 start, float4 stop, float4 amount) {
    return start + (stop - start) * amount;
}
_RS_STATIC float2 __attribute__((overloadable)) mix(float2 start, float2 stop, float amount) {
    return start + (stop - start) * amount;
}
_RS_STATIC float3 __attribute__((overloadable)) mix(float3 start, float3 stop, float amount) {
    return start + (stop - start) * amount;
}
_RS_STATIC float4 __attribute__((overloadable)) mix(float4 start, float4 stop, float amount) {
    return start + (stop - start) * amount;
}

_RS_STATIC float __attribute__((overloadable)) radians(float degrees) {
    return degrees * (M_PI / 180.f);
}
FN_FUNC_FN(radians)

_RS_STATIC float __attribute__((overloadable)) step(float edge, float v) {
    return (v < edge) ? 0.f : 1.f;
}
_RS_STATIC float2 __attribute__((overloadable)) step(float2 edge, float2 v) {
    float2 r;
    r.x = (v.x < edge.x) ? 0.f : 1.f;
    r.y = (v.y < edge.y) ? 0.f : 1.f;
    return r;
}
_RS_STATIC float3 __attribute__((overloadable)) step(float3 edge, float3 v) {
    float3 r;
    r.x = (v.x < edge.x) ? 0.f : 1.f;
    r.y = (v.y < edge.y) ? 0.f : 1.f;
    r.z = (v.z < edge.z) ? 0.f : 1.f;
    return r;
}
_RS_STATIC float4 __attribute__((overloadable)) step(float4 edge, float4 v) {
    float4 r;
    r.x = (v.x < edge.x) ? 0.f : 1.f;
    r.y = (v.y < edge.y) ? 0.f : 1.f;
    r.z = (v.z < edge.z) ? 0.f : 1.f;
    r.w = (v.w < edge.w) ? 0.f : 1.f;
    return r;
}
_RS_STATIC float2 __attribute__((overloadable)) step(float2 edge, float v) {
    float2 r;
    r.x = (v < edge.x) ? 0.f : 1.f;
    r.y = (v < edge.y) ? 0.f : 1.f;
    return r;
}
_RS_STATIC float3 __attribute__((overloadable)) step(float3 edge, float v) {
    float3 r;
    r.x = (v < edge.x) ? 0.f : 1.f;
    r.y = (v < edge.y) ? 0.f : 1.f;
    r.z = (v < edge.z) ? 0.f : 1.f;
    return r;
}
_RS_STATIC float4 __attribute__((overloadable)) step(float4 edge, float v) {
    float4 r;
    r.x = (v < edge.x) ? 0.f : 1.f;
    r.y = (v < edge.y) ? 0.f : 1.f;
    r.z = (v < edge.z) ? 0.f : 1.f;
    r.w = (v < edge.w) ? 0.f : 1.f;
    return r;
}

extern float __attribute__((overloadable)) smoothstep(float, float, float);
extern float2 __attribute__((overloadable)) smoothstep(float2, float2, float2);
extern float3 __attribute__((overloadable)) smoothstep(float3, float3, float3);
extern float4 __attribute__((overloadable)) smoothstep(float4, float4, float4);
extern float2 __attribute__((overloadable)) smoothstep(float, float, float2);
extern float3 __attribute__((overloadable)) smoothstep(float, float, float3);
extern float4 __attribute__((overloadable)) smoothstep(float, float, float4);

_RS_STATIC float __attribute__((overloadable)) sign(float v) {
    if (v > 0) return 1.f;
    if (v < 0) return -1.f;
    return v;
}
FN_FUNC_FN(sign)

// 6.11.5
_RS_STATIC float3 __attribute__((overloadable)) cross(float3 lhs, float3 rhs) {
    float3 r;
    r.x = lhs.y * rhs.z  - lhs.z * rhs.y;
    r.y = lhs.z * rhs.x  - lhs.x * rhs.z;
    r.z = lhs.x * rhs.y  - lhs.y * rhs.x;
    return r;
}

_RS_STATIC float4 __attribute__((overloadable)) cross(float4 lhs, float4 rhs) {
    float4 r;
    r.x = lhs.y * rhs.z  - lhs.z * rhs.y;
    r.y = lhs.z * rhs.x  - lhs.x * rhs.z;
    r.z = lhs.x * rhs.y  - lhs.y * rhs.x;
    r.w = 0.f;
    return r;
}

_RS_STATIC float __attribute__((overloadable)) dot(float lhs, float rhs) {
    return lhs * rhs;
}
_RS_STATIC float __attribute__((overloadable)) dot(float2 lhs, float2 rhs) {
    return lhs.x*rhs.x + lhs.y*rhs.y;
}
_RS_STATIC float __attribute__((overloadable)) dot(float3 lhs, float3 rhs) {
    return lhs.x*rhs.x + lhs.y*rhs.y + lhs.z*rhs.z;
}
_RS_STATIC float __attribute__((overloadable)) dot(float4 lhs, float4 rhs) {
    return lhs.x*rhs.x + lhs.y*rhs.y + lhs.z*rhs.z + lhs.w*rhs.w;
}

_RS_STATIC float __attribute__((overloadable)) length(float v) {
    return v;
}
_RS_STATIC float __attribute__((overloadable)) length(float2 v) {
    return sqrt(v.x*v.x + v.y*v.y);
}
_RS_STATIC float __attribute__((overloadable)) length(float3 v) {
    return sqrt(v.x*v.x + v.y*v.y + v.z*v.z);
}
_RS_STATIC float __attribute__((overloadable)) length(float4 v) {
    return sqrt(v.x*v.x + v.y*v.y + v.z*v.z + v.w*v.w);
}

_RS_STATIC float __attribute__((overloadable)) distance(float lhs, float rhs) {
    return length(lhs - rhs);
}
_RS_STATIC float __attribute__((overloadable)) distance(float2 lhs, float2 rhs) {
    return length(lhs - rhs);
}
_RS_STATIC float __attribute__((overloadable)) distance(float3 lhs, float3 rhs) {
    return length(lhs - rhs);
}
_RS_STATIC float __attribute__((overloadable)) distance(float4 lhs, float4 rhs) {
    return length(lhs - rhs);
}

_RS_STATIC float __attribute__((overloadable)) normalize(float v) {
    return 1.f;
}
_RS_STATIC float2 __attribute__((overloadable)) normalize(float2 v) {
    return v / length(v);
}
_RS_STATIC float3 __attribute__((overloadable)) normalize(float3 v) {
    return v / length(v);
}
_RS_STATIC float4 __attribute__((overloadable)) normalize(float4 v) {
    return v / length(v);
}

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
#undef _RS_STATIC

#endif
