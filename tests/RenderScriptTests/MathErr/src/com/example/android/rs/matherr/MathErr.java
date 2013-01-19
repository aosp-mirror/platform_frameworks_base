/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.example.android.rs.matherr;

import android.content.res.Resources;
import android.renderscript.*;
import java.lang.Float;
import java.lang.Math;

public class MathErr {
    private RenderScript mRS;
    private Allocation mAllocationSrc;
    private Allocation mAllocationRes;
    private ScriptC_math_err mScript;
    private java.util.Random mRand = new java.util.Random();

    private final int BUF_SIZE = 4096;
    float mSrc[] = new float[BUF_SIZE];
    float mRef[] = new float[BUF_SIZE];
    float mRes[] = new float[BUF_SIZE];

    MathErr(RenderScript rs) {
        mRS = rs;
        mScript = new ScriptC_math_err(mRS);

        mAllocationSrc = Allocation.createSized(rs, Element.F32(rs), BUF_SIZE);
        mAllocationRes = Allocation.createSized(rs, Element.F32(rs), BUF_SIZE);

        testExp2();
        testLog2();
    }

    void buildRand() {
        for (int i=0; i < BUF_SIZE; i++) {
            mSrc[i] = (((float)i) / 9) - 200;
            //mSrc[i] = Float.intBitsToFloat(mRand.nextInt());
        }
        mAllocationSrc.copyFrom(mSrc);
    }

    void logErr() {
        mAllocationRes.copyTo(mRes);
        for (int i=0; i < BUF_SIZE; i++) {
            int err = Float.floatToRawIntBits(mRef[i]) - Float.floatToRawIntBits(mRes[i]);
            err = Math.abs(err);
            if (err > 8096) {
                android.util.Log.v("err", "error " + err + " src " + mSrc[i] + " ref " + mRef[i] + " res " + mRes[i]);
            }
        }
    }

    void testExp2() {
        android.util.Log.v("err", "testing exp2");
        buildRand();
        mScript.forEach_testExp2(mAllocationSrc, mAllocationRes);
        for (int i=0; i < BUF_SIZE; i++) {
            mRef[i] = (float)Math.pow(2.f, mSrc[i]);
        }
        logErr();
    }

    void testLog2() {
        android.util.Log.v("err", "testing log2");
        buildRand();
        mScript.forEach_testLog2(mAllocationSrc, mAllocationRes);
        for (int i=0; i < BUF_SIZE; i++) {
            mRef[i] = (float)Math.log(mSrc[i]) * 1.442695041f;
        }
        logErr();
    }

}
