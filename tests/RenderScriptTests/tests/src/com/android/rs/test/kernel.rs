#include "shared.rsh"

int *ain;
int *aout;
int dimX;
static bool failed = false;

void init_vars(int *out) {
    *out = 7;
}


int __attribute__((kernel)) root(int ain, uint32_t x) {
    _RS_ASSERT(ain == 7);
    return ain + x;
}

static bool test_root_output() {
    bool failed = false;
    int i;

    for (i = 0; i < dimX; i++) {
        _RS_ASSERT(aout[i] == (i + ain[i]));
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

void kernel_test() {
    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}
