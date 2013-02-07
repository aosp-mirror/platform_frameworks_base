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

package com.android.rs.image2;

import java.lang.Math;

import android.support.v8.renderscript.*;
import android.util.Log;

public class Convolve3x3 extends TestBase {
    private ScriptC_ip2_convolve3x3 mScript;
    private ScriptIntrinsicConvolve3x3 mIntrinsic;

    private int mWidth;
    private int mHeight;
    private boolean mUseIntrinsic;

    public Convolve3x3(boolean useIntrinsic) {
        mUseIntrinsic = useIntrinsic;
    }

    public void createTest(android.content.res.Resources res) {
        mWidth = mInPixelsAllocation.getType().getX();
        mHeight = mInPixelsAllocation.getType().getY();

        float f[] = new float[9];
        f[0] =  0.f;    f[1] = -1.f;    f[2] =  0.f;
        f[3] = -1.f;    f[4] =  5.f;    f[5] = -1.f;
        f[6] =  0.f;    f[7] = -1.f;    f[8] =  0.f;

        if (mUseIntrinsic) {
            mIntrinsic = ScriptIntrinsicConvolve3x3.create(mRS, Element.U8_4(mRS));
            mIntrinsic.setCoefficients(f);
            mIntrinsic.setInput(mInPixelsAllocation);
        } else {
            mScript = new ScriptC_ip2_convolve3x3(mRS, res, R.raw.ip2_convolve3x3);
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
