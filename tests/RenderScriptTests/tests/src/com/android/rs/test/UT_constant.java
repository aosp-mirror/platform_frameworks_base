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

public class UT_constant extends UnitTest {
    private Resources mRes;

    protected UT_constant(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Const", ctx);
        mRes = res;
    }

    private void Assert(boolean b) {
        if (!b) {
            failTest();
        }
    }

    public void run() {
        Assert(ScriptC_constant.const_floatTest == 1.99f);
        Assert(ScriptC_constant.const_doubleTest == 2.05);
        Assert(ScriptC_constant.const_charTest == -8);
        Assert(ScriptC_constant.const_shortTest == -16);
        Assert(ScriptC_constant.const_intTest == -32);
        Assert(ScriptC_constant.const_longTest == 17179869184l);
        Assert(ScriptC_constant.const_longlongTest == 68719476736l);

        Assert(ScriptC_constant.const_ucharTest == 8);
        Assert(ScriptC_constant.const_ushortTest == 16);
        Assert(ScriptC_constant.const_uintTest == 32);
        Assert(ScriptC_constant.const_ulongTest == 4611686018427387904L);
        Assert(ScriptC_constant.const_int64_tTest == -17179869184l);
        Assert(ScriptC_constant.const_uint64_tTest == 117179869184l);

        Assert(ScriptC_constant.const_boolTest == true);

        passTest();
    }
}
