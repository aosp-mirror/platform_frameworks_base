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
import java.lang.Short;

import android.support.v8.renderscript.*;
import android.util.Log;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.view.View;
import android.widget.Spinner;

public class Blend extends TestBase {
    private ScriptIntrinsicBlend mBlend;
    private ScriptC_blend mBlendHelper;
    private short image1Alpha = 128;
    private short image2Alpha = 128;

    String mIntrinsicNames[];

    private Allocation image1;
    private Allocation image2;
    private int currentIntrinsic = 0;

    private AdapterView.OnItemSelectedListener mIntrinsicSpinnerListener =
            new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    currentIntrinsic = pos;
                    if (mRS != null) {
                        runTest();
                        act.updateDisplay();
                    }
                }

                public void onNothingSelected(AdapterView parent) {

                }
            };

    public void createTest(android.content.res.Resources res) {
        mBlend = ScriptIntrinsicBlend.create(mRS, Element.U8_4(mRS));
        mBlendHelper = new ScriptC_blend(mRS);
        mBlendHelper.set_alpha((short)128);

        image1 = Allocation.createTyped(mRS, mInPixelsAllocation.getType());
        image2 = Allocation.createTyped(mRS, mInPixelsAllocation2.getType());

        mIntrinsicNames = new String[14];
        mIntrinsicNames[0] = "Source";
        mIntrinsicNames[1] = "Destination";
        mIntrinsicNames[2] = "Source Over";
        mIntrinsicNames[3] = "Destination Over";
        mIntrinsicNames[4] = "Source In";
        mIntrinsicNames[5] = "Destination In";
        mIntrinsicNames[6] = "Source Out";
        mIntrinsicNames[7] = "Destination Out";
        mIntrinsicNames[8] = "Source Atop";
        mIntrinsicNames[9] = "Destination Atop";
        mIntrinsicNames[10] = "XOR";
        mIntrinsicNames[11] = "Add";
        mIntrinsicNames[12] = "Subtract";
        mIntrinsicNames[13] = "Multiply";
    }

    public boolean onSpinner1Setup(Spinner s) {
        s.setAdapter(new ArrayAdapter<String>(
            act, R.layout.spinner_layout, mIntrinsicNames));
        s.setOnItemSelectedListener(mIntrinsicSpinnerListener);
        return true;
    }

    public boolean onBar1Setup(SeekBar b, TextView t) {
        t.setText("Image 1 Alpha");
        b.setMax(255);
        b.setProgress(image1Alpha);
        return true;
    }

    public void onBar1Changed(int progress) {
        image1Alpha = (short)progress;
    }

    public boolean onBar2Setup(SeekBar b, TextView t) {
        t.setText("Image 2 Alpha");
        b.setMax(255);
        b.setProgress(image2Alpha);
        return true;
    }

    public void onBar2Changed(int progress) {
        image2Alpha = (short)progress;
    }

    public void runTest() {
        image1.copy2DRangeFrom(0, 0, mInPixelsAllocation.getType().getX(), mInPixelsAllocation.getType().getY(), mInPixelsAllocation, 0, 0);
        image2.copy2DRangeFrom(0, 0, mInPixelsAllocation2.getType().getX(), mInPixelsAllocation2.getType().getY(), mInPixelsAllocation2, 0, 0);

        mBlendHelper.set_alpha(image1Alpha);
        mBlendHelper.forEach_setImageAlpha(image1);

        mBlendHelper.set_alpha(image2Alpha);
        mBlendHelper.forEach_setImageAlpha(image2);

        switch (currentIntrinsic) {
        case 0:
            mBlend.forEachSrc(image1, image2);
            break;
        case 1:
            mBlend.forEachDst(image1, image2);
            break;
        case 2:
            mBlend.forEachSrcOver(image1, image2);
            break;
        case 3:
            mBlend.forEachDstOver(image1, image2);
            break;
        case 4:
            mBlend.forEachSrcIn(image1, image2);
            break;
        case 5:
            mBlend.forEachDstIn(image1, image2);
            break;
        case 6:
            mBlend.forEachSrcOut(image1, image2);
            break;
        case 7:
            mBlend.forEachDstOut(image1, image2);
            break;
        case 8:
            mBlend.forEachSrcAtop(image1, image2);
            break;
        case 9:
            mBlend.forEachDstAtop(image1, image2);
            break;
        case 10:
            mBlend.forEachXor(image1, image2);
            break;
        case 11:
            mBlend.forEachAdd(image1, image2);
            break;
        case 12:
            mBlend.forEachSubtract(image1, image2);
            break;
        case 13:
            mBlend.forEachMultiply(image1, image2);
            break;
        }

        mOutPixelsAllocation.copy2DRangeFrom(0, 0, image2.getType().getX(), image2.getType().getY(), image2, 0, 0);
    }

}
