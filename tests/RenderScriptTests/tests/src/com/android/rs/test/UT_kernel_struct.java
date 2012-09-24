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
import android.util.Log;

public class UT_kernel_struct extends UnitTest {
    private Resources mRes;
    private Allocation A;
    private Allocation B;

    protected UT_kernel_struct(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Kernels (struct pass-by-value)", ctx);
        mRes = res;
    }

    private void initializeGlobals(RenderScript RS, ScriptC_kernel_struct s) {
        int X = 5;
        s.set_dimX(X);
        ScriptField_simpleStruct t;
        t = new ScriptField_simpleStruct(RS, X);
        s.bind_ain(t);
        A = t.getAllocation();
        t = new ScriptField_simpleStruct(RS, X);
        s.bind_aout(t);
        B = t.getAllocation();

        return;
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_kernel_struct s = new ScriptC_kernel_struct(pRS);
        pRS.setMessageHandler(mRsMessage);
        initializeGlobals(pRS, s);
        s.forEach_init_vars(A);
        s.forEach_root(A, B);
        s.invoke_verify_root();
        s.invoke_kernel_struct_test();
        pRS.finish();
        waitForMessage();
        pRS.destroy();
    }
}
