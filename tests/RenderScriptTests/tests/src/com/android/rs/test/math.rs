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

DECL_INT(res)
DECL_INT(src1)
DECL_INT(src2)

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

#define TEST_XN_FUNC_YN(typeout, fnc, typein)   \
    res_##typeout##_1 = fnc(src1_##typein##_1); \
    res_##typeout##_2 = fnc(src1_##typein##_2); \
    res_##typeout##_3 = fnc(src1_##typein##_3); \
    res_##typeout##_4 = fnc(src1_##typein##_4);

#define TEST_XN_FUNC_XN_XN(type, fnc)                       \
    res_##type##_1 = fnc(src1_##type##_1, src2_##type##_1); \
    res_##type##_2 = fnc(src1_##type##_2, src2_##type##_2); \
    res_##type##_3 = fnc(src1_##type##_3, src2_##type##_3); \
    res_##type##_4 = fnc(src1_##type##_4, src2_##type##_4);

#define TEST_X_FUNC_X_X_X(type, fnc)    \
    res_##type##_1 = fnc(src1_##type##_1, src2_##type##_1, src2_##type##_1);

#define TEST_IN_FUNC_IN(fnc)        \
    rsDebug("Testing " #fnc, 0);    \
    TEST_XN_FUNC_YN(uc, fnc, uc)    \
    TEST_XN_FUNC_YN(c, fnc, c)      \
    TEST_XN_FUNC_YN(us, fnc, us)    \
    TEST_XN_FUNC_YN(s, fnc, s)      \
    TEST_XN_FUNC_YN(ui, fnc, ui)    \
    TEST_XN_FUNC_YN(i, fnc, i)

#define TEST_UIN_FUNC_IN(fnc)       \
    rsDebug("Testing " #fnc, 0);    \
    TEST_XN_FUNC_YN(uc, fnc, c)     \
    TEST_XN_FUNC_YN(us, fnc, s)     \
    TEST_XN_FUNC_YN(ui, fnc, i)     \

#define TEST_IN_FUNC_IN_IN(fnc)     \
    rsDebug("Testing " #fnc, 0);    \
    TEST_XN_FUNC_XN_XN(uc, fnc)     \
    TEST_XN_FUNC_XN_XN(c, fnc)      \
    TEST_XN_FUNC_XN_XN(us, fnc)     \
    TEST_XN_FUNC_XN_XN(s, fnc)      \
    TEST_XN_FUNC_XN_XN(ui, fnc)     \
    TEST_XN_FUNC_XN_XN(i, fnc)

#define TEST_I_FUNC_I_I_I(fnc)      \
    rsDebug("Testing " #fnc, 0);    \
    TEST_X_FUNC_X_X_X(uc, fnc)      \
    TEST_X_FUNC_X_X_X(c, fnc)       \
    TEST_X_FUNC_X_X_X(us, fnc)      \
    TEST_X_FUNC_X_X_X(s, fnc)       \
    TEST_X_FUNC_X_X_X(ui, fnc)      \
    TEST_X_FUNC_X_X_X(i, fnc)

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

#define TEST_F34_FUNC_F34_F34(fnc)  \
    rsDebug("Testing " #fnc, 0);    \
    f3 = fnc(f3, f3);               \
    f4 = fnc(f4, f4);

#define TEST_FN_FUNC_FN_F(fnc)      \
    rsDebug("Testing " #fnc, 0);    \
    f1 = fnc(f1, f1);               \
    f2 = fnc(f2, f1);               \
    f3 = fnc(f3, f1);               \
    f4 = fnc(f4, f1);

#define TEST_F_FUNC_FN(fnc)         \
    rsDebug("Testing " #fnc, 0);    \
    f1 = fnc(f1);                   \
    f1 = fnc(f2);                   \
    f1 = fnc(f3);                   \
    f1 = fnc(f4);

#define TEST_F_FUNC_FN_FN(fnc)      \
    rsDebug("Testing " #fnc, 0);    \
    f1 = fnc(f1, f1);               \
    f1 = fnc(f2, f2);               \
    f1 = fnc(f3, f3);               \
    f1 = fnc(f4, f4);

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

#define TEST_FN_FUNC_FN_FN_F(fnc)   \
    rsDebug("Testing " #fnc, 0);    \
    f1 = fnc(f1, f1, f1);           \
    f2 = fnc(f2, f1, f1);           \
    f3 = fnc(f3, f1, f1);           \
    f4 = fnc(f4, f1, f1);

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
    TEST_FN_FUNC_FN_FN_FN(clamp);
    TEST_FN_FUNC_FN_FN_F(clamp);
    TEST_FN_FUNC_FN_FN(copysign);
    TEST_FN_FUNC_FN(cos);
    TEST_FN_FUNC_FN(cosh);
    TEST_FN_FUNC_FN(cospi);
    TEST_F34_FUNC_F34_F34(cross);
    TEST_FN_FUNC_FN(degrees);
    TEST_F_FUNC_FN_FN(distance);
    TEST_F_FUNC_FN_FN(dot);
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
    TEST_FN_FUNC_FN(fract);
    TEST_FN_FUNC_FN_PIN(frexp);
    TEST_FN_FUNC_FN_FN(hypot);
    TEST_IN_FUNC_FN(ilogb);
    TEST_FN_FUNC_FN_IN(ldexp);
    TEST_FN_FUNC_FN_I(ldexp);
    TEST_F_FUNC_FN(length);
    TEST_FN_FUNC_FN(lgamma);
    TEST_FN_FUNC_FN_PIN(lgamma);
    TEST_FN_FUNC_FN(log);
    TEST_FN_FUNC_FN(log2);
    TEST_FN_FUNC_FN(log10);
    TEST_FN_FUNC_FN(log1p);
    TEST_FN_FUNC_FN(logb);
    TEST_FN_FUNC_FN_FN_FN(mad);
    TEST_FN_FUNC_FN_FN(max);
    TEST_FN_FUNC_FN_F(max);
    TEST_FN_FUNC_FN_FN(min);
    TEST_FN_FUNC_FN_F(min);
    TEST_FN_FUNC_FN_FN_FN(mix);
    TEST_FN_FUNC_FN_FN_F(mix);
    TEST_FN_FUNC_FN_PFN(modf);
    // nan
    TEST_FN_FUNC_FN_FN(nextafter);
    TEST_FN_FUNC_FN(normalize);
    TEST_FN_FUNC_FN_FN(pow);
    TEST_FN_FUNC_FN_IN(pown);
    TEST_FN_FUNC_FN_FN(powr);
    TEST_FN_FUNC_FN(radians);
    TEST_FN_FUNC_FN_FN(remainder);
    TEST_FN_FUNC_FN_FN_PIN(remquo);
    TEST_FN_FUNC_FN(rint);
    TEST_FN_FUNC_FN_IN(rootn);
    TEST_FN_FUNC_FN(round);
    TEST_FN_FUNC_FN(rsqrt);
    TEST_FN_FUNC_FN(sign);
    TEST_FN_FUNC_FN(sin);
    TEST_FN_FUNC_FN_PFN(sincos);
    TEST_FN_FUNC_FN(sinh);
    TEST_FN_FUNC_FN(sinpi);
    TEST_FN_FUNC_FN(sqrt);
    TEST_FN_FUNC_FN_FN(step);
    TEST_FN_FUNC_FN_F(step);
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

static bool test_int_math(uint32_t index) {
    bool failed = false;
    start();

    TEST_UIN_FUNC_IN(abs);
    TEST_IN_FUNC_IN(clz);
    TEST_IN_FUNC_IN_IN(min);
    TEST_IN_FUNC_IN_IN(max);
    TEST_I_FUNC_I_I_I(rsClamp);

    float time = end(index);

    if (failed) {
        rsDebug("test_int_math FAILED", time);
    }
    else {
        rsDebug("test_int_math PASSED", time);
    }

    return failed;
}

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

void math_test(uint32_t index, int test_num) {
    bool failed = false;
    failed |= test_convert();
    failed |= test_fp_math(index);
    failed |= test_int_math(index);
    failed |= test_basic_operators();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

