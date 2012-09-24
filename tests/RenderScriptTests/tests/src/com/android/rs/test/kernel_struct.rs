#include "shared.rsh"

struct simpleStruct {
    int i1;
    char ignored1;
    float f1;
    int i2;
    char ignored2;
    float f2;
};

struct simpleStruct *ain;
struct simpleStruct *aout;
int dimX;
static bool failed = false;

void init_vars(struct simpleStruct *out, uint32_t x) {
    out->i1 = 0;
    out->f1 = 0.f;
    out->i2 = 1;
    out->f2 = 1.0f;
}

struct simpleStruct __attribute__((kernel))
        root(struct simpleStruct in, uint32_t x) {
    struct simpleStruct s;
    s.i1 = in.i1 + x;
    s.f1 = in.f1 + x;
    s.i2 = in.i2 + x;
    s.f2 = in.f2 + x;
    return s;
}

static bool test_root_output() {
    bool failed = false;
    int i;

    for (i = 0; i < dimX; i++) {
        _RS_ASSERT(aout[i].i1 == (i + ain[i].i1));
        _RS_ASSERT(aout[i].f1 == (i + ain[i].f1));
        _RS_ASSERT(aout[i].i2 == (i + ain[i].i2));
        _RS_ASSERT(aout[i].f2 == (i + ain[i].f2));
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

void kernel_struct_test() {
    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}
