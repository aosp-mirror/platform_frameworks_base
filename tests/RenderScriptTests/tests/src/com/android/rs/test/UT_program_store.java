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
        s.set_ditherEnable(b.setDitherEnabled(true).create());

        b = getDefaultBuilder(RS);
        s.set_colorRWriteEnable(b.setColorMaskEnabled(true,  false, false, false).create());

        b = getDefaultBuilder(RS);
        s.set_colorGWriteEnable(b.setColorMaskEnabled(false, true,  false, false).create());

        b = getDefaultBuilder(RS);
        s.set_colorBWriteEnable(b.setColorMaskEnabled(false, false, true,  false).create());

        b = getDefaultBuilder(RS);
        s.set_colorAWriteEnable(b.setColorMaskEnabled(false, false, false, true).create());

        b = getDefaultBuilder(RS);
        s.set_blendSrc(b.setBlendFunc(ProgramStore.BlendSrcFunc.DST_COLOR,
                                      ProgramStore.BlendDstFunc.ZERO).create());

        b = getDefaultBuilder(RS);
        s.set_blendDst(b.setBlendFunc(ProgramStore.BlendSrcFunc.ZERO,
                                      ProgramStore.BlendDstFunc.DST_ALPHA).create());

        b = getDefaultBuilder(RS);
        s.set_depthWriteEnable(b.setDepthMaskEnabled(true).create());

        b = getDefaultBuilder(RS);
        s.set_depthFunc(b.setDepthFunc(ProgramStore.DepthFunc.GREATER).create());
        return;
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_program_store s = new ScriptC_program_store(pRS, mRes, R.raw.program_store);
        pRS.setMessageHandler(mRsMessage);
        initializeGlobals(pRS, s);
        s.invoke_program_store_test();
        pRS.finish();
        waitForMessage();
        pRS.destroy();
    }
}
