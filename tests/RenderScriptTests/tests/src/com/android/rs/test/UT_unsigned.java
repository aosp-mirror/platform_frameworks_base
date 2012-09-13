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

public class UT_unsigned extends UnitTest {
    private Resources mRes;

    protected UT_unsigned(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Unsigned", ctx);
        mRes = res;
    }

    private boolean initializeGlobals(ScriptC_unsigned s) {
        short pUC = s.get_uc();
        if (pUC != 5) {
            return false;
        }
        s.set_uc((short)129);

        long pUI = s.get_ui();
        if (pUI != 37) {
            return false;
        }
        s.set_ui(0x7fffffff);

        return true;
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_unsigned s = new ScriptC_unsigned(pRS);
        pRS.setMessageHandler(mRsMessage);
        if (!initializeGlobals(s)) {
            failTest();
        } else {
            s.invoke_unsigned_test();
            pRS.finish();
            waitForMessage();
        }
        pRS.destroy();
    }
}
