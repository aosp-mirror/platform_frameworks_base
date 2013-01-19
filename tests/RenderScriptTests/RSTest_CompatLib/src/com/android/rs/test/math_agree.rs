#include "shared.rsh"
//#pragma rs_fp_relaxed

volatile float x = 0.0f;
volatile float y = 0.0f;
volatile float result_add = 0.0f;
volatile float result_sub = 0.0f;
volatile float result_mul = 0.0f;
volatile float result_div = 0.0f;

#define DECLARE_INPUT_SET(type, abbrev)         \
volatile type    rand_##abbrev##1_0, rand_##abbrev##1_1; \
volatile type##2 rand_##abbrev##2_0, rand_##abbrev##2_1; \
volatile type##3 rand_##abbrev##3_0, rand_##abbrev##3_1; \
volatile type##4 rand_##abbrev##4_0, rand_##abbrev##4_1;

#define DECLARE_ALL_INPUT_SETS()    \
DECLARE_INPUT_SET(float, f);        \
DECLARE_INPUT_SET(char, sc);        \
DECLARE_INPUT_SET(uchar, uc);       \
DECLARE_INPUT_SET(short, ss);       \
DECLARE_INPUT_SET(ushort, us);      \
DECLARE_INPUT_SET(int, si);         \
DECLARE_INPUT_SET(uint, ui);        \
DECLARE_INPUT_SET(long, sl);        \
DECLARE_INPUT_SET(ulong, ul);

DECLARE_ALL_INPUT_SETS();

#define DECLARE_REFERENCE_SET_VEC_VEC(type, abbrev, func)   \
volatile type    func##_rand_##abbrev##1_##abbrev##1;                \
volatile type##2 func##_rand_##abbrev##2_##abbrev##2;                \
volatile type##3 func##_rand_##abbrev##3_##abbrev##3;                \
volatile type##4 func##_rand_##abbrev##4_##abbrev##4;
#define DECLARE_REFERENCE_SET_VEC_SCL(type, abbrev, func)   \
volatile type##2 func##_rand_##abbrev##2_##abbrev##1;                \
volatile type##3 func##_rand_##abbrev##3_##abbrev##1;                \
volatile type##4 func##_rand_##abbrev##4_##abbrev##1;

#define DECLARE_ALL_REFERENCE_SETS_VEC_VEC(func)    \
DECLARE_REFERENCE_SET_VEC_VEC(float, f, func);      \
DECLARE_REFERENCE_SET_VEC_VEC(char, sc, func);      \
DECLARE_REFERENCE_SET_VEC_VEC(uchar, uc, func);     \
DECLARE_REFERENCE_SET_VEC_VEC(short, ss, func);     \
DECLARE_REFERENCE_SET_VEC_VEC(ushort, us, func);    \
DECLARE_REFERENCE_SET_VEC_VEC(int, si, func);       \
DECLARE_REFERENCE_SET_VEC_VEC(uint, ui, func);      \
DECLARE_REFERENCE_SET_VEC_VEC(long, sl, func);      \
DECLARE_REFERENCE_SET_VEC_VEC(ulong, ul, func);

DECLARE_ALL_REFERENCE_SETS_VEC_VEC(min);
DECLARE_ALL_REFERENCE_SETS_VEC_VEC(max);
DECLARE_REFERENCE_SET_VEC_VEC(float, f, fmin);
DECLARE_REFERENCE_SET_VEC_SCL(float, f, fmin);
DECLARE_REFERENCE_SET_VEC_VEC(float, f, fmax);
DECLARE_REFERENCE_SET_VEC_SCL(float, f, fmax);

static void fail_f1(float v1, float v2, float actual, float expected, char *op_name) {
    int dist = float_dist(actual, expected);
    rsDebug("float operation did not match!", op_name);
    rsDebug("v1", v1);
    rsDebug("v2", v2);
    rsDebug("Dalvik result", expected);
    rsDebug("Renderscript result", actual);
    rsDebug("ULP difference", dist);
}

