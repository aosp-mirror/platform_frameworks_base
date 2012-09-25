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

public class UT_copy_test extends UnitTest {
    private Resources mRes;
    boolean pass = true;

    protected UT_copy_test(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Copy", ctx);
        mRes = res;
    }

    void testFloat2(RenderScript rs, ScriptC_copy_test s) {
        Allocation a1 = Allocation.createSized(rs, Element.F32_2(rs), 1024);
        Allocation a2 = Allocation.createSized(rs, Element.F32_2(rs), 1024);

        float[] f1 = new float[1024 * 2];
        float[] f2 = new float[1024 * 2];
        for (int ct=0; ct < f1.length; ct++) {
            f1[ct] = (float)ct;
        }
        a1.copyFrom(f1);

        s.forEach_copyFloat2(a1, a2);

        a2.copyTo(f2);
        for (int ct=0; ct < f1.length; ct++) {
            if (f1[ct] != f2[ct]) {
                failTest();
                Log.v("RS Test", "Compare failed at " + ct + ", " + f1[ct] + ", " + f2[ct]);
            }
        }
        a1.destroy();
        a2.destroy();
    }

    void testFloat3(RenderScript rs, ScriptC_copy_test s) {
        Allocation a1 = Allocation.createSized(rs, Element.F32_3(rs), 1024);
        Allocation a2 = Allocation.createSized(rs, Element.F32_3(rs), 1024);

        float[] f1 = new float[1024 * 4];
        float[] f2 = new float[1024 * 4];
        for (int ct=0; ct < f1.length; ct++) {
            f1[ct] = (float)ct;
        }
        a1.copyFrom(f1);

        s.forEach_copyFloat3(a1, a2);

        a2.copyTo(f2);
        for (int ct=0; ct < f1.length; ct++) {
            if ((f1[ct] != f2[ct]) && ((ct&3) != 3)) {
                failTest();
                Log.v("RS Test", "Compare failed at " + ct + ", " + f1[ct] + ", " + f2[ct]);
            }
        }
        a1.destroy();
        a2.destroy();
    }

    void testFloat4(RenderScript rs, ScriptC_copy_test s) {
        Allocation a1 = Allocation.createSized(rs, Element.F32_4(rs), 1024);
        Allocation a2 = Allocation.createSized(rs, Element.F32_4(rs), 1024);

        float[] f1 = new float[1024 * 4];
        float[] f2 = new float[1024 * 4];
        for (int ct=0; ct < f1.length; ct++) {
            f1[ct] = (float)ct;
        }
        a1.copyFrom(f1);

        s.forEach_copyFloat4(a1, a2);

        a2.copyTo(f2);
        for (int ct=0; ct < f1.length; ct++) {
            if (f1[ct] != f2[ct]) {
                failTest();
                Log.v("RS Test", "Compare failed at " + ct + ", " + f1[ct] + ", " + f2[ct]);
            }
        }
        a1.destroy();
        a2.destroy();
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_copy_test s = new ScriptC_copy_test(pRS);
        pRS.setMessageHandler(mRsMessage);

        testFloat2(pRS, s);
        testFloat3(pRS, s);
        testFloat4(pRS, s);
        s.invoke_sendResult(true);

        pRS.finish();
        waitForMessage();
        pRS.destroy();
    }
}

