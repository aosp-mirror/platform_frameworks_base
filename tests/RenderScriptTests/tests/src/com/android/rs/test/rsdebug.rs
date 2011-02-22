#include "shared.rsh"

// Testing primitive types
float floatTest = 1.99f;
double doubleTest = 2.05;
char charTest = -8;
short shortTest = -16;
int intTest = -32;
long longTest = 17179869184l; // 1 << 34
long long longlongTest = 68719476736l; // 1 << 36

uchar ucharTest = 8;
ushort ushortTest = 16;
uint uintTest = 32;
ulong ulongTest = 4611686018427387904L;
int64_t int64_tTest = -17179869184l; // - 1 << 34
uint64_t uint64_tTest = 117179869184l;

static bool basic_test(uint32_t index) {
    bool failed = false;

    // This test focuses primarily on compilation-time, not run-time.
    // For this reason, none of the outputs are actually checked.

    rsDebug("floatTest", floatTest);
    rsDebug("doubleTest", doubleTest);
    rsDebug("charTest", charTest);
    rsDebug("shortTest", shortTest);
    rsDebug("intTest", intTest);
    rsDebug("longTest", longTest);
    rsDebug("longlongTest", longlongTest);

    rsDebug("ucharTest", ucharTest);
    rsDebug("ushortTest", ushortTest);
    rsDebug("uintTest", uintTest);
    rsDebug("ulongTest", ulongTest);
    rsDebug("int64_tTest", int64_tTest);
    rsDebug("uint64_tTest", uint64_tTest);

    return failed;
}

void test_rsdebug(uint32_t index, int test_num) {
    bool failed = false;
    failed |= basic_test(index);

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
        rsDebug("rsdebug_test FAILED", -1);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
        rsDebug("rsdebug_test PASSED", 0);
    }
}

