#ifndef __RS_CL_RSH__
#define __RS_CL_RSH__

#define M_PI        3.14159265358979323846264338327950288f   /* pi */


// Conversions
#define CVT_FUNC_2(typeout, typein) \
static typeout##2 __attribute__((overloadable)) convert_##typeout##2(typein##2 v) { \
    typeout##2 r = {v.x, v.y}; \
    return r; \
} \
static typeout##3 __attribute__((overloadable)) convert_##typeout##3(typein##3 v) { \
    typeout##3 r = {v.x, v.y, v.z}; \
    return r; \
} \
static typeout##4 __attribute__((overloadable)) convert_##typeout##4(typein##4 v) { \
    typeout##4 r = {v.x, v.y, v.z, v.w}; \
    return r; \
}

#define CVT_FUNC(type)      CVT_FUNC_2(type, uchar) \
                            CVT_FUNC_2(type, char) \
                            CVT_FUNC_2(type, ushort) \
                            CVT_FUNC_2(type, short) \
                            CVT_FUNC_2(type, int) \
                            CVT_FUNC_2(type, uint) \
                            CVT_FUNC_2(type, float)

CVT_FUNC(char)
CVT_FUNC(uchar)
CVT_FUNC(short)
CVT_FUNC(ushort)
CVT_FUNC(int)
CVT_FUNC(uint)
CVT_FUNC(float)



// Float ops, 6.11.2

#define DEF_FUNC_1(fnc) \
static float2 __attribute__((overloadable)) fnc(float2 v) { \
    float2 r; \
    r.x = fnc(v.x); \
    r.y = fnc(v.y); \
    return r; \
} \
static float3 __attribute__((overloadable)) fnc(float3 v) { \
    float3 r; \
    r.x = fnc(v.x); \
    r.y = fnc(v.y); \
    r.z = fnc(v.z); \
    return r; \
} \
static float4 __attribute__((overloadable)) fnc(float4 v) { \
    float4 r; \
    r.x = fnc(v.x); \
    r.y = fnc(v.y); \
    r.z = fnc(v.z); \
    r.w = fnc(v.w); \
    return r; \
}

#define DEF_FUNC_2(fnc) \
static float2 __attribute__((overloadable)) fnc(float2 v1, float2 v2) { \
    float2 r; \
    r.x = fnc(v1.x, v2.x); \
    r.y = fnc(v1.y, v2.y); \
    return r; \
} \
static float3 __attribute__((overloadable)) fnc(float3 v1, float3 v2) { \
    float3 r; \
    r.x = fnc(v1.x, v2.x); \
    r.y = fnc(v1.y, v2.y); \
    r.z = fnc(v1.z, v2.z); \
    return r; \
} \
static float4 __attribute__((overloadable)) fnc(float4 v1, float4 v2) { \
    float4 r; \
    r.x = fnc(v1.x, v2.x); \
    r.y = fnc(v1.y, v2.y); \
    r.z = fnc(v1.z, v2.z); \
    r.w = fnc(v1.w, v2.z); \
    return r; \
}

#define DEF_FUNC_2F(fnc) \
static float2 __attribute__((overloadable)) fnc(float2 v1, float v2) { \
    float2 r; \
    r.x = fnc(v1.x, v2); \
    r.y = fnc(v1.y, v2); \
    return r; \
} \
static float3 __attribute__((overloadable)) fnc(float3 v1, float v2) { \
    float3 r; \
    r.x = fnc(v1.x, v2); \
    r.y = fnc(v1.y, v2); \
    r.z = fnc(v1.z, v2); \
    return r; \
} \
static float4 __attribute__((overloadable)) fnc(float4 v1, float v2) { \
    float4 r; \
    r.x = fnc(v1.x, v2); \
    r.y = fnc(v1.y, v2); \
    r.z = fnc(v1.z, v2); \
    r.w = fnc(v1.w, v2); \
    return r; \
}


extern float __attribute__((overloadable)) acos(float);
DEF_FUNC_1(acos)

extern float __attribute__((overloadable)) acosh(float);
DEF_FUNC_1(acosh)

static float __attribute__((overloadable)) acospi(float v) {
    return acos(v) / M_PI;
}
DEF_FUNC_1(acospi)

