#include "shared.rsh"

// Testing atomic operations
static bool testUMax(uint32_t dst, uint32_t src) {
    bool failed = false;
    uint32_t old = dst;
    uint32_t expect = (dst > src ? dst : src);
    uint32_t ret = rsAtomicMax(&dst, src);
    _RS_ASSERT(old == ret);
    _RS_ASSERT(dst == expect);
    return failed;
}

static bool testUMin(uint32_t dst, uint32_t src) {
    bool failed = false;
    uint32_t old = dst;
    uint32_t expect = (dst < src ? dst : src);
    uint32_t ret = rsAtomicMin(&dst, src);
    _RS_ASSERT(old == ret);
    _RS_ASSERT(dst == expect);
    return failed;
}

static bool testUCas(uint32_t dst, uint32_t cmp, uint32_t swp) {
    bool failed = false;
    uint32_t old = dst;
    uint32_t expect = (dst == cmp ? swp : dst);
    uint32_t ret = rsAtomicCas(&dst, cmp, swp);
    _RS_ASSERT(old == ret);
    _RS_ASSERT(dst == expect);
    return failed;
}

static bool test_atomics() {
    bool failed = false;

    failed |= testUMax(5, 6);
    failed |= testUMax(6, 5);
    failed |= testUMax(5, 0xf0000006);
    failed |= testUMax(0xf0000006, 5);

    failed |= testUMin(5, 6);
    failed |= testUMin(6, 5);
    failed |= testUMin(5, 0xf0000006);
    failed |= testUMin(0xf0000006, 5);

    failed |= testUCas(4, 4, 5);
    failed |= testUCas(4, 5, 5);
    failed |= testUCas(5, 5, 4);
    failed |= testUCas(5, 4, 4);
    failed |= testUCas(0xf0000004, 0xf0000004, 0xf0000005);
    failed |= testUCas(0xf0000004, 0xf0000005, 0xf0000005);
    failed |= testUCas(0xf0000005, 0xf0000005, 0xf0000004);
    failed |= testUCas(0xf0000005, 0xf0000004, 0xf0000004);

    if (failed) {
        rsDebug("test_atomics FAILED", 0);
    }
    else {
        rsDebug("test_atomics PASSED", 0);
    }

    return failed;
}

void atomic_test() {
    bool failed = false;
    failed |= test_atomics();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

