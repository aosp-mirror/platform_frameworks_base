#include "shared.rsh"

// Testing math conformance

static bool test_rootn() {
    bool failed = false;

    // rootn(x, 0) -> NaN
    _RS_ASSERT(isnan(rootn(1.0f, 0)));

    // rootn(+/-0, n) -> +/-inf for odd n < 0
    _RS_ASSERT(isposinf(rootn(0.f, -3)));
    _RS_ASSERT(isneginf(rootn(-0.f, -3)));

    // rootn(+/-0, n) -> +inf for even n < 0
    _RS_ASSERT(isposinf(rootn(0.f, -8)));
    _RS_ASSERT(isposinf(rootn(-0.f, -8)));

    // rootn(+/-0, n) -> +/-0 for odd n > 0
    _RS_ASSERT(isposzero(rootn(0.f, 3)));
    _RS_ASSERT(isnegzero(rootn(-0.f, 3)));

    // rootn(+/-0, n) -> +0 for even n > 0
    _RS_ASSERT(isposzero(rootn(0.f, 8)));
    _RS_ASSERT(isposzero(rootn(-0.f, 8)));

    // rootn(x, n) -> NaN for x < 0 and even n
    _RS_ASSERT(isnan(rootn(-10000.f, -4)));
    _RS_ASSERT(isnan(rootn(-10000.f, 4)));

    // rootn(x, n) -> value for x < 0 and odd n
    _RS_ASSERT(!isnan(rootn(-10000.f, -3)));
    _RS_ASSERT(!isnan(rootn(-10000.f, 3)));

    if (failed) {
        rsDebug("test_rootn FAILED", -1);
    }
    else {
        rsDebug("test_rootn PASSED", 0);
    }

    return failed;
}

void math_conformance_test() {
    bool failed = false;
    failed |= test_rootn();

    if (failed) {
        rsDebug("math_conformance_test FAILED", -1);
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsDebug("math_conformance_test PASSED", 0);
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}
