/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.example.android.rs.computeperf;

import android.content.res.Resources;
import android.renderscript.*;

public class LaunchTest implements Runnable {
    private RenderScript mRS;
    private Allocation mAllocationX;
    private Allocation mAllocationXY;
    private ScriptC_launchtestxlw mScript_xlw;
    private ScriptC_launchtestxyw mScript_xyw;

    LaunchTest(RenderScript rs, Resources res) {
        mRS = rs;
        mScript_xlw = new ScriptC_launchtestxlw(mRS, res, R.raw.launchtestxlw);
        mScript_xyw = new ScriptC_launchtestxyw(mRS, res, R.raw.launchtestxyw);
        final int dim = mScript_xlw.get_dim();

        mAllocationX = Allocation.createSized(rs, Element.U8(rs), dim);
        Type.Builder tb = new Type.Builder(rs, Element.U8(rs));
        tb.setX(dim);
        tb.setY(dim);
        mAllocationXY = Allocation.createTyped(rs, tb.create());
        mScript_xlw.bind_buf(mAllocationXY);
    }

    public void run() {
        long t = java.lang.System.currentTimeMillis();
        mScript_xlw.forEach_root(mAllocationX);
        mRS.finish();
        t = java.lang.System.currentTimeMillis() - t;
        android.util.Log.v("ComputePerf", "xlw launch test  ms " + t);

        t = java.lang.System.currentTimeMillis();
        mScript_xyw.forEach_root(mAllocationXY);
        mRS.finish();
        t = java.lang.System.currentTimeMillis() - t;
        android.util.Log.v("ComputePerf", "xyw launch test  ms " + t);
    }

}
