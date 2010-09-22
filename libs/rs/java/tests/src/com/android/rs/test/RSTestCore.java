/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.util.Log;


public class RSTestCore {
    public static final int PART_COUNT = 50000;

    public RSTestCore() {
    }

    private Resources mRes;
    private RenderScriptGL mRS;

    private ScriptC_test_root mRootScript;

    private boolean fp_mad() {
        ScriptC_fp_mad s = new ScriptC_fp_mad(mRS, mRes, R.raw.fp_mad, true);
        s.invoke_doTest(0, 0);
        return true;
    }

    private boolean rs_primitives_test() {
        ScriptC_primitives s = new ScriptC_primitives(mRS, mRes, R.raw.primitives, true);
        s.invoke_rs_primitives_test(0, 0);
        return true;
    }

    public void init(RenderScriptGL rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;

        mRootScript = new ScriptC_test_root(mRS, mRes, R.raw.test_root, true);

        rs_primitives_test();
        fp_mad();

    }

    public void newTouchPosition(float x, float y, float pressure, int id) {
    }
}
