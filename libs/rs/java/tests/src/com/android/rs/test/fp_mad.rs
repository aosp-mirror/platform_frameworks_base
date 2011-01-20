#include "shared.rsh"

const int TEST_COUNT = 1;

static float data_f1[1025];
static float4 data_f4[1025];

static void test_mad4(uint32_t index) {
    start();

    float total = 0;
    // Do ~1 billion ops
    for (int ct=0; ct < 1000 * (1000 / 80); ct++) {
        for (int i=0; i < (1000); i++) {
            data_f4[i] = (data_f4[i] * 0.02f +
                          data_f4[i+1] * 0.04f +
                          data_f4[i+2] * 0.05f +
                          data_f4[i+3] * 0.1f +
                          data_f4[i+4] * 0.2f +
                          data_f4[i+5] * 0.2f +
                          data_f4[i+6] * 0.1f +
                          data_f4[i+7] * 0.05f +
                          data_f4[i+8] * 0.04f +
                          data_f4[i+9] * 0.02f + 1.f);
        }
    }

    float time = end(index);
    rsDebug("fp_mad4 M ops", 1000.f / time);
}

static void test_mad(uint32_t index) {
    start();

    float total = 0;
    // Do ~1 billion ops
    for (int ct=0; ct < 1000 * (1000 / 20); ct++) {
        for (int i=0; i < (1000); i++) {
            data_f1[i] = (data_f1[i] * 0.02f +
                          data_f1[i+1] * 0.04f +
                          data_f1[i+2] * 0.05f +
                          data_f1[i+3] * 0.1f +
                          data_f1[i+4] * 0.2f +
                          data_f1[i+5] * 0.2f +
                          data_f1[i+6] * 0.1f +
                          data_f1[i+7] * 0.05f +
                          data_f1[i+8] * 0.04f +
                          data_f1[i+9] * 0.02f + 1.f);
        }
    }

    float time = end(index);
    rsDebug("fp_mad M ops", 1000.f / time);
}

static void test_norm(uint32_t index) {
    start();

    float total = 0;
    // Do ~10 M ops
    for (int ct=0; ct < 1000 * 10; ct++) {
        for (int i=0; i < (1000); i++) {
            data_f4[i] = normalize(data_f4[i]);
        }
    }

    float time = end(index);
    rsDebug("fp_norm M ops", 10.f / time);
}

static void test_sincos4(uint32_t index) {
    start();

    float total = 0;
    // Do ~10 M ops
    for (int ct=0; ct < 1000 * 10 / 4; ct++) {
        for (int i=0; i < (1000); i++) {
            data_f4[i] = sin(data_f4[i]) * cos(data_f4[i]);
        }
    }

    float time = end(index);
    rsDebug("fp_sincos4 M ops", 10.f / time);
}

static void test_sincos(uint32_t index) {
    start();

    float total = 0;
    // Do ~10 M ops
    for (int ct=0; ct < 1000 * 10; ct++) {
        for (int i=0; i < (1000); i++) {
            data_f1[i] = sin(data_f1[i]) * cos(data_f1[i]);
        }
    }

    float time = end(index);
    rsDebug("fp_sincos M ops", 10.f / time);
}

static void test_clamp(uint32_t index) {
    start();

    // Do ~100 M ops
    for (int ct=0; ct < 1000 * 100; ct++) {
        for (int i=0; i < (1000); i++) {
            data_f1[i] = clamp(data_f1[i], -1.f, 1.f);
        }
    }

    float time = end(index);
    rsDebug("fp_clamp M ops", 100.f / time);

    start();
    // Do ~100 M ops
    for (int ct=0; ct < 1000 * 100; ct++) {
        for (int i=0; i < (1000); i++) {
            if (data_f1[i] < -1.f) data_f1[i] = -1.f;
            if (data_f1[i] > -1.f) data_f1[i] = 1.f;
        }
    }

    time = end(index);
    rsDebug("fp_clamp ref M ops", 100.f / time);
}

static void test_clamp4(uint32_t index) {
    start();

    float total = 0;
    // Do ~100 M ops
    for (int ct=0; ct < 1000 * 100 /4; ct++) {
        for (int i=0; i < (1000); i++) {
            data_f4[i] = clamp(data_f4[i], -1.f, 1.f);
        }
    }

    float time = end(index);
    rsDebug("fp_clamp4 M ops", 100.f / time);
}

void fp_mad_test(uint32_t index, int test_num) {
    int x;
    for (x=0; x < 1025; x++) {
        data_f1[x] = (x & 0xf) * 0.1f;
        data_f4[x].x = (x & 0xf) * 0.1f;
        data_f4[x].y = (x & 0xf0) * 0.1f;
        data_f4[x].z = (x & 0x33) * 0.1f;
        data_f4[x].w = (x & 0x77) * 0.1f;
    }

    test_mad4(index);
    test_mad(index);

    for (x=0; x < 1025; x++) {
        data_f1[x] = (x & 0xf) * 0.1f + 1.f;
        data_f4[x].x = (x & 0xf) * 0.1f + 1.f;
        data_f4[x].y = (x & 0xf0) * 0.1f + 1.f;
        data_f4[x].z = (x & 0x33) * 0.1f + 1.f;
        data_f4[x].w = (x & 0x77) * 0.1f + 1.f;
    }

    test_norm(index);
    test_sincos4(index);
    test_sincos(index);
    test_clamp4(index);
    test_clamp(index);

    // TODO Actually verify test result accuracy
    rsDebug("fp_mad_test PASSED", 0);
    rsSendToClientBlocking(RS_MSG_TEST_PASSED);
}


