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
import android.renderscript.Matrix4f;
import android.renderscript.RenderScript;
import android.renderscript.Script;
import android.renderscript.ScriptC;
import android.renderscript.ScriptGroup;
import android.renderscript.ScriptIntrinsicColorMatrix;
import android.renderscript.Type;
import android.util.Log;

public class ColorMatrix extends TestBase {
    private ScriptC_colormatrix mScript;
    private ScriptIntrinsicColorMatrix mIntrinsic;
    private boolean mUseIntrinsic;
    private boolean mUseGrey;

    public ColorMatrix(boolean useIntrinsic, boolean useGrey) {
        mUseIntrinsic = useIntrinsic;
        mUseGrey = useGrey;
    }

    public void createTest(android.content.res.Resources res) {
        Matrix4f m = new Matrix4f();
        m.set(1, 0, 0.2f);
        m.set(1, 1, 0.9f);
        m.set(1, 2, 0.2f);

        if (mUseIntrinsic) {
            mIntrinsic = ScriptIntrinsicColorMatrix.create(mRS, Element.U8_4(mRS));
            if (mUseGrey) {
                mIntrinsic.setGreyscale();
            } else {
                mIntrinsic.setColorMatrix(m);
            }
        } else {
            mScript = new ScriptC_colormatrix(mRS, res, R.raw.colormatrix);
            mScript.invoke_setMatrix(m);
        }
    }

    public void runTest() {
        if (mUseIntrinsic) {
            mIntrinsic.forEach(mInPixelsAllocation, mOutPixelsAllocation);
        } else {
            mScript.forEach_root(mInPixelsAllocation, mOutPixelsAllocation);
        }
    }

}
