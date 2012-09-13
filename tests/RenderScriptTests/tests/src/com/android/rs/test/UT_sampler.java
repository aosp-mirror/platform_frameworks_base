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
import android.renderscript.Sampler;
import android.renderscript.Sampler.Value;

public class UT_sampler extends UnitTest {
    private Resources mRes;

    Sampler minification;
    Sampler magnification;
    Sampler wrapS;
    Sampler wrapT;
    Sampler anisotropy;

    protected UT_sampler(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Sampler", ctx);
        mRes = res;
    }

    private Sampler.Builder getDefaultBuilder(RenderScript RS) {
        Sampler.Builder b = new Sampler.Builder(RS);
        b.setMinification(Value.NEAREST);
        b.setMagnification(Value.NEAREST);
        b.setWrapS(Value.CLAMP);
        b.setWrapT(Value.CLAMP);
        b.setAnisotropy(1.0f);
        return b;
    }

    private void initializeGlobals(RenderScript RS, ScriptC_sampler s) {
        Sampler.Builder b = getDefaultBuilder(RS);
        b.setMinification(Value.LINEAR_MIP_LINEAR);
        minification = b.create();

        b = getDefaultBuilder(RS);
        b.setMagnification(Value.LINEAR);
        magnification = b.create();

        b = getDefaultBuilder(RS);
        b.setWrapS(Value.WRAP);
        wrapS = b.create();

        b = getDefaultBuilder(RS);
        b.setWrapT(Value.WRAP);
        wrapT = b.create();

        b = getDefaultBuilder(RS);
        b.setAnisotropy(8.0f);
        anisotropy = b.create();

        s.set_minification(minification);
        s.set_magnification(magnification);
        s.set_wrapS(wrapS);
        s.set_wrapT(wrapT);
        s.set_anisotropy(anisotropy);
    }

    private void testScriptSide(RenderScript pRS) {
        ScriptC_sampler s = new ScriptC_sampler(pRS);
        pRS.setMessageHandler(mRsMessage);
        initializeGlobals(pRS, s);
        s.invoke_sampler_test();
        pRS.finish();
        waitForMessage();
    }

    private void testJavaSide(RenderScript RS) {
        _RS_ASSERT("minification.getMagnification() == Sampler.Value.NEAREST",
                    minification.getMagnification() == Sampler.Value.NEAREST);
        _RS_ASSERT("minification.getMinification() == Sampler.Value.LINEAR_MIP_LINEAR",
                    minification.getMinification() == Sampler.Value.LINEAR_MIP_LINEAR);
        _RS_ASSERT("minification.getWrapS() == Sampler.Value.CLAMP",
                    minification.getWrapS() == Sampler.Value.CLAMP);
        _RS_ASSERT("minification.getWrapT() == Sampler.Value.CLAMP",
                    minification.getWrapT() == Sampler.Value.CLAMP);
        _RS_ASSERT("minification.getAnisotropy() == 1.0f",
                    minification.getAnisotropy() == 1.0f);

        _RS_ASSERT("magnification.getMagnification() == Sampler.Value.LINEAR",
                    magnification.getMagnification() == Sampler.Value.LINEAR);
        _RS_ASSERT("magnification.getMinification() == Sampler.Value.NEAREST",
                    magnification.getMinification() == Sampler.Value.NEAREST);
        _RS_ASSERT("magnification.getWrapS() == Sampler.Value.CLAMP",
                    magnification.getWrapS() == Sampler.Value.CLAMP);
        _RS_ASSERT("magnification.getWrapT() == Sampler.Value.CLAMP",
                    magnification.getWrapT() == Sampler.Value.CLAMP);
        _RS_ASSERT("magnification.getAnisotropy() == 1.0f",
                    magnification.getAnisotropy() == 1.0f);

        _RS_ASSERT("wrapS.getMagnification() == Sampler.Value.NEAREST",
                    wrapS.getMagnification() == Sampler.Value.NEAREST);
        _RS_ASSERT("wrapS.getMinification() == Sampler.Value.NEAREST",
                    wrapS.getMinification() == Sampler.Value.NEAREST);
        _RS_ASSERT("wrapS.getWrapS() == Sampler.Value.WRAP",
                    wrapS.getWrapS() == Sampler.Value.WRAP);
        _RS_ASSERT("wrapS.getWrapT() == Sampler.Value.CLAMP",
                    wrapS.getWrapT() == Sampler.Value.CLAMP);
        _RS_ASSERT("wrapS.getAnisotropy() == 1.0f",
                    wrapS.getAnisotropy() == 1.0f);

        _RS_ASSERT("wrapT.getMagnification() == Sampler.Value.NEAREST",
                    wrapT.getMagnification() == Sampler.Value.NEAREST);
        _RS_ASSERT("wrapT.getMinification() == Sampler.Value.NEAREST",
                    wrapT.getMinification() == Sampler.Value.NEAREST);
        _RS_ASSERT("wrapT.getWrapS() == Sampler.Value.CLAMP",
                    wrapT.getWrapS() == Sampler.Value.CLAMP);
        _RS_ASSERT("wrapT.getWrapT() == Sampler.Value.WRAP",
                    wrapT.getWrapT() == Sampler.Value.WRAP);
        _RS_ASSERT("wrapT.getAnisotropy() == 1.0f",
                    wrapT.getAnisotropy() == 1.0f);

        _RS_ASSERT("anisotropy.getMagnification() == Sampler.Value.NEAREST",
                    anisotropy.getMagnification() == Sampler.Value.NEAREST);
        _RS_ASSERT("anisotropy.getMinification() == Sampler.Value.NEAREST",
                    anisotropy.getMinification() == Sampler.Value.NEAREST);
        _RS_ASSERT("anisotropy.getWrapS() == Sampler.Value.CLAMP",
                    anisotropy.getWrapS() == Sampler.Value.CLAMP);
        _RS_ASSERT("anisotropy.getWrapT() == Sampler.Value.CLAMP",
                    anisotropy.getWrapT() == Sampler.Value.CLAMP);
        _RS_ASSERT("anisotropy.getAnisotropy() == 1.0f",
                    anisotropy.getAnisotropy() == 8.0f);
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        testScriptSide(pRS);
        testJavaSide(pRS);
        passTest();
        pRS.destroy();
    }
}