static void fail_f2(float2 v1, float2 v2, float2 actual, float2 expected, char *op_name) {
    int2 dist;
    dist.x = float_dist(actual.x, expected.x);
    dist.y = float_dist(actual.y, expected.y);
    rsDebug("float2 operation did not match!", op_name);
    rsDebug("v1.x", v1.x);
    rsDebug("v1.y", v1.y);
    rsDebug("v2.x", v2.x);
    rsDebug("v2.y", v2.y);
    rsDebug("Dalvik result .x", expected.x);
    rsDebug("Dalvik result .y", expected.y);
    rsDebug("Renderscript result .x", actual.x);
    rsDebug("Renderscript result .y", actual.y);
    rsDebug("ULP difference .x", dist.x);
    rsDebug("ULP difference .y", dist.y);
}

static void fail_f3(float3 v1, float3 v2, float3 actual, float3 expected, char *op_name) {
    int3 dist;
    dist.x = float_dist(actual.x, expected.x);
    dist.y = float_dist(actual.y, expected.y);
    dist.z = float_dist(actual.z, expected.z);
    rsDebug("float3 operation did not match!", op_name);
    rsDebug("v1.x", v1.x);
    rsDebug("v1.y", v1.y);
    rsDebug("v1.z", v1.z);
    rsDebug("v2.x", v2.x);
    rsDebug("v2.y", v2.y);
    rsDebug("v2.z", v2.z);
    rsDebug("Dalvik result .x", expected.x);
    rsDebug("Dalvik result .y", expected.y);
    rsDebug("Dalvik result .z", expected.z);
    rsDebug("Renderscript result .x", actual.x);
    rsDebug("Renderscript result .y", actual.y);
    rsDebug("Renderscript result .z", actual.z);
    rsDebug("ULP difference .x", dist.x);
    rsDebug("ULP difference .y", dist.y);
    rsDebug("ULP difference .z", dist.z);
}

static void fail_f4(float4 v1, float4 v2, float4 actual, float4 expected, char *op_name) {
    int4 dist;
    dist.x = float_dist(actual.x, expected.x);
    dist.y = float_dist(actual.y, expected.y);
    dist.z = float_dist(actual.z, expected.z);
    dist.w = float_dist(actual.w, expected.w);
    rsDebug("float4 operation did not match!", op_name);
    rsDebug("v1.x", v1.x);
    rsDebug("v1.y", v1.y);
    rsDebug("v1.z", v1.z);
    rsDebug("v1.w", v1.w);
    rsDebug("v2.x", v2.x);
    rsDebug("v2.y", v2.y);
    rsDebug("v2.z", v2.z);
    rsDebug("v2.w", v2.w);
    rsDebug("Dalvik result .x", expected.x);
    rsDebug("Dalvik result .y", expected.y);
    rsDebug("Dalvik result .z", expected.z);
    rsDebug("Dalvik result .w", expected.w);
    rsDebug("Renderscript result .x", actual.x);
    rsDebug("Renderscript result .y", actual.y);
    rsDebug("Renderscript result .z", actual.z);
    rsDebug("Renderscript result .w", actual.w);
    rsDebug("ULP difference .x", dist.x);
    rsDebug("ULP difference .y", dist.y);
    rsDebug("ULP difference .z", dist.z);
    rsDebug("ULP difference .w", dist.w);
}

static bool f1_almost_equal(float a, float b) {
    return float_almost_equal(a, b);
}

static bool f2_almost_equal(float2 a, float2 b) {
    return float_almost_equal(a.x, b.x) && float_almost_equal(a.y, b.y);
}


static bool f3_almost_equal(float3 a, float3 b) {
    return float_almost_equal(a.x, b.x) && float_almost_equal(a.y, b.y)
            && float_almost_equal(a.z, b.z);
}

static bool f4_almost_equal(float4 a, float4 b) {
    return float_almost_equal(a.x, b.x) && float_almost_equal(a.y, b.y)
            && float_almost_equal(a.z, b.z) && float_almost_equal(a.w, b.w);
}

