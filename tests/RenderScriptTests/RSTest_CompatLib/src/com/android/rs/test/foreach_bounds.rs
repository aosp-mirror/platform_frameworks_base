#include "shared.rsh"

int dimX;
int dimY;
int xStart = 0;
int xEnd = 0;
int yStart = 0;
int yEnd = 0;

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
            rsDebug("i: ", i);
            rsDebug("j: ", j);
            rsDebug("a[j][i]: ", v);
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

void foreach_bounds_test() {
    static bool failed = false;

    rs_script_call_t rssc = {0};
    rssc.strategy = RS_FOR_EACH_STRATEGY_DONT_CARE;
    rssc.xStart = xStart;
    rssc.xEnd = xEnd;
    rssc.yStart = yStart;
    rssc.yEnd = yEnd;

    rsForEach(s, ain, aout, NULL, 0, &rssc);

    failed |= test_root_output();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

