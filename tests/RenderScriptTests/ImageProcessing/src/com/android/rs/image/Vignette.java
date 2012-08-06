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

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Sampler;
import android.renderscript.Type;
import android.widget.SeekBar;
import android.widget.TextView;

public class Vignette extends TestBase {
    private ScriptC_vignette_full mScript_full = null;
    private ScriptC_vignette_relaxed mScript_relaxed = null;
    private ScriptC_vignette_approx_full mScript_approx_full = null;
    private ScriptC_vignette_approx_relaxed mScript_approx_relaxed = null;
    private final boolean approx;
    private final boolean relaxed;
    private float center_x = 0.5f;
    private float center_y = 0.5f;
    private float scale = 0.5f;
    private float shade = 0.5f;
    private float slope = 20.0f;

    public Vignette(boolean approx, boolean relaxed) {
        this.approx = approx;
        this.relaxed = relaxed;
    }

    public boolean onBar1Setup(SeekBar b, TextView t) {
        t.setText("Scale");
        b.setMax(100);
        b.setProgress(25);
        return true;
    }
    public boolean onBar2Setup(SeekBar b, TextView t) {
        t.setText("Shade");
        b.setMax(100);
        b.setProgress(50);
        return true;
    }
    public boolean onBar3Setup(SeekBar b, TextView t) {
        t.setText("Slope");
        b.setMax(100);
        b.setProgress(20);
        return true;
    }
    public boolean onBar4Setup(SeekBar b, TextView t) {
        t.setText("Shift center X");
        b.setMax(100);
        b.setProgress(50);
        return true;
    }
    public boolean onBar5Setup(SeekBar b, TextView t) {
        t.setText("Shift center Y");
        b.setMax(100);
        b.setProgress(50);
        return true;
    }

    public void onBar1Changed(int progress) {
        scale = progress / 50.0f;
        do_init();
    }
    public void onBar2Changed(int progress) {
        shade = progress / 100.0f;
        do_init();
    }
    public void onBar3Changed(int progress) {
        slope = (float)progress;
        do_init();
    }
    public void onBar4Changed(int progress) {
        center_x = progress / 100.0f;
        do_init();
    }
    public void onBar5Changed(int progress) {
        center_y = progress / 100.0f;
        do_init();
    }

    private void do_init() {
        if (approx) {
            if (relaxed)
                mScript_approx_relaxed.invoke_init_vignette(
                        mInPixelsAllocation.getType().getX(),
                        mInPixelsAllocation.getType().getY(), center_x,
                        center_y, scale, shade, slope);
            else
                mScript_approx_full.invoke_init_vignette(
                        mInPixelsAllocation.getType().getX(),
                        mInPixelsAllocation.getType().getY(), center_x,
                        center_y, scale, shade, slope);
        } else if (relaxed)
            mScript_relaxed.invoke_init_vignette(
                    mInPixelsAllocation.getType().getX(),
                    mInPixelsAllocation.getType().getY(), center_x, center_y,
                    scale, shade, slope);
        else
            mScript_full.invoke_init_vignette(
                    mInPixelsAllocation.getType().getX(),
                    mInPixelsAllocation.getType().getY(), center_x, center_y,
                    scale, shade, slope);
    }

    public void createTest(android.content.res.Resources res) {
        if (approx) {
            if (relaxed)
                mScript_approx_relaxed = new ScriptC_vignette_approx_relaxed(
                        mRS, res, R.raw.vignette_approx_relaxed);
            else
                mScript_approx_full = new ScriptC_vignette_approx_full(
                        mRS, res, R.raw.vignette_approx_full);
        } else if (relaxed)
            mScript_relaxed = new ScriptC_vignette_relaxed(mRS, res,
                    R.raw.vignette_relaxed);
        else
            mScript_full = new ScriptC_vignette_full(mRS, res,
                    R.raw.vignette_full);
        do_init();
    }

    public void runTest() {
        if (approx) {
            if (relaxed)
                mScript_approx_relaxed.forEach_root(mInPixelsAllocation,
                        mOutPixelsAllocation);
            else
                mScript_approx_full.forEach_root(mInPixelsAllocation,
                        mOutPixelsAllocation);
        } else if (relaxed)
            mScript_relaxed.forEach_root(mInPixelsAllocation,
                    mOutPixelsAllocation);
        else
            mScript_full.forEach_root(mInPixelsAllocation,
                    mOutPixelsAllocation);
    }

}

