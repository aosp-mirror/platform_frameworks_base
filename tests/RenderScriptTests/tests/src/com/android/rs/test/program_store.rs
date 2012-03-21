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

    _RS_ASSERT(rsProgramStoreGetDepthFunc(depthFunc) == RS_DEPTH_FUNC_GREATER);
    _RS_ASSERT(rsProgramStoreGetDepthMask(depthFunc) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskR(depthFunc) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskG(depthFunc) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskB(depthFunc) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskA(depthFunc) == false);
    _RS_ASSERT(rsProgramStoreGetDitherEnabled(depthFunc) == false);
    _RS_ASSERT(rsProgramStoreGetBlendSrcFunc(depthFunc) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsProgramStoreGetBlendDstFunc(depthFunc) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsProgramStoreGetDepthFunc(depthWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsProgramStoreGetDepthMask(depthWriteEnable) == true);
    _RS_ASSERT(rsProgramStoreGetColorMaskR(depthWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskG(depthWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskB(depthWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskA(depthWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetDitherEnabled(depthWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetBlendSrcFunc(depthWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsProgramStoreGetBlendDstFunc(depthWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsProgramStoreGetDepthFunc(colorRWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsProgramStoreGetDepthMask(colorRWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskR(colorRWriteEnable) == true);
    _RS_ASSERT(rsProgramStoreGetColorMaskG(colorRWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskB(colorRWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskA(colorRWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetDitherEnabled(colorRWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetBlendSrcFunc(colorRWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsProgramStoreGetBlendDstFunc(colorRWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsProgramStoreGetDepthFunc(colorGWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsProgramStoreGetDepthMask(colorGWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskR(colorGWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskG(colorGWriteEnable) == true);
    _RS_ASSERT(rsProgramStoreGetColorMaskB(colorGWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskA(colorGWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetDitherEnabled(colorGWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetBlendSrcFunc(colorGWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsProgramStoreGetBlendDstFunc(colorGWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsProgramStoreGetDepthFunc(colorBWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsProgramStoreGetDepthMask(colorBWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskR(colorBWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskG(colorBWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskB(colorBWriteEnable) == true);
    _RS_ASSERT(rsProgramStoreGetColorMaskA(colorBWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetDitherEnabled(colorBWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetBlendSrcFunc(colorBWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsProgramStoreGetBlendDstFunc(colorBWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsProgramStoreGetDepthFunc(colorAWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsProgramStoreGetDepthMask(colorAWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskR(colorAWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskG(colorAWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskB(colorAWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskA(colorAWriteEnable) == true);
    _RS_ASSERT(rsProgramStoreGetDitherEnabled(colorAWriteEnable) == false);
    _RS_ASSERT(rsProgramStoreGetBlendSrcFunc(colorAWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsProgramStoreGetBlendDstFunc(colorAWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsProgramStoreGetDepthFunc(ditherEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsProgramStoreGetDepthMask(ditherEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskR(ditherEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskG(ditherEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskB(ditherEnable) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskA(ditherEnable) == false);
    _RS_ASSERT(rsProgramStoreGetDitherEnabled(ditherEnable) == true);
    _RS_ASSERT(rsProgramStoreGetBlendSrcFunc(ditherEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsProgramStoreGetBlendDstFunc(ditherEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsProgramStoreGetDepthFunc(blendSrc) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsProgramStoreGetDepthMask(blendSrc) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskR(blendSrc) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskG(blendSrc) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskB(blendSrc) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskA(blendSrc) == false);
    _RS_ASSERT(rsProgramStoreGetDitherEnabled(blendSrc) == false);
    _RS_ASSERT(rsProgramStoreGetBlendSrcFunc(blendSrc) == RS_BLEND_SRC_DST_COLOR);
    _RS_ASSERT(rsProgramStoreGetBlendDstFunc(blendSrc) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsProgramStoreGetDepthFunc(blendDst) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsProgramStoreGetDepthMask(blendDst) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskR(blendDst) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskG(blendDst) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskB(blendDst) == false);
    _RS_ASSERT(rsProgramStoreGetColorMaskA(blendDst) == false);
    _RS_ASSERT(rsProgramStoreGetDitherEnabled(blendDst) == false);
    _RS_ASSERT(rsProgramStoreGetBlendSrcFunc(blendDst) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsProgramStoreGetBlendDstFunc(blendDst) == RS_BLEND_DST_DST_ALPHA);

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

