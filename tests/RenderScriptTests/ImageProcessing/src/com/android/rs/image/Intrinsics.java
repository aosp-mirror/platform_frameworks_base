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
import android.renderscript.ScriptIntrinsicConvolve3x3;
import android.renderscript.Type;
import android.util.Log;
import android.widget.SeekBar;
import android.widget.TextView;

public class Intrinsics extends TestBase {
    private ScriptIntrinsicConvolve3x3 mScript;

    Intrinsics(int id) {
    }

    public boolean onBar1Setup(SeekBar b, TextView t) {
        t.setText("Strength");
        b.setProgress(50);
        return true;
    }

    public void onBar1Changed(int progress) {
        float s = progress / 100.0f;
        float v[] = new float[9];
        v[0] = 0.f;     v[1] = -s;      v[2] = 0.f;
        v[3] = -s;      v[4] = s*4+1;   v[5] = -s;
        v[6] = 0.f;     v[7] = -s;      v[8] = 0.f;
        mScript.setValues(v);
    }


    public void createTest(android.content.res.Resources res) {
        mScript = ScriptIntrinsicConvolve3x3.create(mRS, Element.RGBA_8888(mRS));
    }

    public void runTest() {
        mScript.forEach(mInPixelsAllocation, mOutPixelsAllocation);
    }

}