extern float __attribute__((overloadable)) asin(float);
DEF_FUNC_1(asin)

extern float __attribute__((overloadable)) asinh(float);
DEF_FUNC_1(asinh)

static float __attribute__((overloadable)) asinpi(float v) {
    return asin(v) / M_PI;
}
DEF_FUNC_1(asinpi)

extern float __attribute__((overloadable)) atan(float);
DEF_FUNC_1(atan)

extern float __attribute__((overloadable)) atan2(float, float);
DEF_FUNC_2(atan2)

extern float __attribute__((overloadable)) atanh(float);
DEF_FUNC_1(atanh)

static float __attribute__((overloadable)) atanpi(float v) {
    return atan(v) / M_PI;
}
DEF_FUNC_1(atanpi)

static float __attribute__((overloadable)) atan2pi(float y, float x) {
    return atan2(y, x) / M_PI;
}
DEF_FUNC_2(atan2pi)

extern float __attribute__((overloadable)) cbrt(float);
DEF_FUNC_1(cbrt)

extern float __attribute__((overloadable)) ceil(float);
DEF_FUNC_1(ceil)

extern float __attribute__((overloadable)) copysign(float, float);
DEF_FUNC_2(copysign)

extern float __attribute__((overloadable)) cos(float);
DEF_FUNC_1(cos)

extern float __attribute__((overloadable)) cosh(float);
DEF_FUNC_1(cosh)

static float __attribute__((overloadable)) cospi(float v) {
    return cos(v * M_PI);
}
DEF_FUNC_1(cospi)

extern float __attribute__((overloadable)) erfc(float);
DEF_FUNC_1(erfc)

extern float __attribute__((overloadable)) erf(float);
DEF_FUNC_1(erf)

extern float __attribute__((overloadable)) exp(float);
DEF_FUNC_1(exp)

extern float __attribute__((overloadable)) exp2(float);
DEF_FUNC_1(exp2)

extern float __attribute__((overloadable)) pow(float, float);
static float __attribute__((overloadable)) exp10(float v) {
    return pow(10.f, v);
}
DEF_FUNC_1(exp10)

extern float __attribute__((overloadable)) expm1(float);
DEF_FUNC_1(expm1)

extern float __attribute__((overloadable)) fabs(float);
DEF_FUNC_1(fabs)

extern float __attribute__((overloadable)) fdim(float, float);
DEF_FUNC_2(fdim)

extern float __attribute__((overloadable)) floor(float);
DEF_FUNC_1(floor)

extern float __attribute__((overloadable)) fma(float, float, float);
extern float2 __attribute__((overloadable)) fma(float2, float2, float2);
extern float3 __attribute__((overloadable)) fma(float3, float3, float3);
extern float4 __attribute__((overloadable)) fma(float4, float4, float4);

extern float __attribute__((overloadable)) fmax(float, float);
DEF_FUNC_2(fmax);
DEF_FUNC_2F(fmax);

extern float __attribute__((overloadable)) fmin(float, float);
DEF_FUNC_2(fmin);
DEF_FUNC_2F(fmin);

extern float __attribute__((overloadable)) fmod(float, float);
DEF_FUNC_2(fmod)

static float __attribute__((overloadable)) fract(float v, float *iptr) {
    int i = (int)floor(v);
    iptr[0] = i;
    return fmin(v - i, 0x1.fffffep-1f);
}
static float2 __attribute__((overloadable)) fract(float2 v, float2 *iptr) {
    float t[2];
    float2 r;
    r.x = fract(v.x, &t[0]);
    r.y = fract(v.y, &t[1]);
    iptr[0] = t[0];
    iptr[1] = t[1];
    return r;
}
static float3 __attribute__((overloadable)) fract(float3 v, float3 *iptr) {
    float t[3];
    float3 r;
    r.x = fract(v.x, &t[0]);
    r.y = fract(v.y, &t[1]);
    r.z = fract(v.z, &t[2]);
    iptr[0] = t[0];
    iptr[1] = t[1];
    iptr[2] = t[2];
    return r;
}
static float4 __attribute__((overloadable)) fract(float4 v, float4 *iptr) {
    float t[4];
    float4 r;
    r.x = fract(v.x, &t[0]);
    r.y = fract(v.y, &t[1]);
    r.z = fract(v.z, &t[2]);
    r.w = fract(v.w, &t[3]);
    iptr[0] = t[0];
    iptr[1] = t[1];
    iptr[2] = t[2];
    iptr[3] = t[3];
    return r;
}

