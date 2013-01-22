#include "shared.rsh"
#include "rs_graphics.rsh"

rs_element elementTest;
rs_type typeTest;
rs_allocation allocationTest;
rs_sampler samplerTest;
rs_script scriptTest;

rs_matrix4x4 matrix4x4Test;
rs_matrix3x3 matrix3x3Test;
rs_matrix2x2 matrix2x2Test;

struct my_struct {
    int i;
    rs_allocation banana;
};

static bool basic_test(uint32_t index) {
    bool failed = false;

    rs_matrix4x4 matrix4x4TestLocal;
    rs_matrix3x3 matrix3x3TestLocal;
    rs_matrix2x2 matrix2x2TestLocal;

    // This test focuses primarily on compilation-time, not run-time.
    rs_element elementTestLocal;
    rs_type typeTestLocal;
    rs_allocation allocationTestLocal;
    rs_sampler samplerTestLocal;
    rs_script scriptTestLocal;

    struct my_struct structTest;

    //allocationTestLocal = allocationTest;

    //allocationTest = allocationTestLocal;

    /*for (int i = 0; i < 4; i++) {
        fontTestLocalArray[i] = fontTestLocal;
    }*/

    /*fontTest = fontTestLocalArray[3];*/

    return failed;
}

void test_rstypes(uint32_t index, int test_num) {
    bool failed = false;
    failed |= basic_test(index);

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
        rsDebug("rstypes_test FAILED", -1);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
        rsDebug("rstypes_test PASSED", 0);
    }
}

