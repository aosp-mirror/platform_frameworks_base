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
import android.renderscript.Matrix3f;
import android.renderscript.Script;
import android.renderscript.ScriptC;
import android.renderscript.Type;
import android.util.Log;
import android.widget.SeekBar;
import android.widget.TextView;


public class LevelsV4 extends TestBase {
    private ScriptC_levels_relaxed mScriptR;
    private ScriptC_levels_full mScriptF;
    private float mInBlack = 0.0f;
    private float mOutBlack = 0.0f;
    private float mInWhite = 255.0f;
    private float mOutWhite = 255.0f;
    private float mSaturation = 1.0f;

    Matrix3f satMatrix = new Matrix3f();
    float mInWMinInB;
    float mOutWMinOutB;
    float mOverInWMinInB;

    boolean mUseFull;
    boolean mUseV4;

    LevelsV4(boolean useFull, boolean useV4) {
        mUseFull = useFull;
        mUseV4 = useV4;
    }


    private void setLevels() {
        mInWMinInB = mInWhite - mInBlack;
        mOutWMinOutB = mOutWhite - mOutBlack;
        mOverInWMinInB = 1.f / mInWMinInB;

        mScriptR.set_inBlack(mInBlack);
        mScriptR.set_outBlack(mOutBlack);
        mScriptR.set_inWMinInB(mInWMinInB);
        mScriptR.set_outWMinOutB(mOutWMinOutB);
        mScriptR.set_overInWMinInB(mOverInWMinInB);
        mScriptF.set_inBlack(mInBlack);
        mScriptF.set_outBlack(mOutBlack);
        mScriptF.set_inWMinInB(mInWMinInB);
        mScriptF.set_outWMinOutB(mOutWMinOutB);
        mScriptF.set_overInWMinInB(mOverInWMinInB);
    }

    private void setSaturation() {
        float rWeight = 0.299f;
        float gWeight = 0.587f;
        float bWeight = 0.114f;
        float oneMinusS = 1.0f - mSaturation;

        satMatrix.set(0, 0, oneMinusS * rWeight + mSaturation);
        satMatrix.set(0, 1, oneMinusS * rWeight);
        satMatrix.set(0, 2, oneMinusS * rWeight);
        satMatrix.set(1, 0, oneMinusS * gWeight);
        satMatrix.set(1, 1, oneMinusS * gWeight + mSaturation);
        satMatrix.set(1, 2, oneMinusS * gWeight);
        satMatrix.set(2, 0, oneMinusS * bWeight);
        satMatrix.set(2, 1, oneMinusS * bWeight);
        satMatrix.set(2, 2, oneMinusS * bWeight + mSaturation);
        mScriptR.set_colorMat(satMatrix);
        mScriptF.set_colorMat(satMatrix);
    }

    public boolean onBar1Setup(SeekBar b, TextView t) {
        b.setProgress(50);
        t.setText("Saturation");
        return true;
    }
    public boolean onBar2Setup(SeekBar b, TextView t) {
        b.setMax(128);
        b.setProgress(0);
        t.setText("In Black");
        return true;
    }
    public boolean onBar3Setup(SeekBar b, TextView t) {
        b.setMax(128);
        b.setProgress(0);
        t.setText("Out Black");
        return true;
    }
    public boolean onBar4Setup(SeekBar b, TextView t) {
        b.setMax(128);
        b.setProgress(128);
        t.setText("Out White");
        return true;
    }
    public boolean onBar5Setup(SeekBar b, TextView t) {
        b.setMax(128);
        b.setProgress(128);
        t.setText("Out White");
        return true;
    }

    public void onBar1Changed(int progress) {
        mSaturation = (float)progress / 50.0f;
        setSaturation();
    }
    public void onBar2Changed(int progress) {
        mInBlack = (float)progress;
        setLevels();
    }
    public void onBar3Changed(int progress) {
        mOutBlack = (float)progress;
        setLevels();
    }
    public void onBar4Changed(int progress) {
        mInWhite = (float)progress + 127.0f;
        setLevels();
    }
    public void onBar5Changed(int progress) {
        mOutWhite = (float)progress + 127.0f;
        setLevels();
    }

    public void createTest(android.content.res.Resources res) {
        mScriptR = new ScriptC_levels_relaxed(mRS, res, R.raw.levels_relaxed);
        mScriptF = new ScriptC_levels_full(mRS, res, R.raw.levels_full);
        setSaturation();
        setLevels();
    }

    public void runTest() {
        if (mUseFull) {
            if (mUseV4) {
                mScriptF.forEach_root4(mInPixelsAllocation, mOutPixelsAllocation);
            } else {
                mScriptF.forEach_root(mInPixelsAllocation, mOutPixelsAllocation);
            }
        } else {
            if (mUseV4) {
                mScriptR.forEach_root4(mInPixelsAllocation, mOutPixelsAllocation);
            } else {
                mScriptR.forEach_root(mInPixelsAllocation, mOutPixelsAllocation);
            }
        }
    }

}

