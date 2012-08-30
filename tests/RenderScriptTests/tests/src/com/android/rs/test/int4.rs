#include "shared.rsh"
#pragma rs_fp_relaxed

uchar4 u4 = 4;
int4 gi4 = {2, 2, 2, 2};

void int4_test() {
    bool failed = false;
    int4 i4 = {u4.x, u4.y, u4.z, u4.w};
    i4 *= gi4;

    rsDebug("i4.x", i4.x);
    rsDebug("i4.y", i4.y);
    rsDebug("i4.z", i4.z);
    rsDebug("i4.w", i4.w);

    _RS_ASSERT(i4.x == 8);
    _RS_ASSERT(i4.y == 8);
    _RS_ASSERT(i4.z == 8);
    _RS_ASSERT(i4.w == 8);

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

