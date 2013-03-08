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

import android.graphics.Bitmap;
import android.support.v8.renderscript.*;
import android.util.Log;
import android.widget.SeekBar;
import android.widget.TextView;

public class Blur25G extends TestBase {
    private final int MAX_RADIUS = 25;
    private float mRadius = MAX_RADIUS;

    private ScriptIntrinsicBlur mIntrinsic;

    private ScriptC_greyscale mScript;
    private Allocation mScratchPixelsAllocation1;
    private Allocation mScratchPixelsAllocation2;


    public Blur25G() {
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
        mIntrinsic.setRadius(mRadius);
    }


    public void createTest(android.content.res.Resources res) {
        int width = mInPixelsAllocation.getType().getX();
        int height = mInPixelsAllocation.getType().getY();

        Type.Builder tb = new Type.Builder(mRS, Element.U8(mRS));
        tb.setX(width);
        tb.setY(height);
        mScratchPixelsAllocation1 = Allocation.createTyped(mRS, tb.create());
        mScratchPixelsAllocation2 = Allocation.createTyped(mRS, tb.create());

        mScript = new ScriptC_greyscale(mRS);
        mScript.forEach_toU8(mInPixelsAllocation, mScratchPixelsAllocation1);

        mIntrinsic = ScriptIntrinsicBlur.create(mRS, Element.U8(mRS));
        mIntrinsic.setRadius(MAX_RADIUS);
        mIntrinsic.setInput(mScratchPixelsAllocation1);
    }

    public void runTest() {
        mIntrinsic.forEach(mScratchPixelsAllocation2);
    }

    public void setupBenchmark() {
        mIntrinsic.setRadius(MAX_RADIUS);
    }

    public void exitBenchmark() {
        mIntrinsic.setRadius(mRadius);
    }

    public void updateBitmap(Bitmap b) {
        mScript.forEach_toU8_4(mScratchPixelsAllocation2, mOutPixelsAllocation);
        mOutPixelsAllocation.copyTo(b);
    }

}