extern float __attribute__((overloadable)) frexp(float, float *);
extern float2 __attribute__((overloadable)) frexp(float2, float2 *);
extern float3 __attribute__((overloadable)) frexp(float3, float3 *);
extern float4 __attribute__((overloadable)) frexp(float4, float4 *);

extern float __attribute__((overloadable)) hypot(float, float);
DEF_FUNC_2(hypot)

extern int __attribute__((overloadable)) ilogb(float);
DEF_FUNC_1(ilogb)

extern float __attribute__((overloadable)) ldexp(float, int);
extern float2 __attribute__((overloadable)) ldexp(float2, int2);
extern float3 __attribute__((overloadable)) ldexp(float3, int3);
extern float4 __attribute__((overloadable)) ldexp(float4, int4);
extern float2 __attribute__((overloadable)) ldexp(float2, int);
extern float3 __attribute__((overloadable)) ldexp(float3, int);
extern float4 __attribute__((overloadable)) ldexp(float4, int);

extern float __attribute__((overloadable)) lgamma(float);
DEF_FUNC_1(lgamma)
extern float __attribute__((overloadable)) lgamma(float, float *);
extern float2 __attribute__((overloadable)) lgamma(float2, float2 *);
extern float3 __attribute__((overloadable)) lgamma(float3, float3 *);
extern float4 __attribute__((overloadable)) lgamma(float4, float4 *);

extern float __attribute__((overloadable)) log(float);
DEF_FUNC_1(log)


extern float __attribute__((overloadable)) log10(float);
DEF_FUNC_1(log10)

static float __attribute__((overloadable)) log2(float v) {
    return log10(v) / log10(2.f);
}
DEF_FUNC_1(log2)

extern float __attribute__((overloadable)) log1p(float);
DEF_FUNC_1(log1p)

extern float __attribute__((overloadable)) logb(float);
DEF_FUNC_1(logb)

extern float __attribute__((overloadable)) mad(float, float, float);
extern float2 __attribute__((overloadable)) mad(float2, float2, float2);
extern float3 __attribute__((overloadable)) mad(float3, float3, float3);
extern float4 __attribute__((overloadable)) mad(float4, float4, float4);

extern float __attribute__((overloadable)) modf(float, float *);
extern float2 __attribute__((overloadable)) modf(float2, float2 *);
extern float3 __attribute__((overloadable)) modf(float3, float3 *);
extern float4 __attribute__((overloadable)) modf(float4, float4 *);

//extern float __attribute__((overloadable)) nan(uint);

extern float __attribute__((overloadable)) nextafter(float, float);
DEF_FUNC_2(nextafter)

DEF_FUNC_2(pow)

static float __attribute__((overloadable)) pown(float v, int p) {
    return pow(v, (float)p);
}
static float2 __attribute__((overloadable)) pown(float2 v, int2 p) {
    return pow(v, (float2)p);
}
static float3 __attribute__((overloadable)) pown(float3 v, int3 p) {
    return pow(v, (float3)p);
}
static float4 __attribute__((overloadable)) pown(float4 v, int4 p) {
    return pow(v, (float4)p);
}

static float __attribute__((overloadable)) powr(float v, float p) {
    return pow(v, p);
}
static float2 __attribute__((overloadable)) powr(float2 v, float2 p) {
    return pow(v, p);
}
static float3 __attribute__((overloadable)) powr(float3 v, float3 p) {
    return pow(v, p);
}
static float4 __attribute__((overloadable)) powr(float4 v, float4 p) {
    return pow(v, p);
}

extern float __attribute__((overloadable)) remainder(float, float);
DEF_FUNC_2(remainder)

extern float __attribute__((overloadable)) remquo(float, float, float *);
extern float2 __attribute__((overloadable)) remquo(float2, float2, float2 *);
extern float3 __attribute__((overloadable)) remquo(float3, float3, float3 *);
extern float4 __attribute__((overloadable)) remquo(float4, float4, float4 *);

