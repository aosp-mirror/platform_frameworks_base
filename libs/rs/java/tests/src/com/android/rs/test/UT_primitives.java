/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.content.res.Resources;
import android.renderscript.*;

public class UT_primitives extends UnitTest {
    private Resources mRes;

    protected UT_primitives(Resources res) {
        super("Primitives");
        mRes = res;
    }

    public void run() {
        RenderScript pRS = RenderScript.create();
        ScriptC_primitives s = new ScriptC_primitives(pRS, mRes, R.raw.primitives, true);
        pRS.mMessageCallback = mRsMessage;
        s.invoke_primitives_test(0, 0);
        pRS.finish();
        //android.util.Log.v("UT", "After pRS.finish");

        pRS.destroy();
    }
}

