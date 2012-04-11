#pragma version(1)

#pragma rs java_package_name(com.android.rs.test)

typedef struct TestResult_s {
    rs_allocation name;
    bool pass;
    float score;
    int64_t time;
} TestResult;
//TestResult *g_results;

static int64_t g_time;

static void start(void) {
    g_time = rsUptimeMillis();
}

static float end(uint32_t idx) {
    int64_t t = rsUptimeMillis() - g_time;
    //g_results[idx].time = t;
    //rsDebug("test time", (int)t);
    return ((float)t) / 1000.f;
}

#define _RS_ASSERT(b) \
do { \
    if (!(b)) { \
        failed = true; \
        rsDebug(#b " FAILED", 0); \
    } \
\
} while (0)

static const int iposinf = 0x7f800000;
static const int ineginf = 0xff800000;

static const float posinf() {
    float f = *((float*)&iposinf);
    return f;
}

static const float neginf() {
    float f = *((float*)&ineginf);
    return f;
}

static bool isposinf(float f) {
    int i = *((int*)(void*)&f);
    return (i == iposinf);
}

static bool isneginf(float f) {
    int i = *((int*)(void*)&f);
    return (i == ineginf);
}

static bool isnan(float f) {
    int i = *((int*)(void*)&f);
    return (((i & 0x7f800000) == 0x7f800000) && (i & 0x007fffff));
}

static bool isposzero(float f) {
    int i = *((int*)(void*)&f);
    return (i == 0x00000000);
}

static bool isnegzero(float f) {
    int i = *((int*)(void*)&f);
    return (i == 0x80000000);
}

static bool iszero(float f) {
    return isposzero(f) || isnegzero(f);
}

/* These constants must match those in UnitTest.java */
static const int RS_MSG_TEST_PASSED = 100;
static const int RS_MSG_TEST_FAILED = 101;

