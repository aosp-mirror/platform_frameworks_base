#include "shared.rsh"

int dimX;
int dimY;
int xStart = 0;
int xEnd = 0;
int yStart = 0;
int yEnd = 0;

static bool failed = false;

rs_script s;
rs_allocation aRaw;
rs_allocation ain;
rs_allocation aout;

void root(int *out, uint32_t x, uint32_t y) {
    *out = x + y * dimX;
}

int __attribute__((kernel)) zero() {
    return 0;
}

static bool test_root_output() {
    bool failed = false;
    int i, j;

    for (j = 0; j < dimY; j++) {
        for (i = 0; i < dimX; i++) {
            int v = rsGetElementAt_int(aRaw, i, j);
            if (i < xStart || i >= xEnd || j < yStart || j >= yEnd) {
                _RS_ASSERT(v == 0);
            } else {
                _RS_ASSERT(v == (i + j * dimX));
            }
        }
    }

    if (failed) {
        rsDebug("test_root_output FAILED", 0);
    }
    else {
        rsDebug("test_root_output PASSED", 0);
    }

    return failed;
}

void verify_root() {
    failed |= test_root_output();
}

void foreach_bounds_test() {
    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