extern float __attribute__((overloadable)) rint(float);
DEF_FUNC_1(rint)

static float __attribute__((overloadable)) rootn(float v, int r) {
    return pow(v, 1.f / r);
}
static float2 __attribute__((overloadable)) rootn(float2 v, int2 r) {
    float2 t = {1.f / r.x, 1.f / r.y};
    return pow(v, t);
}
static float3 __attribute__((overloadable)) rootn(float3 v, int3 r) {
    float3 t = {1.f / r.x, 1.f / r.y, 1.f / r.z};
    return pow(v, t);
}
static float4 __attribute__((overloadable)) rootn(float4 v, int4 r) {
    float4 t = {1.f / r.x, 1.f / r.y, 1.f / r.z, 1.f / r.w};
    return pow(v, t);
}

extern float __attribute__((overloadable)) round(float);
DEF_FUNC_1(round)

extern float __attribute__((overloadable)) sqrt(float);
/*static float __attribute__((overloadable)) rsqrt(float v) {
    return 1.f / sqrt(v);
}
DEF_FUNC_1(rsqrt)*/

extern float __attribute__((overloadable)) sin(float);
DEF_FUNC_1(sin)

static float __attribute__((overloadable)) sincos(float v, float *cosptr) {
    *cosptr = cos(v);
    return sin(v);
}
static float2 __attribute__((overloadable)) sincos(float2 v, float2 *cosptr) {
    *cosptr = cos(v);
    return sin(v);
}
static float3 __attribute__((overloadable)) sincos(float3 v, float3 *cosptr) {
    *cosptr = cos(v);
    return sin(v);
}
static float4 __attribute__((overloadable)) sincos(float4 v, float4 *cosptr) {
    *cosptr = cos(v);
    return sin(v);
}

extern float __attribute__((overloadable)) sinh(float);
DEF_FUNC_1(sinh)

static float __attribute__((overloadable)) sinpi(float v) {
    return sin(v * M_PI);
}
DEF_FUNC_1(sinpi)

DEF_FUNC_1(sqrt)

extern float __attribute__((overloadable)) tan(float);
DEF_FUNC_1(tan)

extern float __attribute__((overloadable)) tanh(float);
DEF_FUNC_1(tanh)

static float __attribute__((overloadable)) tanpi(float v) {
    return tan(v * M_PI);
}
DEF_FUNC_1(tanpi)

extern float __attribute__((overloadable)) tgamma(float);
DEF_FUNC_1(tgamma)

extern float __attribute__((overloadable)) trunc(float);
DEF_FUNC_1(trunc)

// Int ops (partial), 6.11.3
extern uint __attribute__((overloadable)) abs(int);
extern ushort __attribute__((overloadable)) abs(short);
extern uchar __attribute__((overloadable)) abs(char);

extern uint __attribute__((overloadable)) clz(uint);
extern int __attribute__((overloadable)) clz(int);
extern ushort __attribute__((overloadable)) clz(ushort);
extern short __attribute__((overloadable)) clz(short);
extern uchar __attribute__((overloadable)) clz(uchar);
extern char __attribute__((overloadable)) clz(char);

static uint __attribute__((overloadable)) min(uint v1, uint v2) {
    return v1 < v2 ? v1 : v2;
}
static int __attribute__((overloadable)) min(int v1, int v2) {
    return v1 < v2 ? v1 : v2;
}
static ushort __attribute__((overloadable)) min(ushort v1, ushort v2) {
    return v1 < v2 ? v1 : v2;
}
static short __attribute__((overloadable)) min(short v1, short v2) {
    return v1 < v2 ? v1 : v2;
}
static uchar __attribute__((overloadable)) min(uchar v1, uchar v2) {
    return v1 < v2 ? v1 : v2;
}
static char __attribute__((overloadable)) min(char v1, char v2) {
    return v1 < v2 ? v1 : v2;
}

static uint __attribute__((overloadable)) max(uint v1, uint v2) {
    return v1 > v2 ? v1 : v2;
}
static int __attribute__((overloadable)) max(int v1, int v2) {
    return v1 > v2 ? v1 : v2;
}
static ushort __attribute__((overloadable)) max(ushort v1, ushort v2) {
    return v1 > v2 ? v1 : v2;
}
static short __attribute__((overloadable)) max(short v1, short v2) {
    return v1 > v2 ? v1 : v2;
}
static uchar __attribute__((overloadable)) max(uchar v1, uchar v2) {
    return v1 > v2 ? v1 : v2;
}
static char __attribute__((overloadable)) max(char v1, char v2) {
    return v1 > v2 ? v1 : v2;
}




