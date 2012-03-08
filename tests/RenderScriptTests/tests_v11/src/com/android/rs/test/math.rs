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

volatile uint ui1;
volatile uint2 ui2;
volatile uint3 ui3;
volatile uint4 ui4;

volatile short s1;
volatile short2 s2;
volatile short3 s3;
volatile short4 s4;

volatile ushort us1;
volatile ushort2 us2;
volatile ushort3 us3;
volatile ushort4 us4;

volatile char c1;
volatile char2 c2;
volatile char3 c3;
volatile char4 c4;

volatile uchar uc1;
volatile uchar2 uc2;
volatile uchar3 uc3;
volatile uchar4 uc4;

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

#define DECL_INT(prefix)            \
volatile char prefix##_c_1 = 1;     \
volatile char2 prefix##_c_2 = 1;    \
volatile char3 prefix##_c_3 = 1;    \
volatile char4 prefix##_c_4 = 1;    \
volatile uchar prefix##_uc_1 = 1;   \
volatile uchar2 prefix##_uc_2 = 1;  \
volatile uchar3 prefix##_uc_3 = 1;  \
volatile uchar4 prefix##_uc_4 = 1;  \
volatile short prefix##_s_1 = 1;    \
volatile short2 prefix##_s_2 = 1;   \
volatile short3 prefix##_s_3 = 1;   \
volatile short4 prefix##_s_4 = 1;   \
volatile ushort prefix##_us_1 = 1;  \
volatile ushort2 prefix##_us_2 = 1; \
volatile ushort3 prefix##_us_3 = 1; \
volatile ushort4 prefix##_us_4 = 1; \
volatile int prefix##_i_1 = 1;      \
volatile int2 prefix##_i_2 = 1;     \
volatile int3 prefix##_i_3 = 1;     \
volatile int4 prefix##_i_4 = 1;     \
volatile uint prefix##_ui_1 = 1;    \
volatile uint2 prefix##_ui_2 = 1;   \
volatile uint3 prefix##_ui_3 = 1;   \
volatile uint4 prefix##_ui_4 = 1;   \
volatile long prefix##_l_1 = 1;     \
volatile ulong prefix##_ul_1 = 1;

#define TEST_INT_OP_TYPE(op, type)                      \
rsDebug("Testing " #op " for " #type "1", i++);         \
res_##type##_1 = src1_##type##_1 op src2_##type##_1;    \
rsDebug("Testing " #op " for " #type "2", i++);         \
res_##type##_2 = src1_##type##_2 op src2_##type##_2;    \
rsDebug("Testing " #op " for " #type "3", i++);         \
res_##type##_3 = src1_##type##_3 op src2_##type##_3;    \
rsDebug("Testing " #op " for " #type "4", i++);         \
res_##type##_4 = src1_##type##_4 op src2_##type##_4;

#define TEST_INT_OP(op)                     \
TEST_INT_OP_TYPE(op, c)                     \
TEST_INT_OP_TYPE(op, uc)                    \
TEST_INT_OP_TYPE(op, s)                     \
TEST_INT_OP_TYPE(op, us)                    \
TEST_INT_OP_TYPE(op, i)                     \
TEST_INT_OP_TYPE(op, ui)                    \
rsDebug("Testing " #op " for l1", i++);     \
res_l_1 = src1_l_1 op src2_l_1;             \
rsDebug("Testing " #op " for ul1", i++);    \
res_ul_1 = src1_ul_1 op src2_ul_1;

DECL_INT(res)
DECL_INT(src1)
DECL_INT(src2)

static bool test_basic_operators() {
    bool failed = false;
    int i = 0;

    TEST_INT_OP(+);
    TEST_INT_OP(-);
    TEST_INT_OP(*);
    TEST_INT_OP(/);
    TEST_INT_OP(%);
    TEST_INT_OP(<<);
    TEST_INT_OP(>>);

    if (failed) {
        rsDebug("test_basic_operators FAILED", 0);
    }
    else {
        rsDebug("test_basic_operators PASSED", 0);
    }

    return failed;
}

#define TEST_CVT(to, from, type)                        \
rsDebug("Testing convert from " #from " to " #to, 0);   \
to##1 = from##1;                                        \
to##2 = convert_##type##2(from##2);                     \
to##3 = convert_##type##3(from##3);                     \
to##4 = convert_##type##4(from##4);

#define TEST_CVT_MATRIX(to, type)   \
TEST_CVT(to, c, type);              \
TEST_CVT(to, uc, type);             \
TEST_CVT(to, s, type);              \
TEST_CVT(to, us, type);             \
TEST_CVT(to, i, type);              \
TEST_CVT(to, ui, type);             \
TEST_CVT(to, f, type);              \

static bool test_convert() {
    bool failed = false;

    TEST_CVT_MATRIX(c, char);
    TEST_CVT_MATRIX(uc, uchar);
    TEST_CVT_MATRIX(s, short);
    TEST_CVT_MATRIX(us, ushort);
    TEST_CVT_MATRIX(i, int);
    TEST_CVT_MATRIX(ui, uint);
    TEST_CVT_MATRIX(f, float);

    if (failed) {
        rsDebug("test_convert FAILED", 0);
    }
    else {
        rsDebug("test_convert PASSED", 0);
    }

    return failed;
}

#define INIT_PREFIX_TYPE(prefix, type)  \
prefix##_##type##_1 = 1;                \
prefix##_##type##_2.x = 1;              \
prefix##_##type##_2.y = 1;              \
prefix##_##type##_3.x = 1;              \
prefix##_##type##_3.y = 1;              \
prefix##_##type##_3.z = 1;              \
prefix##_##type##_4.x = 1;              \
prefix##_##type##_4.y = 1;              \
prefix##_##type##_4.z = 1;              \
prefix##_##type##_4.w = 1;

#define INIT_TYPE(type)         \
INIT_PREFIX_TYPE(src1, type)    \
INIT_PREFIX_TYPE(src2, type)    \
INIT_PREFIX_TYPE(res, type)

#define INIT_ALL    \
INIT_TYPE(c);       \
INIT_TYPE(uc);      \
INIT_TYPE(s);       \
INIT_TYPE(us);      \
INIT_TYPE(i);       \
INIT_TYPE(ui);

void math_test(uint32_t index, int test_num) {
    bool failed = false;
    INIT_ALL;
    failed |= test_convert();
    failed |= test_fp_math(index);
    failed |= test_basic_operators();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

