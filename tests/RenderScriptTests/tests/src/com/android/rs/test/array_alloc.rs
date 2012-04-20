#include "shared.rsh"

const int dimX = 20;
rs_allocation a[dimX];

void array_alloc_test() {
    bool failed = false;

    for (int i = 0; i < dimX; i++) {
        rsDebug("i: ", i);
        _RS_ASSERT(rsAllocationGetDimX(a[i]) == 1);
    }

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