// 6.11.4

static float __attribute__((overloadable)) clamp(float amount, float low, float high) {
    return amount < low ? low : (amount > high ? high : amount);
}
static float2 __attribute__((overloadable)) clamp(float2 amount, float2 low, float2 high) {
    float2 r;
    r.x = amount.x < low.x ? low.x : (amount.x > high.x ? high.x : amount.x);
    r.y = amount.y < low.y ? low.y : (amount.y > high.y ? high.y : amount.y);
    return r;
}
static float3 __attribute__((overloadable)) clamp(float3 amount, float3 low, float3 high) {
    float3 r;
    r.x = amount.x < low.x ? low.x : (amount.x > high.x ? high.x : amount.x);
    r.y = amount.y < low.y ? low.y : (amount.y > high.y ? high.y : amount.y);
    r.z = amount.z < low.z ? low.z : (amount.z > high.z ? high.z : amount.z);
    return r;
}
static float4 __attribute__((overloadable)) clamp(float4 amount, float4 low, float4 high) {
    float4 r;
    r.x = amount.x < low.x ? low.x : (amount.x > high.x ? high.x : amount.x);
    r.y = amount.y < low.y ? low.y : (amount.y > high.y ? high.y : amount.y);
    r.z = amount.z < low.z ? low.z : (amount.z > high.z ? high.z : amount.z);
    r.w = amount.w < low.w ? low.w : (amount.w > high.w ? high.w : amount.w);
    return r;
}
static float2 __attribute__((overloadable)) clamp(float2 amount, float low, float high) {
    float2 r;
    r.x = amount.x < low ? low : (amount.x > high ? high : amount.x);
    r.y = amount.y < low ? low : (amount.y > high ? high : amount.y);
    return r;
}
static float3 __attribute__((overloadable)) clamp(float3 amount, float low, float high) {
    float3 r;
    r.x = amount.x < low ? low : (amount.x > high ? high : amount.x);
    r.y = amount.y < low ? low : (amount.y > high ? high : amount.y);
    r.z = amount.z < low ? low : (amount.z > high ? high : amount.z);
    return r;
}
static float4 __attribute__((overloadable)) clamp(float4 amount, float low, float high) {
    float4 r;
    r.x = amount.x < low ? low : (amount.x > high ? high : amount.x);
    r.y = amount.y < low ? low : (amount.y > high ? high : amount.y);
    r.z = amount.z < low ? low : (amount.z > high ? high : amount.z);
    r.w = amount.w < low ? low : (amount.w > high ? high : amount.w);
    return r;
}

static float __attribute__((overloadable)) degrees(float radians) {
    return radians * (180.f / M_PI);
}
DEF_FUNC_1(degrees)

static float __attribute__((overloadable)) max(float v1, float v2) {
    return v1 > v2 ? v1 : v2;
}
static float2 __attribute__((overloadable)) max(float2 v1, float2 v2) {
    float2 r;
    r.x = v1.x > v2.x ? v1.x : v2.x;
    r.y = v1.y > v2.y ? v1.y : v2.y;
    return r;
}
static float3 __attribute__((overloadable)) max(float3 v1, float3 v2) {
    float3 r;
    r.x = v1.x > v2.x ? v1.x : v2.x;
    r.y = v1.y > v2.y ? v1.y : v2.y;
    r.z = v1.z > v2.z ? v1.z : v2.z;
    return r;
}
static float4 __attribute__((overloadable)) max(float4 v1, float4 v2) {
    float4 r;
    r.x = v1.x > v2.x ? v1.x : v2.x;
    r.y = v1.y > v2.y ? v1.y : v2.y;
    r.z = v1.z > v2.z ? v1.z : v2.z;
    r.w = v1.w > v2.w ? v1.w : v2.w;
    return r;
}
static float2 __attribute__((overloadable)) max(float2 v1, float v2) {
    float2 r;
    r.x = v1.x > v2 ? v1.x : v2;
    r.y = v1.y > v2 ? v1.y : v2;
    return r;
}
static float3 __attribute__((overloadable)) max(float3 v1, float v2) {
    float3 r;
    r.x = v1.x > v2 ? v1.x : v2;
    r.y = v1.y > v2 ? v1.y : v2;
    r.z = v1.z > v2 ? v1.z : v2;
    return r;
}
static float4 __attribute__((overloadable)) max(float4 v1, float v2) {
    float4 r;
    r.x = v1.x > v2 ? v1.x : v2;
    r.y = v1.y > v2 ? v1.y : v2;
    r.z = v1.z > v2 ? v1.z : v2;
    r.w = v1.w > v2 ? v1.w : v2;
    return r;
}

