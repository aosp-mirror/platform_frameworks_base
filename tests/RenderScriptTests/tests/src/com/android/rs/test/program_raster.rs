#include "shared.rsh"
#include "rs_graphics.rsh"

rs_program_raster pointSpriteEnabled;
rs_program_raster cullMode;

static bool test_program_raster_getters() {
    bool failed = false;

    _RS_ASSERT(rsProgramRasterGetPointSpriteEnabled(pointSpriteEnabled) == true);
    _RS_ASSERT(rsProgramRasterGetCullMode(pointSpriteEnabled) == RS_CULL_BACK);

    _RS_ASSERT(rsProgramRasterGetPointSpriteEnabled(cullMode) == false);
    _RS_ASSERT(rsProgramRasterGetCullMode(cullMode) == RS_CULL_FRONT);

    if (failed) {
        rsDebug("test_program_raster_getters FAILED", 0);
    }
    else {
        rsDebug("test_program_raster_getters PASSED", 0);
    }

    return failed;
}

void program_raster_test() {
    bool failed = false;
    failed |= test_program_raster_getters();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

