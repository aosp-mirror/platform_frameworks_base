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
import android.renderscript.ProgramRaster;
import android.renderscript.ProgramRaster.CullMode;

public class UT_program_raster extends UnitTest {
    private Resources mRes;

    ProgramRaster pointSpriteEnabled;
    ProgramRaster cullMode;

    protected UT_program_raster(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "ProgramRaster", ctx);
        mRes = res;
    }

    private ProgramRaster.Builder getDefaultBuilder(RenderScript RS) {
        ProgramRaster.Builder b = new ProgramRaster.Builder(RS);
        b.setCullMode(CullMode.BACK);
        b.setPointSpriteEnabled(false);
        return b;
    }

    private void initializeGlobals(RenderScript RS, ScriptC_program_raster s) {
        ProgramRaster.Builder b = getDefaultBuilder(RS);
        pointSpriteEnabled = b.setPointSpriteEnabled(true).create();
        b = getDefaultBuilder(RS);
        cullMode = b.setCullMode(CullMode.FRONT).create();

        s.set_pointSpriteEnabled(pointSpriteEnabled);
        s.set_cullMode(cullMode);
    }

    private void testScriptSide(RenderScript pRS) {
        ScriptC_program_raster s = new ScriptC_program_raster(pRS);
        pRS.setMessageHandler(mRsMessage);
        initializeGlobals(pRS, s);
        s.invoke_program_raster_test();
        pRS.finish();
        waitForMessage();
    }

    private void testJavaSide(RenderScript RS) {
        _RS_ASSERT("pointSpriteEnabled.isPointSpriteEnabled() == true",
                    pointSpriteEnabled.isPointSpriteEnabled() == true);
        _RS_ASSERT("pointSpriteEnabled.getCullMode() == ProgramRaster.CullMode.BACK",
                    pointSpriteEnabled.getCullMode() == ProgramRaster.CullMode.BACK);

        _RS_ASSERT("cullMode.isPointSpriteEnabled() == false",
                    cullMode.isPointSpriteEnabled() == false);
        _RS_ASSERT("cullMode.getCullMode() == ProgramRaster.CullMode.FRONT",
                    cullMode.getCullMode() == ProgramRaster.CullMode.FRONT);
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        testScriptSide(pRS);
        testJavaSide(pRS);
        passTest();
        pRS.destroy();
    }
}
