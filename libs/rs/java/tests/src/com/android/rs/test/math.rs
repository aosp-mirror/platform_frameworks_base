#include "shared.rsh"

// Testing math library

volatile float f1;
volatile float2 f2;
volatile float3 f3;
volatile float4 f4;

#define TEST_F(fnc, var)            \
    rsDebug("Testing " #fnc, 0);    \
    var##1 = fnc(var##1);           \
    var##2 = fnc(var##2);           \
    var##3 = fnc(var##3);           \
    var##4 = fnc(var##4);

#define TEST_FP(fnc, var)           \
    rsDebug("Testing " #fnc, 0);    \
    var##1 = fnc(var##1, (float*) &f1);  \
    var##2 = fnc(var##2, (float2*) &f2);  \
    var##3 = fnc(var##3, (float3*) &f3);  \
    var##4 = fnc(var##4, (float4*) &f4);

#define TEST_F2(fnc, var)           \
    rsDebug("Testing " #fnc, 0);    \
    var##1 = fnc(var##1, var##1);   \
    var##2 = fnc(var##2, var##2);   \
    var##3 = fnc(var##3, var##3);   \
    var##4 = fnc(var##4, var##4);

static bool test_math(uint32_t index) {
    bool failed = false;
    start();

    TEST_F(cos, f);
    TEST_FP(modf, f);
    TEST_F2(pow, f);
    TEST_F(sin, f);
    TEST_F(sqrt, f);


    float time = end(index);

    if (failed) {
        rsDebug("test_math FAILED", time);
    }
    else {
        rsDebug("test_math PASSED", time);
    }

    return failed;
}

void math_test(uint32_t index, int test_num) {
    bool failed = false;
    failed |= test_math(index);

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

