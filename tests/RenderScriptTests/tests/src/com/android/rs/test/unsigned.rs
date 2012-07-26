#include "shared.rsh"

// Testing unsigned types for Bug 6764163
unsigned int ui = 37;
unsigned char uc = 5;

static bool test_unsigned() {
    bool failed = false;

    rsDebug("ui", ui);
    rsDebug("uc", uc);
    _RS_ASSERT(ui == 0x7fffffff);
    _RS_ASSERT(uc == 129);

    if (failed) {
        rsDebug("test_unsigned FAILED", -1);
    }
    else {
        rsDebug("test_unsigned PASSED", 0);
    }

    return failed;
}

void unsigned_test() {
    bool failed = false;
    failed |= test_unsigned();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

