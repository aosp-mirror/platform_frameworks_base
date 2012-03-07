#include "shared.rsh"

int *a;
int dimX;
int dimY;

void root(int *out, uint32_t x, uint32_t y) {
    *out = x + y * dimX;
}

static bool test_foreach_output() {
    bool failed = false;
    int i, j;

    for (j = 0; j < dimY; j++) {
        for (i = 0; i < dimX; i++) {
            _RS_ASSERT(a[i + j * dimX] == (i + j * dimX));
        }
    }

    if (failed) {
        rsDebug("test_foreach_output FAILED", 0);
    }
    else {
        rsDebug("test_foreach_output PASSED", 0);
    }

    return failed;
}

void foreach_test() {
    bool failed = false;
    failed |= test_foreach_output();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

