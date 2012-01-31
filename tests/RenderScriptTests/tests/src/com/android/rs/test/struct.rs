#include "shared.rsh"

typedef struct Point2 {
   int x;
   int y;
} Point_2;
Point_2 *point2;

static bool test_Point_2(int expected) {
    bool failed = false;

    rsDebug("Point: ", point2[0].x, point2[0].y);
    _RS_ASSERT(point2[0].x == expected);
    _RS_ASSERT(point2[0].y == expected);

    if (failed) {
        rsDebug("test_Point_2 FAILED", 0);
    }
    else {
        rsDebug("test_Point_2 PASSED", 0);
    }

    return failed;
}

void struct_test(int expected) {
    bool failed = false;
    failed |= test_Point_2(expected);

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