static float __attribute__((overloadable)) min(float v1, float v2) {
    return v1 < v2 ? v1 : v2;
}
static float2 __attribute__((overloadable)) min(float2 v1, float2 v2) {
    float2 r;
    r.x = v1.x < v2.x ? v1.x : v2.x;
    r.y = v1.y < v2.y ? v1.y : v2.y;
    return r;
}
static float3 __attribute__((overloadable)) min(float3 v1, float3 v2) {
    float3 r;
    r.x = v1.x < v2.x ? v1.x : v2.x;
    r.y = v1.y < v2.y ? v1.y : v2.y;
    r.z = v1.z < v2.z ? v1.z : v2.z;
    return r;
}
static float4 __attribute__((overloadable)) min(float4 v1, float4 v2) {
    float4 r;
    r.x = v1.x < v2.x ? v1.x : v2.x;
    r.y = v1.y < v2.y ? v1.y : v2.y;
    r.z = v1.z < v2.z ? v1.z : v2.z;
    r.w = v1.w < v2.w ? v1.w : v2.w;
    return r;
}
static float2 __attribute__((overloadable)) min(float2 v1, float v2) {
    float2 r;
    r.x = v1.x < v2 ? v1.x : v2;
    r.y = v1.y < v2 ? v1.y : v2;
    return r;
}
static float3 __attribute__((overloadable)) min(float3 v1, float v2) {
    float3 r;
    r.x = v1.x < v2 ? v1.x : v2;
    r.y = v1.y < v2 ? v1.y : v2;
    r.z = v1.z < v2 ? v1.z : v2;
    return r;
}
static float4 __attribute__((overloadable)) min(float4 v1, float v2) {
    float4 r;
    r.x = v1.x < v2 ? v1.x : v2;
    r.y = v1.y < v2 ? v1.y : v2;
    r.z = v1.z < v2 ? v1.z : v2;
    r.w = v1.w < v2 ? v1.w : v2;
    return r;
}

static float __attribute__((overloadable)) mix(float start, float stop, float amount) {
    return start + (stop - start) * amount;
}
static float2 __attribute__((overloadable)) mix(float2 start, float2 stop, float2 amount) {
    return start + (stop - start) * amount;
}
static float3 __attribute__((overloadable)) mix(float3 start, float3 stop, float3 amount) {
    return start + (stop - start) * amount;
}
static float4 __attribute__((overloadable)) mix(float4 start, float4 stop, float4 amount) {
    return start + (stop - start) * amount;
}
static float2 __attribute__((overloadable)) mix(float2 start, float2 stop, float amount) {
    return start + (stop - start) * amount;
}
static float3 __attribute__((overloadable)) mix(float3 start, float3 stop, float amount) {
    return start + (stop - start) * amount;
}
static float4 __attribute__((overloadable)) mix(float4 start, float4 stop, float amount) {
    return start + (stop - start) * amount;
}

static float __attribute__((overloadable)) radians(float degrees) {
    return degrees * (M_PI / 180.f);
}
DEF_FUNC_1(radians)

