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

public class Blur25 extends TestBase {
    private int MAX_RADIUS = 25;
    private ScriptC_threshold mScript;
    private ScriptC_vertical_blur mScriptVBlur;
    private ScriptC_horizontal_blur mScriptHBlur;
    private int mRadius = MAX_RADIUS;
    private float mSaturation = 1.0f;
    private Allocation mScratchPixelsAllocation1;
    private Allocation mScratchPixelsAllocation2;


    public boolean onBar1Setup(SeekBar b, TextView t) {
        t.setText("Radius");
        b.setProgress(100);
        return true;
    }
    public boolean onBar2Setup(SeekBar b, TextView t) {
        b.setProgress(50);
        t.setText("Saturation");
        return true;
    }


    public void onBar1Changed(int progress) {
        float fRadius = progress / 100.0f;
        fRadius *= (float)(MAX_RADIUS);
        mRadius = (int)fRadius;
        mScript.set_radius(mRadius);
    }
    public void onBar2Changed(int progress) {
        mSaturation = (float)progress / 50.0f;
        mScriptVBlur.invoke_setSaturation(mSaturation);
    }


    public void createTest(android.content.res.Resources res) {
        int width = mInPixelsAllocation.getType().getX();
        int height = mInPixelsAllocation.getType().getY();

        Type.Builder tb = new Type.Builder(mRS, Element.F32_4(mRS));
        tb.setX(width);
        tb.setY(height);
        mScratchPixelsAllocation1 = Allocation.createTyped(mRS, tb.create());
        mScratchPixelsAllocation2 = Allocation.createTyped(mRS, tb.create());

        mScriptVBlur = new ScriptC_vertical_blur(mRS, res, R.raw.vertical_blur);
        mScriptHBlur = new ScriptC_horizontal_blur(mRS, res, R.raw.horizontal_blur);

        mScript = new ScriptC_threshold(mRS, res, R.raw.threshold);
        mScript.set_width(width);
        mScript.set_height(height);
        mScript.set_radius(mRadius);

        mScriptVBlur.invoke_setSaturation(mSaturation);

        mScript.bind_InPixel(mInPixelsAllocation);
        mScript.bind_OutPixel(mOutPixelsAllocation);
        mScript.bind_ScratchPixel1(mScratchPixelsAllocation1);
        mScript.bind_ScratchPixel2(mScratchPixelsAllocation2);

        mScript.set_vBlurScript(mScriptVBlur);
        mScript.set_hBlurScript(mScriptHBlur);
    }

    public void runTest() {
        mScript.invoke_filter();
    }

    public void setupBenchmark() {
        mScript.set_radius(MAX_RADIUS);
    }

    public void exitBenchmark() {
        mScript.set_radius(mRadius);
    }
}
