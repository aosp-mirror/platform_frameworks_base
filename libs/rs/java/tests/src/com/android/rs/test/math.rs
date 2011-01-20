#include "shared.rsh"

// Testing math library

volatile float f1;
volatile float2 f2;
volatile float3 f3;
volatile float4 f4;

volatile int i1;
volatile int2 i2;
volatile int3 i3;
volatile int4 i4;

#define TEST_FN_FUNC_FN(fnc)        \
    rsDebug("Testing " #fnc, 0);    \
    f1 = fnc(f1);                   \
    f2 = fnc(f2);                   \
    f3 = fnc(f3);                   \
    f4 = fnc(f4);

#define TEST_FN_FUNC_FN_PFN(fnc)    \
    rsDebug("Testing " #fnc, 0);    \
    f1 = fnc(f1, (float*) &f1);     \
    f2 = fnc(f2, (float2*) &f2);    \
    f3 = fnc(f3, (float3*) &f3);    \
    f4 = fnc(f4, (float4*) &f4);

#define TEST_FN_FUNC_FN_FN(fnc)     \
    rsDebug("Testing " #fnc, 0);    \
    f1 = fnc(f1, f1);               \
    f2 = fnc(f2, f2);               \
    f3 = fnc(f3, f3);               \
    f4 = fnc(f4, f4);

#define TEST_FN_FUNC_FN_F(fnc)      \
    rsDebug("Testing " #fnc, 0);    \
    f1 = fnc(f1, f1);               \
    f2 = fnc(f2, f1);               \
    f3 = fnc(f3, f1);               \
    f4 = fnc(f4, f1);

#define TEST_FN_FUNC_FN_IN(fnc)     \
    rsDebug("Testing " #fnc, 0);    \
    f1 = fnc(f1, i1);               \
    f2 = fnc(f2, i2);               \
    f3 = fnc(f3, i3);               \
    f4 = fnc(f4, i4);

#define TEST_FN_FUNC_FN_I(fnc)      \
    rsDebug("Testing " #fnc, 0);    \
    f1 = fnc(f1, i1);               \
    f2 = fnc(f2, i1);               \
    f3 = fnc(f3, i1);               \
    f4 = fnc(f4, i1);

#define TEST_FN_FUNC_FN_FN_FN(fnc)  \
    rsDebug("Testing " #fnc, 0);    \
    f1 = fnc(f1, f1, f1);           \
    f2 = fnc(f2, f2, f2);           \
    f3 = fnc(f3, f3, f3);           \
    f4 = fnc(f4, f4, f4);

#define TEST_FN_FUNC_FN_PIN(fnc)    \
    rsDebug("Testing " #fnc, 0);    \
    f1 = fnc(f1, (int*) &i1);       \
    f2 = fnc(f2, (int2*) &i2);      \
    f3 = fnc(f3, (int3*) &i3);      \
    f4 = fnc(f4, (int4*) &i4);

#define TEST_FN_FUNC_FN_FN_PIN(fnc) \
    rsDebug("Testing " #fnc, 0);    \
    f1 = fnc(f1, f1, (int*) &i1);   \
    f2 = fnc(f2, f2, (int2*) &i2);  \
    f3 = fnc(f3, f3, (int3*) &i3);  \
    f4 = fnc(f4, f4, (int4*) &i4);

#define TEST_IN_FUNC_FN(fnc)        \
    rsDebug("Testing " #fnc, 0);    \
    i1 = fnc(f1);                   \
    i2 = fnc(f2);                   \
    i3 = fnc(f3);                   \
    i4 = fnc(f4);


static bool test_fp_math(uint32_t index) {
    bool failed = false;
    start();

    TEST_FN_FUNC_FN(acos);
    TEST_FN_FUNC_FN(acosh);
    TEST_FN_FUNC_FN(acospi);
    TEST_FN_FUNC_FN(asin);
    TEST_FN_FUNC_FN(asinh);
    TEST_FN_FUNC_FN(asinpi);
    TEST_FN_FUNC_FN(atan);
    TEST_FN_FUNC_FN_FN(atan2);
    TEST_FN_FUNC_FN(atanh);
    TEST_FN_FUNC_FN(atanpi);
    TEST_FN_FUNC_FN_FN(atan2pi);
    TEST_FN_FUNC_FN(cbrt);
    TEST_FN_FUNC_FN(ceil);
    TEST_FN_FUNC_FN_FN(copysign);
    TEST_FN_FUNC_FN(cos);
    TEST_FN_FUNC_FN(cosh);
    TEST_FN_FUNC_FN(cospi);
    TEST_FN_FUNC_FN(erfc);
    TEST_FN_FUNC_FN(erf);
    TEST_FN_FUNC_FN(exp);
    TEST_FN_FUNC_FN(exp2);
    TEST_FN_FUNC_FN(exp10);
    TEST_FN_FUNC_FN(expm1);
    TEST_FN_FUNC_FN(fabs);
    TEST_FN_FUNC_FN_FN(fdim);
    TEST_FN_FUNC_FN(floor);
    TEST_FN_FUNC_FN_FN_FN(fma);
    TEST_FN_FUNC_FN_FN(fmax);
    TEST_FN_FUNC_FN_F(fmax);
    TEST_FN_FUNC_FN_FN(fmin);
    TEST_FN_FUNC_FN_F(fmin);
    TEST_FN_FUNC_FN_FN(fmod);
    TEST_FN_FUNC_FN_PFN(fract);
    TEST_FN_FUNC_FN_PIN(frexp);
    TEST_FN_FUNC_FN_FN(hypot);
    TEST_IN_FUNC_FN(ilogb);
    TEST_FN_FUNC_FN_IN(ldexp);
    TEST_FN_FUNC_FN_I(ldexp);
    TEST_FN_FUNC_FN(lgamma);
    TEST_FN_FUNC_FN_PIN(lgamma);
    TEST_FN_FUNC_FN(log);
    TEST_FN_FUNC_FN(log2);
    TEST_FN_FUNC_FN(log10);
    TEST_FN_FUNC_FN(log1p);
    TEST_FN_FUNC_FN(logb);
    TEST_FN_FUNC_FN_FN_FN(mad);
    TEST_FN_FUNC_FN_PFN(modf);
    // nan
    TEST_FN_FUNC_FN_FN(nextafter);
    TEST_FN_FUNC_FN_FN(pow);
    TEST_FN_FUNC_FN_IN(pown);
    TEST_FN_FUNC_FN_FN(powr);
    TEST_FN_FUNC_FN_FN(remainder);
    TEST_FN_FUNC_FN_FN_PIN(remquo);
    TEST_FN_FUNC_FN(rint);
    TEST_FN_FUNC_FN_IN(rootn);
    TEST_FN_FUNC_FN(round);
    TEST_FN_FUNC_FN(rsqrt);
    TEST_FN_FUNC_FN(sin);
    TEST_FN_FUNC_FN_PFN(sincos);
    TEST_FN_FUNC_FN(sinh);
    TEST_FN_FUNC_FN(sinpi);
    TEST_FN_FUNC_FN(sqrt);
    TEST_FN_FUNC_FN(tan);
    TEST_FN_FUNC_FN(tanh);
    TEST_FN_FUNC_FN(tanpi);
    TEST_FN_FUNC_FN(tgamma);
    TEST_FN_FUNC_FN(trunc);

    float time = end(index);

    if (failed) {
        rsDebug("test_fp_math FAILED", time);
    }
    else {
        rsDebug("test_fp_math PASSED", time);
    }

    return failed;
}

void math_test(uint32_t index, int test_num) {
    bool failed = false;
    failed |= test_fp_math(index);

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

