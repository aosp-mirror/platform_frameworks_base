#include "shared.rsh"
//#pragma rs_fp_relaxed

float x = 0.0f;
float y = 0.0f;
float result_add = 0.0f;
float result_sub = 0.0f;
float result_mul = 0.0f;
float result_div = 0.0f;

#define TEST_OP(op, opName)                                             \
result = x op y;                                                        \
if (! float_almost_equal(result, result_##opName)) {                    \
    rsDebug(#opName " did not match!", 0);                              \
    rsDebug("x = ", x);                                                 \
    rsDebug("y = ", y);                                                 \
    rsDebug("Result = ", result);                                       \
    rsDebug("Expected = ", result_##opName);                            \
    rsDebug("Difference = ", result - result_##opName);                 \
    rsDebug("ULP Difference =", float_dist(result, result_##opName));   \
    failed = true;                                                      \
}

static bool test_math_agree() {
    bool failed = false;
    float result = 0.0;

    TEST_OP(+, add);
    TEST_OP(-, sub);
    TEST_OP(*, mul);
    TEST_OP(/, div);

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

