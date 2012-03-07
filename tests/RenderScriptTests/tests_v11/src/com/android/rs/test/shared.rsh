#pragma version(1)

#pragma rs java_package_name(com.android.rs.test_v11)

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

/* These constants must match those in UnitTest.java */
static const int RS_MSG_TEST_PASSED = 100;
static const int RS_MSG_TEST_FAILED = 101;

