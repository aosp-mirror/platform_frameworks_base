#include "shared.rsh"
#include "rs_graphics.rsh"

rs_program_store ditherEnable;
rs_program_store colorRWriteEnable;
rs_program_store colorGWriteEnable;
rs_program_store colorBWriteEnable;
rs_program_store colorAWriteEnable;
rs_program_store blendSrc;
rs_program_store blendDst;
rs_program_store depthWriteEnable;
rs_program_store depthFunc;

static bool test_program_store_getters() {
    bool failed = false;

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(depthFunc) == RS_DEPTH_FUNC_GREATER);
    _RS_ASSERT(rsgProgramStoreIsDepthMaskEnabled(depthFunc) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskRedEnabled(depthFunc) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskGreenEnabled(depthFunc) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskBlueEnabled(depthFunc) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskAlphaEnabled( depthFunc) == false);
    _RS_ASSERT(rsgProgramStoreIsDitherEnabled(depthFunc) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(depthFunc) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(depthFunc) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(depthWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreIsDepthMaskEnabled(depthWriteEnable) == true);
    _RS_ASSERT(rsgProgramStoreIsColorMaskRedEnabled(depthWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskGreenEnabled(depthWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskBlueEnabled(depthWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskAlphaEnabled( depthWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsDitherEnabled(depthWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(depthWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(depthWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(colorRWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreIsDepthMaskEnabled(colorRWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskRedEnabled(colorRWriteEnable) == true);
    _RS_ASSERT(rsgProgramStoreIsColorMaskGreenEnabled(colorRWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskBlueEnabled(colorRWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskAlphaEnabled( colorRWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsDitherEnabled(colorRWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(colorRWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(colorRWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(colorGWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreIsDepthMaskEnabled(colorGWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskRedEnabled(colorGWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskGreenEnabled(colorGWriteEnable) == true);
    _RS_ASSERT(rsgProgramStoreIsColorMaskBlueEnabled(colorGWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskAlphaEnabled( colorGWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsDitherEnabled(colorGWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(colorGWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(colorGWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(colorBWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreIsDepthMaskEnabled(colorBWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskRedEnabled(colorBWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskGreenEnabled(colorBWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskBlueEnabled(colorBWriteEnable) == true);
    _RS_ASSERT(rsgProgramStoreIsColorMaskAlphaEnabled( colorBWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsDitherEnabled(colorBWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(colorBWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(colorBWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(colorAWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreIsDepthMaskEnabled(colorAWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskRedEnabled(colorAWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskGreenEnabled(colorAWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskBlueEnabled(colorAWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskAlphaEnabled( colorAWriteEnable) == true);
    _RS_ASSERT(rsgProgramStoreIsDitherEnabled(colorAWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(colorAWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(colorAWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(ditherEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreIsDepthMaskEnabled(ditherEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskRedEnabled(ditherEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskGreenEnabled(ditherEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskBlueEnabled(ditherEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskAlphaEnabled( ditherEnable) == false);
    _RS_ASSERT(rsgProgramStoreIsDitherEnabled(ditherEnable) == true);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(ditherEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(ditherEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(blendSrc) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreIsDepthMaskEnabled(blendSrc) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskRedEnabled(blendSrc) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskGreenEnabled(blendSrc) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskBlueEnabled(blendSrc) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskAlphaEnabled( blendSrc) == false);
    _RS_ASSERT(rsgProgramStoreIsDitherEnabled(blendSrc) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(blendSrc) == RS_BLEND_SRC_DST_COLOR);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(blendSrc) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(blendDst) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreIsDepthMaskEnabled(blendDst) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskRedEnabled(blendDst) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskGreenEnabled(blendDst) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskBlueEnabled(blendDst) == false);
    _RS_ASSERT(rsgProgramStoreIsColorMaskAlphaEnabled( blendDst) == false);
    _RS_ASSERT(rsgProgramStoreIsDitherEnabled(blendDst) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(blendDst) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(blendDst) == RS_BLEND_DST_DST_ALPHA);

    if (failed) {
        rsDebug("test_program_store_getters FAILED", 0);
    }
    else {
        rsDebug("test_program_store_getters PASSED", 0);
    }

    return failed;
}

void program_store_test() {
    bool failed = false;
    failed |= test_program_store_getters();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

