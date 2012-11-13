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

package com.example.android.rs.latencybench;
import android.content.Context;
import android.content.res.Resources;
import android.renderscript.*;

public class Benchmark implements Runnable {
    private final RenderScript mRS;
    private ScriptC_compute_benchmark mScript;
    private Allocation ain;
    private Allocation aout;

    public Benchmark(RenderScript rs, Resources res) {
        mRS = rs;
        mScript = new ScriptC_compute_benchmark(mRS, res, R.raw.compute_benchmark);
        ain = Allocation.createSized(rs, Element.U32(mRS), 10000);
        aout = Allocation.createSized(rs, Element.U32(mRS), 10000);
    }

    public void run() {
        int[] temp;
        temp = new int[1];

        long t = java.lang.System.currentTimeMillis();

        for (int i = 0; i < 1000000; i++)
            mScript.forEach_root(ain, aout);
        aout.copy1DRangeFrom(0, 1, temp);

        t = java.lang.System.currentTimeMillis() - t;
        android.util.Log.v("LatencyBench", "Iterated Java forEach took " + t + " ms");

        mScript.set_empty_kern(mScript);
        mScript.set_in(ain);
        mScript.set_out(aout);

        t = java.lang.System.currentTimeMillis();
        mScript.invoke_emptyKernelLauncher();
        aout.copy1DRangeFrom(0, 1, temp);

        t = java.lang.System.currentTimeMillis() - t;
        android.util.Log.v("LatencyBench", "Invoked forEach took " + t + " ms");



    }

}
