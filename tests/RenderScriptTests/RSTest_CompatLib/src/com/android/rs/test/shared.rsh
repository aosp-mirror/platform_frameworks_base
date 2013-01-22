#pragma version(1)

#pragma rs java_package_name(com.android.rs.test_compat)

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

/* Absolute epsilon used for floats.  Value is similar to float.h. */
#ifndef FLT_EPSILON
#define FLT_EPSILON 1.19e7f
#endif
/* Max ULPs while still being considered "equal".  Only used when this number
   of ULPs is of a greater size than FLT_EPSILON. */
#define FLT_MAX_ULP 1

/* Calculate the difference in ULPs between the two values.  (Return zero on
   perfect equality.) */
static int float_dist(float f1, float f2) {
    return *((int *)(&f1)) - *((int *)(&f2));
}

/* Check if two floats are essentially equal.  Will fail with some values
   due to design.  (Validate using FLT_EPSILON or similar if necessary.) */
static bool float_almost_equal(float f1, float f2) {
    int *i1 = (int*)(&f1);
    int *i2 = (int*)(&f2);

    // Check for sign equality
    if ( ((*i1 >> 31) == 0) != ((*i2 >> 31) == 0) ) {
        // Handle signed zeroes
        if (f1 == f2)
            return true;
        return false;
    }

    // Check with ULP distance
    if (float_dist(f1, f2) > FLT_MAX_ULP)
        return false;
    return true;
}

/* These constants must match those in UnitTest.java */
static const int RS_MSG_TEST_PASSED = 100;
static const int RS_MSG_TEST_FAILED = 101;

