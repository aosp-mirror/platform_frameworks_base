/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.rs.test_compat;

import android.content.Context;
import android.content.res.Resources;
import android.support.v8.renderscript.*;

public class UT_refcount extends UnitTest {
    private Resources mRes;

    protected UT_refcount(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Refcount", ctx);
        mRes = res;
    }

    private void initializeGlobals(RenderScript RS, ScriptC_refcount s) {
        Type.Builder typeBuilder = new Type.Builder(RS, Element.I32(RS));
        int X = 500;
        int Y = 700;
        typeBuilder.setX(X).setY(Y);
        Allocation A = Allocation.createTyped(RS, typeBuilder.create());
        s.set_globalA(A);
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        pRS.setMessageHandler(mRsMessage);
        ScriptC_refcount s = new ScriptC_refcount(pRS);
        initializeGlobals(pRS, s);
        s.invoke_refcount_test();
        pRS.finish();
        waitForMessage();
        pRS.destroy();
    }
}
