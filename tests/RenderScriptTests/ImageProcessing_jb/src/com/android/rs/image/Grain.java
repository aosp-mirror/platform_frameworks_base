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

package com.android.rs.imagejb;

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

public class Grain extends TestBase {
    private ScriptC_grain mScript;
    private Allocation mNoise;
    private Allocation mNoise2;


    public boolean onBar1Setup(SeekBar b, TextView t) {
        t.setText("Strength");
        b.setProgress(50);
        return true;
    }

    public void onBar1Changed(int progress) {
        float s = progress / 100.0f;
        mScript.set_gNoiseStrength(s);
    }

    private int findHighBit(int v) {
        int bit = 0;
        while (v > 1) {
            bit++;
            v >>= 1;
        }
        return bit;
    }


    public void createTest(android.content.res.Resources res) {
        int width = mInPixelsAllocation.getType().getX();
        int height = mInPixelsAllocation.getType().getY();

        int noiseW = findHighBit(width);
        int noiseH = findHighBit(height);
        if (noiseW > 9) {
            noiseW = 9;
        }
        if (noiseH > 9) {
            noiseH = 9;
        }
        noiseW = 1 << noiseW;
        noiseH = 1 << noiseH;

        Type.Builder tb = new Type.Builder(mRS, Element.U8(mRS));
        tb.setX(noiseW);
        tb.setY(noiseH);
        mNoise = Allocation.createTyped(mRS, tb.create());
        mNoise2 = Allocation.createTyped(mRS, tb.create());

        mScript = new ScriptC_grain(mRS, res, R.raw.grain);
        mScript.set_gWMask(noiseW - 1);
        mScript.set_gHMask(noiseH - 1);
        mScript.set_gNoiseStrength(0.5f);
        mScript.set_gBlendSource(mNoise);
        mScript.set_gNoise(mNoise2);
    }

    public void runTest() {
        mScript.forEach_genRand(mNoise);
        mScript.forEach_blend9(mNoise2);
        mScript.forEach_root(mInPixelsAllocation, mOutPixelsAllocation);
    }

}

