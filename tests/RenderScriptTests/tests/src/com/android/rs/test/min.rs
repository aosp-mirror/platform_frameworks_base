#include "shared.rsh"
#pragma rs_fp_relaxed

volatile uchar2 res_uc_2 = 1;
volatile uchar2 src1_uc_2 = 1;
volatile uchar2 src2_uc_2 = 1;

void min_test() {
    bool failed = false;

    res_uc_2 = min(src1_uc_2, src2_uc_2);

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

