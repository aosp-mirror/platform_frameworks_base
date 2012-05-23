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

package com.android.rs.matrix;

import java.lang.Math;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Bundle;
import android.renderscript.*;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

public class MatrixActivity extends Activity {

    private RenderScript mRS;
    private ScriptC_large_matrix mScript;
    private Allocation mVectorIn;
    private Allocation mVectorOut;
    private Allocation mMatrix;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mRS = RenderScript.create(this);

        Type.Builder tb = new Type.Builder(mRS, Element.F32_4(mRS));
        tb.setX(2000);
        tb.setY(500);
        Type t = tb.create();
        mMatrix = Allocation.createTyped(mRS, t);

        mVectorIn = Allocation.createSized(mRS, Element.F32_4(mRS), 500);
        mVectorOut = Allocation.createSized(mRS, Element.F32_4(mRS), 500);

        mScript = new ScriptC_large_matrix(mRS, getResources(), R.raw.large_matrix);
        mScript.set_gMatrix(mMatrix);

        mRS.finish();
        long dt = java.lang.System.currentTimeMillis();

        for (int i=0; i<100; i++) {
            mScript.forEach_multiply_row(mVectorIn, mVectorOut);
        }
        mRS.finish();
        dt = (java.lang.System.currentTimeMillis() - dt);

        Log.v("rs", "LargeMatrix  mult time ms " + dt);

        float gflop = 2000.f * 2000.f * 2.f / dt / 1000000.f;
        gflop *= 100;
        Log.v("rs", "LargeMatrix  gflop " + gflop);
    }

}
