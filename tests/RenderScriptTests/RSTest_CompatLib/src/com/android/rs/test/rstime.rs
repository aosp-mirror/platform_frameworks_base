#include "shared.rsh"

static bool basic_test(uint32_t index) {
    bool failed = false;

    rs_time_t curTime = rsTime(0);
    rs_tm tm;
    rsDebug("curTime", curTime);

    rsLocaltime(&tm, &curTime);

    rsDebug("tm.tm_sec", tm.tm_sec);
    rsDebug("tm.tm_min", tm.tm_min);
    rsDebug("tm.tm_hour", tm.tm_hour);
    rsDebug("tm.tm_mday", tm.tm_mday);
    rsDebug("tm.tm_mon", tm.tm_mon);
    rsDebug("tm.tm_year", tm.tm_year);
    rsDebug("tm.tm_wday", tm.tm_wday);
    rsDebug("tm.tm_yday", tm.tm_yday);
    rsDebug("tm.tm_isdst", tm.tm_isdst);

    // Test a specific time (since we set America/Los_Angeles localtime)
    curTime = 1294438893;
    rsLocaltime(&tm, &curTime);

    _RS_ASSERT(tm.tm_sec == 33);
    _RS_ASSERT(tm.tm_min == 21);
    _RS_ASSERT(tm.tm_hour == 14);
    _RS_ASSERT(tm.tm_mday == 7);
    _RS_ASSERT(tm.tm_mon == 0);
    _RS_ASSERT(tm.tm_year == 111);
    _RS_ASSERT(tm.tm_wday == 5);
    _RS_ASSERT(tm.tm_yday == 6);
    _RS_ASSERT(tm.tm_isdst == 0);

    return failed;
}

void test_rstime(uint32_t index, int test_num) {
    bool failed = false;
    failed |= basic_test(index);

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
        rsDebug("rstime_test FAILED", -1);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
        rsDebug("rstime_test PASSED", 0);
    }
}

