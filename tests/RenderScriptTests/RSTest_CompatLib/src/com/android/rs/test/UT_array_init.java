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

public class UT_array_init extends UnitTest {
    private Resources mRes;

    protected UT_array_init(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Array Init", ctx);
        mRes = res;
    }

    private void checkInit(ScriptC_array_init s) {
        float[] fa = s.get_fa();
        _RS_ASSERT("fa[0] == 1.0", fa[0] == 1.0);
        _RS_ASSERT("fa[1] == 9.9999f", fa[1] == 9.9999f);
        _RS_ASSERT("fa[2] == 0", fa[2] == 0);
        _RS_ASSERT("fa[3] == 0", fa[3] == 0);
        _RS_ASSERT("fa.length == 4", fa.length == 4);

        double[] da = s.get_da();
        _RS_ASSERT("da[0] == 7.0", da[0] == 7.0);
        _RS_ASSERT("da[1] == 8.88888", da[1] == 8.88888);
        _RS_ASSERT("da.length == 2", da.length == 2);

        byte[] ca = s.get_ca();
        _RS_ASSERT("ca[0] == 'a'", ca[0] == 'a');
        _RS_ASSERT("ca[1] == 7", ca[1] == 7);
        _RS_ASSERT("ca[2] == 'b'", ca[2] == 'b');
        _RS_ASSERT("ca[3] == 'c'", ca[3] == 'c');
        _RS_ASSERT("ca.length == 4", ca.length == 4);

        short[] sa = s.get_sa();
        _RS_ASSERT("sa[0] == 1", sa[0] == 1);
        _RS_ASSERT("sa[1] == 1", sa[1] == 1);
        _RS_ASSERT("sa[2] == 2", sa[2] == 2);
        _RS_ASSERT("sa[3] == 3", sa[3] == 3);
        _RS_ASSERT("sa.length == 4", sa.length == 4);

        int[] ia = s.get_ia();
        _RS_ASSERT("ia[0] == 5", ia[0] == 5);
        _RS_ASSERT("ia[1] == 8", ia[1] == 8);
        _RS_ASSERT("ia[2] == 0", ia[2] == 0);
        _RS_ASSERT("ia[3] == 0", ia[3] == 0);
        _RS_ASSERT("ia.length == 4", ia.length == 4);

        long[] la = s.get_la();
        _RS_ASSERT("la[0] == 13", la[0] == 13);
        _RS_ASSERT("la[1] == 21", la[1] == 21);
        _RS_ASSERT("la.length == 4", la.length == 2);

        long[] lla = s.get_lla();
        _RS_ASSERT("lla[0] == 34", lla[0] == 34);
        _RS_ASSERT("lla[1] == 0", lla[1] == 0);
        _RS_ASSERT("lla[2] == 0", lla[2] == 0);
        _RS_ASSERT("lla[3] == 0", lla[3] == 0);
        _RS_ASSERT("lla.length == 4", lla.length == 4);

        boolean[] ba = s.get_ba();
        _RS_ASSERT("ba[0] == true", ba[0] == true);
        _RS_ASSERT("ba[1] == false", ba[1] == false);
        _RS_ASSERT("ba[2] == false", ba[2] == false);
        _RS_ASSERT("ba.length == 3", ba.length == 3);
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_array_init s = new ScriptC_array_init(pRS);
        pRS.setMessageHandler(mRsMessage);
        checkInit(s);
        s.invoke_array_init_test();
        pRS.finish();
        waitForMessage();
        pRS.destroy();
        passTest();
    }
}
