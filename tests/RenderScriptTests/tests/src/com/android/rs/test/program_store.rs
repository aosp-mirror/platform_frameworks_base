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
    _RS_ASSERT(rsgProgramStoreGetDepthMask(depthFunc) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskR(depthFunc) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskG(depthFunc) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskB(depthFunc) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskA(depthFunc) == false);
    _RS_ASSERT(rsgProgramStoreGetDitherEnabled(depthFunc) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(depthFunc) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(depthFunc) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(depthWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreGetDepthMask(depthWriteEnable) == true);
    _RS_ASSERT(rsgProgramStoreGetColorMaskR(depthWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskG(depthWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskB(depthWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskA(depthWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetDitherEnabled(depthWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(depthWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(depthWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(colorRWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreGetDepthMask(colorRWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskR(colorRWriteEnable) == true);
    _RS_ASSERT(rsgProgramStoreGetColorMaskG(colorRWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskB(colorRWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskA(colorRWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetDitherEnabled(colorRWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(colorRWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(colorRWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(colorGWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreGetDepthMask(colorGWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskR(colorGWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskG(colorGWriteEnable) == true);
    _RS_ASSERT(rsgProgramStoreGetColorMaskB(colorGWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskA(colorGWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetDitherEnabled(colorGWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(colorGWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(colorGWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(colorBWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreGetDepthMask(colorBWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskR(colorBWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskG(colorBWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskB(colorBWriteEnable) == true);
    _RS_ASSERT(rsgProgramStoreGetColorMaskA(colorBWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetDitherEnabled(colorBWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(colorBWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(colorBWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(colorAWriteEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreGetDepthMask(colorAWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskR(colorAWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskG(colorAWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskB(colorAWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskA(colorAWriteEnable) == true);
    _RS_ASSERT(rsgProgramStoreGetDitherEnabled(colorAWriteEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(colorAWriteEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(colorAWriteEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(ditherEnable) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreGetDepthMask(ditherEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskR(ditherEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskG(ditherEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskB(ditherEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskA(ditherEnable) == false);
    _RS_ASSERT(rsgProgramStoreGetDitherEnabled(ditherEnable) == true);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(ditherEnable) == RS_BLEND_SRC_ZERO);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(ditherEnable) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(blendSrc) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreGetDepthMask(blendSrc) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskR(blendSrc) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskG(blendSrc) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskB(blendSrc) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskA(blendSrc) == false);
    _RS_ASSERT(rsgProgramStoreGetDitherEnabled(blendSrc) == false);
    _RS_ASSERT(rsgProgramStoreGetBlendSrcFunc(blendSrc) == RS_BLEND_SRC_DST_COLOR);
    _RS_ASSERT(rsgProgramStoreGetBlendDstFunc(blendSrc) == RS_BLEND_DST_ZERO);

    _RS_ASSERT(rsgProgramStoreGetDepthFunc(blendDst) == RS_DEPTH_FUNC_ALWAYS);
    _RS_ASSERT(rsgProgramStoreGetDepthMask(blendDst) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskR(blendDst) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskG(blendDst) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskB(blendDst) == false);
    _RS_ASSERT(rsgProgramStoreGetColorMaskA(blendDst) == false);
    _RS_ASSERT(rsgProgramStoreGetDitherEnabled(blendDst) == false);
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

