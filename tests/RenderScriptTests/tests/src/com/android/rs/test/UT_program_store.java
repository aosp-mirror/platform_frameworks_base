/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.rs.test;

import android.content.Context;
import android.content.res.Resources;
import android.renderscript.*;
import android.renderscript.ProgramStore.BlendDstFunc;
import android.renderscript.ProgramStore.BlendSrcFunc;
import android.renderscript.ProgramStore.Builder;
import android.renderscript.ProgramStore.DepthFunc;

public class UT_program_store extends UnitTest {
    private Resources mRes;

    ProgramStore ditherEnable;
    ProgramStore colorRWriteEnable;
    ProgramStore colorGWriteEnable;
    ProgramStore colorBWriteEnable;
    ProgramStore colorAWriteEnable;
    ProgramStore blendSrc;
    ProgramStore blendDst;
    ProgramStore depthWriteEnable;
    ProgramStore depthFunc;

    protected UT_program_store(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "ProgramStore", ctx);
        mRes = res;
    }

    private ProgramStore.Builder getDefaultBuilder(RenderScript RS) {
        ProgramStore.Builder b = new ProgramStore.Builder(RS);
        b.setBlendFunc(ProgramStore.BlendSrcFunc.ZERO, ProgramStore.BlendDstFunc.ZERO);
        b.setColorMaskEnabled(false, false, false, false);
        b.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
        b.setDepthMaskEnabled(false);
        b.setDitherEnabled(false);
        return b;
    }

    private void initializeGlobals(RenderScript RS, ScriptC_program_store s) {
        ProgramStore.Builder b = getDefaultBuilder(RS);
        ditherEnable = b.setDitherEnabled(true).create();

        b = getDefaultBuilder(RS);
        colorRWriteEnable = b.setColorMaskEnabled(true,  false, false, false).create();

        b = getDefaultBuilder(RS);
        colorGWriteEnable = b.setColorMaskEnabled(false, true,  false, false).create();

        b = getDefaultBuilder(RS);
        colorBWriteEnable = b.setColorMaskEnabled(false, false, true,  false).create();

        b = getDefaultBuilder(RS);
        colorAWriteEnable = b.setColorMaskEnabled(false, false, false, true).create();

        b = getDefaultBuilder(RS);
        blendSrc = b.setBlendFunc(ProgramStore.BlendSrcFunc.DST_COLOR,
                                  ProgramStore.BlendDstFunc.ZERO).create();

        b = getDefaultBuilder(RS);
        blendDst = b.setBlendFunc(ProgramStore.BlendSrcFunc.ZERO,
                                  ProgramStore.BlendDstFunc.DST_ALPHA).create();

        b = getDefaultBuilder(RS);
        depthWriteEnable = b.setDepthMaskEnabled(true).create();

        b = getDefaultBuilder(RS);
        depthFunc = b.setDepthFunc(ProgramStore.DepthFunc.GREATER).create();

        s.set_ditherEnable(ditherEnable);
        s.set_colorRWriteEnable(colorRWriteEnable);
        s.set_colorGWriteEnable(colorGWriteEnable);
        s.set_colorBWriteEnable(colorBWriteEnable);
        s.set_colorAWriteEnable(colorAWriteEnable);
        s.set_blendSrc(blendSrc);
        s.set_blendDst(blendDst);
        s.set_depthWriteEnable(depthWriteEnable);
        s.set_depthFunc(depthFunc);
    }

    private void testScriptSide(RenderScript pRS) {
        ScriptC_program_store s = new ScriptC_program_store(pRS, mRes, R.raw.program_store);
        pRS.setMessageHandler(mRsMessage);
        initializeGlobals(pRS, s);
        s.invoke_program_store_test();
        pRS.finish();
        waitForMessage();
    }

    void checkObject(ProgramStore ps,
                     boolean depthMask,
                     DepthFunc df,
                     BlendSrcFunc bsf,
                     BlendDstFunc bdf,
                     boolean R,
                     boolean G,
                     boolean B,
                     boolean A,
                     boolean dither) {
        _RS_ASSERT("ps.isDepthMaskEnabled() == depthMask", ps.isDepthMaskEnabled() == depthMask);
        _RS_ASSERT("ps.getDepthFunc() == df", ps.getDepthFunc() == df);
        _RS_ASSERT("ps.getBlendSrcFunc() == bsf", ps.getBlendSrcFunc() == bsf);
        _RS_ASSERT("ps.getBlendDstFunc() == bdf", ps.getBlendDstFunc() == bdf);
        _RS_ASSERT("ps.isColorMaskRedEnabled() == R", ps.isColorMaskRedEnabled() == R);
        _RS_ASSERT("ps.isColorMaskGreenEnabled() == G", ps.isColorMaskGreenEnabled() == G);
        _RS_ASSERT("ps.isColorMaskBlueEnabled () == B", ps.isColorMaskBlueEnabled () == B);
        _RS_ASSERT("ps.isColorMaskAlphaEnabled() == A", ps.isColorMaskAlphaEnabled() == A);
        _RS_ASSERT("ps.isDitherEnabled() == dither", ps.isDitherEnabled() == dither);
    }

    void varyBuilderColorAndDither(ProgramStore.Builder pb,
                                   boolean depthMask,
                                   DepthFunc df,
                                   BlendSrcFunc bsf,
                                   BlendDstFunc bdf) {
        for (int r = 0; r <= 1; r++) {
            boolean isR = (r == 1);
            for (int g = 0; g <= 1; g++) {
                boolean isG = (g == 1);
                for (int b = 0; b <= 1; b++) {
                    boolean isB = (b == 1);
                    for (int a = 0; a <= 1; a++) {
                        boolean isA = (a == 1);
                        for (int dither = 0; dither <= 1; dither++) {
                            boolean isDither = (dither == 1);
                            pb.setDitherEnabled(isDither);
                            pb.setColorMaskEnabled(isR, isG, isB, isA);
                            ProgramStore ps = pb.create();
                            checkObject(ps, depthMask, df, bsf, bdf, isR, isG, isB, isA, isDither);
                        }
                    }
                }
            }
        }
    }

    public void testJavaSide(RenderScript RS) {
        for (int depth = 0; depth <= 1; depth++) {
            boolean depthMask = (depth == 1);
            for (DepthFunc df : DepthFunc.values()) {
                for (BlendSrcFunc bsf : BlendSrcFunc.values()) {
                    for (BlendDstFunc bdf : BlendDstFunc.values()) {
                        ProgramStore.Builder b = new ProgramStore.Builder(RS);
                        b.setDepthFunc(df);
                        b.setDepthMaskEnabled(depthMask);
                        b.setBlendFunc(bsf, bdf);
                        varyBuilderColorAndDither(b, depthMask, df, bsf, bdf);
                    }
                }
            }
        }
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        testJavaSide(pRS);
        testScriptSide(pRS);
        pRS.destroy();
    }
}
