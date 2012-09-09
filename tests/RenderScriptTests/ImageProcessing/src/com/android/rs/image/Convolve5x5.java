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
import android.renderscript.Matrix4f;
import android.renderscript.RenderScript;
import android.renderscript.Script;
import android.renderscript.ScriptC;
import android.renderscript.ScriptGroup;
import android.renderscript.ScriptIntrinsicConvolve5x5;
import android.renderscript.Type;
import android.util.Log;

public class Convolve5x5 extends TestBase {
    private ScriptC_convolve5x5 mScript;
    private ScriptIntrinsicConvolve5x5 mIntrinsic;

    private int mWidth;
    private int mHeight;
    private boolean mUseIntrinsic;

    public Convolve5x5(boolean useIntrinsic) {
        mUseIntrinsic = useIntrinsic;
    }

    public void createTest(android.content.res.Resources res) {
        mWidth = mInPixelsAllocation.getType().getX();
        mHeight = mInPixelsAllocation.getType().getY();

        float f[] = new float[25];
        f[0] = 0.012f; f[1] = 0.025f; f[2] = 0.031f; f[3] = 0.025f; f[4] = 0.012f;
        f[5] = 0.025f; f[6] = 0.057f; f[7] = 0.075f; f[8] = 0.057f; f[9] = 0.025f;
        f[10]= 0.031f; f[11]= 0.075f; f[12]= 0.095f; f[13]= 0.075f; f[14]= 0.031f;
        f[15]= 0.025f; f[16]= 0.057f; f[17]= 0.075f; f[18]= 0.057f; f[19]= 0.025f;
        f[20]= 0.012f; f[21]= 0.025f; f[22]= 0.031f; f[23]= 0.025f; f[24]= 0.012f;

        if (mUseIntrinsic) {
            mIntrinsic = ScriptIntrinsicConvolve5x5.create(mRS, Element.U8_4(mRS));
            mIntrinsic.setCoefficients(f);
            mIntrinsic.setInput(mInPixelsAllocation);
        } else {
            mScript = new ScriptC_convolve5x5(mRS, res, R.raw.convolve5x5);
            mScript.set_gCoeffs(f);
            mScript.set_gIn(mInPixelsAllocation);
            mScript.set_gWidth(mWidth);
            mScript.set_gHeight(mHeight);
        }
    }

    public void runTest() {
        if (mUseIntrinsic) {
            mIntrinsic.forEach(mOutPixelsAllocation);
        } else {
            mScript.forEach_root(mOutPixelsAllocation);
        }
    }

}
