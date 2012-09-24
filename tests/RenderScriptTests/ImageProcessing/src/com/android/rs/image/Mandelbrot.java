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

package com.android.rs.image;

import java.lang.Math;

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Script;
import android.renderscript.ScriptC;
import android.renderscript.Type;
import android.util.Log;
import android.widget.SeekBar;
import android.widget.TextView;

public class Mandelbrot extends TestBase {
    private ScriptC_mandelbrot mScript;

    public boolean onBar1Setup(SeekBar b, TextView t) {
        t.setText("Iterations");
        b.setProgress(0);
        return true;
    }

    public void onBar1Changed(int progress) {
        int iters = progress * 3 + 50;
        mScript.set_gMaxIteration(iters);
    }

    public boolean onBar2Setup(SeekBar b, TextView t) {
        t.setText("Lower Bound: X");
        b.setProgress(0);
        return true;
    }

    public void onBar2Changed(int progress) {
        float scaleFactor = mScript.get_scaleFactor();
        // allow viewport to be moved by 2x scale factor
        float lowerBoundX = -2.f + ((progress / scaleFactor) / 50.f);
        mScript.set_lowerBoundX(lowerBoundX);
    }

    public boolean onBar3Setup(SeekBar b, TextView t) {
        t.setText("Lower Bound: Y");
        b.setProgress(0);
        return true;
    }

    public void onBar3Changed(int progress) {
        float scaleFactor = mScript.get_scaleFactor();
        // allow viewport to be moved by 2x scale factor
        float lowerBoundY = -2.f + ((progress / scaleFactor) / 50.f);
        mScript.set_lowerBoundY(lowerBoundY);
    }

    public boolean onBar4Setup(SeekBar b, TextView t) {
        t.setText("Scale Factor");
        b.setProgress(0);
        return true;
    }

    public void onBar4Changed(int progress) {
        float scaleFactor = 4.f - (3.96f * (progress / 100.f));
        mScript.set_scaleFactor(scaleFactor);
    }

    public void createTest(android.content.res.Resources res) {
        int width = mOutPixelsAllocation.getType().getX();
        int height = mOutPixelsAllocation.getType().getY();

        mScript = new ScriptC_mandelbrot(mRS, res, R.raw.mandelbrot);
        mScript.set_gDimX(width);
        mScript.set_gDimY(height);
        mScript.set_gMaxIteration(50);
    }

    public void runTest() {
        mScript.forEach_root(mOutPixelsAllocation);
        mRS.finish();
    }

}