#define TEST_BASIC_FLOAT_OP(op, opName)                 \
temp_f1 = x op y;                                       \
if (! float_almost_equal(temp_f1, result_##opName)) {   \
    fail_f1(x, y , temp_f1, result_##opName, #opName);  \
    failed = true;                                      \
}

#define TEST_FN_FN(func, size)                                                  \
temp_f##size = func(rand_f##size##_0, rand_f##size##_1);                        \
if (! f##size##_almost_equal(temp_f##size , func##_rand_f##size##_f##size)) {   \
    fail_f##size (x, y , temp_f##size, func##_rand_f##size##_f##size, #func);   \
    failed = true;                                                              \
}
#define TEST_FN_F(func, size)                                               \
temp_f##size = func(rand_f##size##_0, rand_f1_1);                           \
if (! f##size##_almost_equal(temp_f##size , func##_rand_f##size##_f1)) {    \
    fail_f##size (x, y , temp_f##size, func##_rand_f##size##_f1 , #func);   \
    failed = true;                                                          \
}

#define TEST_FN_FN_ALL(func)    \
TEST_FN_FN(func, 1)             \
TEST_FN_FN(func, 2)             \
TEST_FN_FN(func, 3)             \
TEST_FN_FN(func, 4)
#define TEST_FN_F_ALL(func) \
TEST_FN_F(func, 2)          \
TEST_FN_F(func, 3)          \
TEST_FN_F(func, 4)

#define TEST_VEC1_VEC1(func, type)                              \
temp_##type##1 = func( rand_##type##1_0, rand_##type##1_1 );    \
if (temp_##type##1 != func##_rand_##type##1_##type##1) {        \
    rsDebug(#func " " #type "1 operation did not match!", 0);   \
    rsDebug("v1", rand_##type##1_0);                            \
    rsDebug("v2", rand_##type##1_1);                            \
    rsDebug("Dalvik result", func##_rand_##type##1_##type##1);  \
    rsDebug("Renderscript result", temp_##type##1);             \
    failed = true;                                              \
}
#define TEST_VEC2_VEC2(func, type)                                      \
temp_##type##2 = func( rand_##type##2_0, rand_##type##2_1 );            \
if (temp_##type##2 .x != func##_rand_##type##2_##type##2 .x             \
        || temp_##type##2 .y != func##_rand_##type##2_##type##2 .y) {   \
    rsDebug(#func " " #type "2 operation did not match!", 0);           \
    rsDebug("v1.x", rand_##type##2_0 .x);                               \
    rsDebug("v1.y", rand_##type##2_0 .y);                               \
    rsDebug("v2.x", rand_##type##2_1 .x);                               \
    rsDebug("v2.y", rand_##type##2_1 .y);                               \
    rsDebug("Dalvik result .x", func##_rand_##type##2_##type##2 .x);    \
    rsDebug("Dalvik result .y", func##_rand_##type##2_##type##2 .y);    \
    rsDebug("Renderscript result .x", temp_##type##2 .x);               \
    rsDebug("Renderscript result .y", temp_##type##2 .y);               \
    failed = true;                                                      \
}
#define TEST_VEC3_VEC3(func, type)                                      \
temp_##type##3 = func( rand_##type##3_0, rand_##type##3_1 );            \
if (temp_##type##3 .x != func##_rand_##type##3_##type##3 .x             \
        || temp_##type##3 .y != func##_rand_##type##3_##type##3 .y      \
        || temp_##type##3 .z != func##_rand_##type##3_##type##3 .z) {   \
    rsDebug(#func " " #type "3 operation did not match!", 0);           \
    rsDebug("v1.x", rand_##type##3_0 .x);                               \
    rsDebug("v1.y", rand_##type##3_0 .y);                               \
    rsDebug("v1.z", rand_##type##3_0 .z);                               \
    rsDebug("v2.x", rand_##type##3_1 .x);                               \
    rsDebug("v2.y", rand_##type##3_1 .y);                               \
    rsDebug("v2.z", rand_##type##3_1 .z);                               \
    rsDebug("Dalvik result .x", func##_rand_##type##3_##type##3 .x);    \
    rsDebug("Dalvik result .y", func##_rand_##type##3_##type##3 .y);    \
    rsDebug("Dalvik result .z", func##_rand_##type##3_##type##3 .z);    \
    rsDebug("Renderscript result .x", temp_##type##3 .x);               \
    rsDebug("Renderscript result .y", temp_##type##3 .y);               \
    rsDebug("Renderscript result .z", temp_##type##3 .z);               \
    failed = true;                                                      \
}
#define TEST_VEC4_VEC4(func, type)                                      \
temp_##type##4 = func( rand_##type##4_0, rand_##type##4_1 );            \
if (temp_##type##4 .x != func##_rand_##type##4_##type##4 .x             \
        || temp_##type##4 .y != func##_rand_##type##4_##type##4 .y      \
        || temp_##type##4 .z != func##_rand_##type##4_##type##4 .z      \
        || temp_##type##4 .w != func##_rand_##type##4_##type##4 .w) {   \
    rsDebug(#func " " #type "4 operation did not match!", 0);           \
    rsDebug("v1.x", rand_##type##4_0 .x);                               \
    rsDebug("v1.y", rand_##type##4_0 .y);                               \
    rsDebug("v1.z", rand_##type##4_0 .z);                               \
    rsDebug("v1.w", rand_##type##4_0 .w);                               \
    rsDebug("v2.x", rand_##type##4_1 .x);                               \
    rsDebug("v2.y", rand_##type##4_1 .y);                               \
    rsDebug("v2.z", rand_##type##4_1 .z);                               \
    rsDebug("v2.w", rand_##type##4_1 .w);                               \
    rsDebug("Dalvik result .x", func##_rand_##type##4_##type##4 .x);    \
    rsDebug("Dalvik result .y", func##_rand_##type##4_##type##4 .y);    \
    rsDebug("Dalvik result .z", func##_rand_##type##4_##type##4 .z);    \
    rsDebug("Dalvik result .w", func##_rand_##type##4_##type##4 .w);    \
    rsDebug("Renderscript result .x", temp_##type##4 .x);               \
    rsDebug("Renderscript result .y", temp_##type##4 .y);               \
    rsDebug("Renderscript result .z", temp_##type##4 .z);               \
    rsDebug("Renderscript result .w", temp_##type##4 .w);               \
    failed = true;                                                      \
}

#define TEST_SC1_SC1(func)  TEST_VEC1_VEC1(func, sc)
#define TEST_SC2_SC2(func)  TEST_VEC2_VEC2(func, sc)
#define TEST_SC3_SC3(func)  TEST_VEC3_VEC3(func, sc)
#define TEST_SC4_SC4(func)  TEST_VEC4_VEC4(func, sc)

#define TEST_UC1_UC1(func)  TEST_VEC1_VEC1(func, uc)
#define TEST_UC2_UC2(func)  TEST_VEC2_VEC2(func, uc)
#define TEST_UC3_UC3(func)  TEST_VEC3_VEC3(func, uc)
#define TEST_UC4_UC4(func)  TEST_VEC4_VEC4(func, uc)

#define TEST_SS1_SS1(func)  TEST_VEC1_VEC1(func, ss)
#define TEST_SS2_SS2(func)  TEST_VEC2_VEC2(func, ss)
#define TEST_SS3_SS3(func)  TEST_VEC3_VEC3(func, ss)
#define TEST_SS4_SS4(func)  TEST_VEC4_VEC4(func, ss)

#define TEST_US1_US1(func)  TEST_VEC1_VEC1(func, us)
#define TEST_US2_US2(func)  TEST_VEC2_VEC2(func, us)
#define TEST_US3_US3(func)  TEST_VEC3_VEC3(func, us)
#define TEST_US4_US4(func)  TEST_VEC4_VEC4(func, us)

#define TEST_SI1_SI1(func)  TEST_VEC1_VEC1(func, si)
#define TEST_SI2_SI2(func)  TEST_VEC2_VEC2(func, si)
#define TEST_SI3_SI3(func)  TEST_VEC3_VEC3(func, si)
#define TEST_SI4_SI4(func)  TEST_VEC4_VEC4(func, si)

#define TEST_UI1_UI1(func)  TEST_VEC1_VEC1(func, ui)
#define TEST_UI2_UI2(func)  TEST_VEC2_VEC2(func, ui)
#define TEST_UI3_UI3(func)  TEST_VEC3_VEC3(func, ui)
#define TEST_UI4_UI4(func)  TEST_VEC4_VEC4(func, ui)

#define TEST_SL1_SL1(func)  TEST_VEC1_VEC1(func, sl)
#define TEST_SL2_SL2(func)  TEST_VEC2_VEC2(func, sl)
#define TEST_SL3_SL3(func)  TEST_VEC3_VEC3(func, sl)
#define TEST_SL4_SL4(func)  TEST_VEC4_VEC4(func, sl)

#define TEST_UL1_UL1(func)  TEST_VEC1_VEC1(func, ul)
#define TEST_UL2_UL2(func)  TEST_VEC2_VEC2(func, ul)
#define TEST_UL3_UL3(func)  TEST_VEC3_VEC3(func, ul)
#define TEST_UL4_UL4(func)  TEST_VEC4_VEC4(func, ul)

#define TEST_SC_SC_ALL(func)    \
TEST_SC1_SC1(func)              \
TEST_SC2_SC2(func)              \
TEST_SC3_SC3(func)              \
TEST_SC4_SC4(func)
#define TEST_UC_UC_ALL(func)    \
TEST_UC1_UC1(func)              \
TEST_UC2_UC2(func)              \
TEST_UC3_UC3(func)              \
TEST_UC4_UC4(func)

#define TEST_SS_SS_ALL(func)    \
TEST_SS1_SS1(func)              \
TEST_SS2_SS2(func)              \
TEST_SS3_SS3(func)              \
TEST_SS4_SS4(func)
#define TEST_US_US_ALL(func)    \
TEST_US1_US1(func)              \
TEST_US2_US2(func)              \
TEST_US3_US3(func)              \
TEST_US4_US4(func)
#define TEST_SI_SI_ALL(func)    \
TEST_SI1_SI1(func)              \
TEST_SI2_SI2(func)              \
TEST_SI3_SI3(func)              \
TEST_SI4_SI4(func)
#define TEST_UI_UI_ALL(func)    \
TEST_UI1_UI1(func)              \
TEST_UI2_UI2(func)              \
TEST_UI3_UI3(func)              \
TEST_UI4_UI4(func)
#define TEST_SL_SL_ALL(func)    \
TEST_SL1_SL1(func)              \
TEST_SL2_SL2(func)              \
TEST_SL3_SL3(func)              \
TEST_SL4_SL4(func)
#define TEST_UL_UL_ALL(func)    \
TEST_UL1_UL1(func)              \
TEST_UL2_UL2(func)              \
TEST_UL3_UL3(func)              \
TEST_UL4_UL4(func)

#define TEST_VEC_VEC_ALL(func)  \
TEST_FN_FN_ALL(func)            \
TEST_SC_SC_ALL(func)            \
TEST_UC_UC_ALL(func)            \
TEST_SS_SS_ALL(func)            \
TEST_US_US_ALL(func)            \
TEST_SI_SI_ALL(func)            \
TEST_UI_UI_ALL(func)

// TODO:  add long types to ALL macro
#if 0
TEST_SL_SL_ALL(func)            \
TEST_UL_UL_ALL(func)
#endif

#define DECLARE_TEMP_SET(type, abbrev)  \
volatile type    temp_##abbrev##1;               \
volatile type##2 temp_##abbrev##2;               \
volatile type##3 temp_##abbrev##3;               \
volatile type##4 temp_##abbrev##4;

#define DECLARE_ALL_TEMP_SETS() \
DECLARE_TEMP_SET(float, f);     \
DECLARE_TEMP_SET(char, sc);     \
DECLARE_TEMP_SET(uchar, uc);    \
DECLARE_TEMP_SET(short, ss);    \
DECLARE_TEMP_SET(ushort, us);   \
DECLARE_TEMP_SET(int, si);      \
DECLARE_TEMP_SET(uint, ui);     \
DECLARE_TEMP_SET(long, sl);     \
DECLARE_TEMP_SET(ulong, ul);

static bool test_math_agree() {
    bool failed = false;

    DECLARE_ALL_TEMP_SETS();

    TEST_BASIC_FLOAT_OP(+, add);
    TEST_BASIC_FLOAT_OP(-, sub);
    TEST_BASIC_FLOAT_OP(*, mul);
    TEST_BASIC_FLOAT_OP(/, div);

    TEST_VEC_VEC_ALL(min);
    TEST_VEC_VEC_ALL(max);
    TEST_FN_FN_ALL(fmin);
    TEST_FN_F_ALL(fmin);
    TEST_FN_FN_ALL(fmax);
    TEST_FN_F_ALL(fmax);

    if (failed) {
        rsDebug("test_math_agree FAILED", 0);
    }
    else {
        rsDebug("test_math_agree PASSED", 0);
    }

    return failed;
}

void math_agree_test() {
    bool failed = false;
    failed |= test_math_agree();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