static float __attribute__((overloadable)) step(float edge, float v) {
    return (v < edge) ? 0.f : 1.f;
}
static float2 __attribute__((overloadable)) step(float2 edge, float2 v) {
    float2 r;
    r.x = (v.x < edge.x) ? 0.f : 1.f;
    r.y = (v.y < edge.y) ? 0.f : 1.f;
    return r;
}
static float3 __attribute__((overloadable)) step(float3 edge, float3 v) {
    float3 r;
    r.x = (v.x < edge.x) ? 0.f : 1.f;
    r.y = (v.y < edge.y) ? 0.f : 1.f;
    r.z = (v.z < edge.z) ? 0.f : 1.f;
    return r;
}
static float4 __attribute__((overloadable)) step(float4 edge, float4 v) {
    float4 r;
    r.x = (v.x < edge.x) ? 0.f : 1.f;
    r.y = (v.y < edge.y) ? 0.f : 1.f;
    r.z = (v.z < edge.z) ? 0.f : 1.f;
    r.w = (v.w < edge.w) ? 0.f : 1.f;
    return r;
}
static float2 __attribute__((overloadable)) step(float2 edge, float v) {
    float2 r;
    r.x = (v < edge.x) ? 0.f : 1.f;
    r.y = (v < edge.y) ? 0.f : 1.f;
    return r;
}
static float3 __attribute__((overloadable)) step(float3 edge, float v) {
    float3 r;
    r.x = (v < edge.x) ? 0.f : 1.f;
    r.y = (v < edge.y) ? 0.f : 1.f;
    r.z = (v < edge.z) ? 0.f : 1.f;
    return r;
}
static float4 __attribute__((overloadable)) step(float4 edge, float v) {
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

static float __attribute__((overloadable)) sign(float v) {
    if (v > 0) return 1.f;
    if (v < 0) return -1.f;
    return v;
}
DEF_FUNC_1(sign)

// 6.11.5
static float3 __attribute__((overloadable)) cross(float3 lhs, float3 rhs) {
    float3 r;
    r.x = lhs.y * rhs.z  - lhs.z * rhs.y;
    r.y = lhs.z * rhs.x  - lhs.x * rhs.z;
    r.z = lhs.x * rhs.y  - lhs.y * rhs.x;
    return r;
}

static float4 __attribute__((overloadable)) cross(float4 lhs, float4 rhs) {
    float4 r;
    r.x = lhs.y * rhs.z  - lhs.z * rhs.y;
    r.y = lhs.z * rhs.x  - lhs.x * rhs.z;
    r.z = lhs.x * rhs.y  - lhs.y * rhs.x;
    r.w = 0.f;
    return r;
}

static float __attribute__((overloadable)) dot(float lhs, float rhs) {
    return lhs * rhs;
}
static float __attribute__((overloadable)) dot(float2 lhs, float2 rhs) {
    return lhs.x*rhs.x + lhs.y*rhs.y;
}
static float __attribute__((overloadable)) dot(float3 lhs, float3 rhs) {
    return lhs.x*rhs.x + lhs.y*rhs.y + lhs.z*rhs.z;
}
static float __attribute__((overloadable)) dot(float4 lhs, float4 rhs) {
    return lhs.x*rhs.x + lhs.y*rhs.y + lhs.z*rhs.z + lhs.w*rhs.w;
}

static float __attribute__((overloadable)) length(float v) {
    return v;
}
static float __attribute__((overloadable)) length(float2 v) {
    return sqrt(v.x*v.x + v.y*v.y);
}
static float __attribute__((overloadable)) length(float3 v) {
    return sqrt(v.x*v.x + v.y*v.y + v.z*v.z);
}
static float __attribute__((overloadable)) length(float4 v) {
    return sqrt(v.x*v.x + v.y*v.y + v.z*v.z + v.w*v.w);
}

static float __attribute__((overloadable)) distance(float lhs, float rhs) {
    return length(lhs - rhs);
}
static float __attribute__((overloadable)) distance(float2 lhs, float2 rhs) {
    return length(lhs - rhs);
}
static float __attribute__((overloadable)) distance(float3 lhs, float3 rhs) {
    return length(lhs - rhs);
}
static float __attribute__((overloadable)) distance(float4 lhs, float4 rhs) {
    return length(lhs - rhs);
}

static float __attribute__((overloadable)) normalize(float v) {
    return 1.f;
}
static float2 __attribute__((overloadable)) normalize(float2 v) {
    return v / length(v);
}
static float3 __attribute__((overloadable)) normalize(float3 v) {
    return v / length(v);
}
static float4 __attribute__((overloadable)) normalize(float4 v) {
    return v / length(v);
}


#endif
