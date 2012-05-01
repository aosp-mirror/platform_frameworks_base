/*
 * Copyright (C) 2012 The Android Open Source Project
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

public class UT_array_alloc extends UnitTest {
    private Resources mRes;

    protected UT_array_alloc(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Array Allocation", ctx);
        mRes = res;
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_array_alloc s = new ScriptC_array_alloc(pRS, mRes, R.raw.array_alloc);
        pRS.setMessageHandler(mRsMessage);

        int dimX = s.get_dimX();
        Allocation[] Arr = new Allocation[dimX];
        Type.Builder typeBuilder = new Type.Builder(pRS, Element.I32(pRS));
        Type T = typeBuilder.setX(1).create();
        for (int i = 0; i < dimX; i++) {
            Allocation A = Allocation.createTyped(pRS, T);
            Arr[i] = A;
        }
        s.set_a(Arr);

        s.invoke_array_alloc_test();
        pRS.finish();
        waitForMessage();
        pRS.destroy();
        passTest();
    }
}
