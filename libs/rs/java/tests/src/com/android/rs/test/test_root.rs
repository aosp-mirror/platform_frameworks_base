// Fountain test script
#pragma version(1)

#pragma rs java_package_name(com.android.rs.test)

#pragma stateFragment(parent)

#include "rs_graphics.rsh"


typedef struct TestResult {
    rs_allocation name;
    bool pass;
    float score;
} TestResult_t;
TestResult_t *results;

#pragma rs export_var(results)
//#pragma rs export_func(addParticles)

int root() {

    return 0;
}


