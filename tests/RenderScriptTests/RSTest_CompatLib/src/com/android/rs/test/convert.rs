#include "shared.rsh"

float4 f4 = { 2.0f, 4.0f, 6.0f, 8.0f };

char4 i8_4 = { -1, -2, -3, 4 };

static bool test_convert() {
    bool failed = false;

    f4 = convert_float4(i8_4);
    _RS_ASSERT(f4.x == -1.0f);
    _RS_ASSERT(f4.y == -2.0f);
    _RS_ASSERT(f4.z == -3.0f);
    _RS_ASSERT(f4.w == 4.0f);

    if (failed) {
        rsDebug("test_convert FAILED", 0);
    }
    else {
        rsDebug("test_convert PASSED", 0);
    }

    return failed;
}

void convert_test() {
    bool failed = false;
    failed |= test_convert();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

