#include "shared.rsh"

int *a;
int dimX;
int dimY;
static bool failed = false;

void foo(const int *in, int *out, uint32_t x, uint32_t y) {
    *out = 99 + x + y * dimX;
}

static bool test_foo_output() {
    bool failed = false;
    int i, j;

    for (j = 0; j < dimY; j++) {
        for (i = 0; i < dimX; i++) {
            _RS_ASSERT(a[i + j * dimX] == (99 + i + j * dimX));
        }
    }

    if (failed) {
        rsDebug("test_foo_output FAILED", 0);
    }
    else {
        rsDebug("test_foo_output PASSED", 0);
    }

    return failed;
}

void verify_foo() {
    failed |= test_foo_output();
}

void noroot_test() {
    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

