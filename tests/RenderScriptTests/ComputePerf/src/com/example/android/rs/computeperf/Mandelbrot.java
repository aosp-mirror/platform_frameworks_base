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

public class Mandelbrot implements Runnable {
    private RenderScript mRS;
    private Allocation mAllocationXY;
    private ScriptC_mandelbrot mScript;

    Mandelbrot(RenderScript rs, Resources res) {
        mRS = rs;
        mScript = new ScriptC_mandelbrot(mRS, res, R.raw.mandelbrot);

        Type.Builder tb = new Type.Builder(rs, Element.U8_4(rs));
        tb.setX(mScript.get_gDimX());
        tb.setY(mScript.get_gDimY());
        mAllocationXY = Allocation.createTyped(rs, tb.create());
    }

    public void run() {
        long t = java.lang.System.currentTimeMillis();
        mScript.forEach_root(mAllocationXY);
        mRS.finish();
        t = java.lang.System.currentTimeMillis() - t;
        android.util.Log.v("ComputePerf", "mandelbrot  ms " + t);
    }

}
