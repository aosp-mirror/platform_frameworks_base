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

public class UT_foreach_bounds extends UnitTest {
    private Resources mRes;
    private Allocation A;

    protected UT_foreach_bounds(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "ForEach (bounds)", ctx);
        mRes = res;
    }

    private void initializeGlobals(RenderScript RS, ScriptC_foreach_bounds s) {
        Type.Builder typeBuilder = new Type.Builder(RS, Element.I32(RS));
        int X = 5;
        int Y = 7;
        s.set_dimX(X);
        s.set_dimY(Y);
        typeBuilder.setX(X).setY(Y);
        A = Allocation.createTyped(RS, typeBuilder.create());
        s.set_aRaw(A);
        s.set_s(s);
        s.set_ain(A);
        s.set_aout(A);
        s.set_xStart(2);
        s.set_xEnd(5);
        s.set_yStart(3);
        s.set_yEnd(6);
        s.forEach_zero(A);

        return;
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_foreach_bounds s = new ScriptC_foreach_bounds(pRS);
        pRS.setMessageHandler(mRsMessage);
        initializeGlobals(pRS, s);
        s.invoke_foreach_bounds_test();
        pRS.finish();
        waitForMessage();
        pRS.destroy();
    }
}
