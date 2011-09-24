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
        s.set_minification(b.create());

        b = getDefaultBuilder(RS);
        b.setMagnification(Value.LINEAR);
        s.set_magnification(b.create());

        b = getDefaultBuilder(RS);
        b.setWrapS(Value.WRAP);
        s.set_wrapS(b.create());

        b = getDefaultBuilder(RS);
        b.setWrapT(Value.WRAP);
        s.set_wrapT(b.create());

        b = getDefaultBuilder(RS);
        b.setAnisotropy(8.0f);
        s.set_anisotropy(b.create());
        return;
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_sampler s = new ScriptC_sampler(pRS, mRes, R.raw.sampler);
        pRS.setMessageHandler(mRsMessage);
        initializeGlobals(pRS, s);
        s.invoke_sampler_test();
        pRS.finish();
        waitForMessage();
        pRS.destroy();
    }
}
