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
import android.renderscript.ScriptIntrinsicBlur;
import android.renderscript.Type;
import android.util.Log;
import android.widget.SeekBar;
import android.widget.TextView;

public class Blur25 extends TestBase {
    private boolean mUseIntrinsic = false;
    private ScriptIntrinsicBlur mIntrinsic;

    private int MAX_RADIUS = 25;
    private ScriptC_threshold mScript;
    private float mRadius = MAX_RADIUS;
    private float mSaturation = 1.0f;
    private Allocation mScratchPixelsAllocation1;
    private Allocation mScratchPixelsAllocation2;


    public Blur25(boolean useIntrinsic) {
        mUseIntrinsic = useIntrinsic;
    }

    public boolean onBar1Setup(SeekBar b, TextView t) {
        t.setText("Radius");
        b.setProgress(100);
        return true;
    }


    public void onBar1Changed(int progress) {
        mRadius = ((float)progress) / 100.0f * MAX_RADIUS;
        if (mRadius <= 0.10f) {
            mRadius = 0.10f;
        }
        if (mUseIntrinsic) {
            mIntrinsic.setRadius(mRadius);
        } else {
            mScript.invoke_setRadius((int)mRadius);
        }
    }


    public void createTest(android.content.res.Resources res) {
        int width = mInPixelsAllocation.getType().getX();
        int height = mInPixelsAllocation.getType().getY();

        if (mUseIntrinsic) {
            mIntrinsic = ScriptIntrinsicBlur.create(mRS, Element.U8_4(mRS));
            mIntrinsic.setRadius(MAX_RADIUS);
            mIntrinsic.setInput(mInPixelsAllocation);
        } else {

            Type.Builder tb = new Type.Builder(mRS, Element.F32_4(mRS));
            tb.setX(width);
            tb.setY(height);
            mScratchPixelsAllocation1 = Allocation.createTyped(mRS, tb.create());
            mScratchPixelsAllocation2 = Allocation.createTyped(mRS, tb.create());

            mScript = new ScriptC_threshold(mRS, res, R.raw.threshold);
            mScript.set_width(width);
            mScript.set_height(height);
            mScript.invoke_setRadius(MAX_RADIUS);

            mScript.set_InPixel(mInPixelsAllocation);
            mScript.set_ScratchPixel1(mScratchPixelsAllocation1);
            mScript.set_ScratchPixel2(mScratchPixelsAllocation2);
        }
    }

    public void runTest() {
        if (mUseIntrinsic) {
            mIntrinsic.forEach(mOutPixelsAllocation);
        } else {
            mScript.forEach_copyIn(mInPixelsAllocation, mScratchPixelsAllocation1);
            mScript.forEach_horz(mScratchPixelsAllocation2);
            mScript.forEach_vert(mOutPixelsAllocation);
        }
    }

    public void setupBenchmark() {
        if (mUseIntrinsic) {
            mIntrinsic.setRadius(MAX_RADIUS);
        } else {
            mScript.invoke_setRadius(MAX_RADIUS);
        }
    }

    public void exitBenchmark() {
        if (mUseIntrinsic) {
            mIntrinsic.setRadius(mRadius);
        } else {
            mScript.invoke_setRadius((int)mRadius);
        }
    }
}
