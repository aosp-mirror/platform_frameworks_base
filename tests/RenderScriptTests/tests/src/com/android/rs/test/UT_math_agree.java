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
import java.util.Random;

public class UT_math_agree extends UnitTest {
    private Resources mRes;

    protected UT_math_agree(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Math Agreement", ctx);
        mRes = res;
    }

    private void initializeValues(ScriptC_math_agree s) {
        Random rand = new Random();

        float x = rand.nextFloat();
        float y = rand.nextFloat();

        s.set_x(x);
        s.set_y(y);
        s.set_result_add(x + y);
        s.set_result_sub(x - y);
        s.set_result_mul(x * y);
        s.set_result_div(x / y);
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_math_agree s = new ScriptC_math_agree(pRS, mRes,
                R.raw.math_agree);
        pRS.setMessageHandler(mRsMessage);
        initializeValues(s);
        s.invoke_math_agree_test();
        pRS.finish();
        waitForMessage();
        pRS.destroy();
    }
}
