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
import java.util.Arrays;

public class UT_bug_char extends UnitTest {
    private Resources mRes;

    protected UT_bug_char(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Bug Char", ctx);
        mRes = res;
    }

    // packing functions
    private Byte2 pack_b2(byte[] val) {
        assert val.length == 2;
        Log.i("bug_char", "pack_b2 " + val[0] + " " + val[1]);
        return new Byte2(val[0], val[1]);
    }

    private byte min(byte v1, byte v2) {
        return v1 < v2 ? v1 : v2;
    }
    private byte[] min(byte[] v1, byte[] v2) {
        assert v1.length == v2.length;
        byte[] rv = new byte[v1.length];
        for (int i = 0; i < v1.length; ++i)
            rv[i] = min(v1[i], v2[i]);
        return rv;
    }

    private void initializeValues(ScriptC_bug_char s) {
        byte rand_sc1_0 = (byte)7;
        byte[] rand_sc2_0 = new byte[2];
        rand_sc2_0[0] = 11;
        rand_sc2_0[1] = 21;
        Log.i("bug_char", "Generated sc2_0 to " + Arrays.toString(rand_sc2_0));
        byte rand_sc1_1 = (byte)10;
        byte[] rand_sc2_1 = new byte[2];
        rand_sc2_1[0] = 13;
        rand_sc2_1[1] = 15;
        Log.i("bug_char", "Generated sc2_1 to " + Arrays.toString(rand_sc2_1));

        s.set_rand_sc1_0(rand_sc1_0);
        s.set_rand_sc2_0(pack_b2(rand_sc2_0));
        s.set_rand_sc1_1(rand_sc1_1);
        s.set_rand_sc2_1(pack_b2(rand_sc2_1));
        // Set results for min
        s.set_min_rand_sc1_sc1(min(rand_sc1_0, rand_sc1_1));
        byte[] min_rand_sc2_raw = min(rand_sc2_0, rand_sc2_1);
        Log.i("bug_char", "Generating min_rand_sc2_sc2 to " +
              Arrays.toString(min_rand_sc2_raw));
        Byte2 min_rand_sc2 = pack_b2(min_rand_sc2_raw);
        Log.i("bug_char", "Setting min_rand_sc2_sc2 to [" + min_rand_sc2.x +
              ", " + min_rand_sc2.y + "]");
        s.set_min_rand_sc2_sc2(min_rand_sc2);
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_bug_char s = new ScriptC_bug_char(pRS, mRes,
                R.raw.bug_char);
        pRS.setMessageHandler(mRsMessage);
        initializeValues(s);
        s.invoke_bug_char_test();
        pRS.finish();
        waitForMessage();
        pRS.destroy();
    }
}
