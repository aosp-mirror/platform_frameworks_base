#include "shared.rsh"

// Testing reference counting of RS object types

rs_allocation globalA;
static rs_allocation staticGlobalA;

void refcount_test() {
    staticGlobalA = globalA;
    rsClearObject(&globalA);
    rsSendToClientBlocking(RS_MSG_TEST_PASSED);
}

