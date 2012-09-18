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
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicConvolve3x3;
import android.renderscript.ScriptIntrinsicColorMatrix;
import android.renderscript.Type;
import android.renderscript.Matrix4f;
import android.renderscript.ScriptGroup;
import android.util.Log;

public class GroupTest extends TestBase {
    private ScriptIntrinsicConvolve3x3 mConvolve;
    private ScriptIntrinsicColorMatrix mMatrix;

    private Allocation mScratchPixelsAllocation1;
    private ScriptGroup mGroup;

    private int mWidth;
    private int mHeight;
    private boolean mUseNative;


    public GroupTest(boolean useNative) {
        mUseNative = useNative;
    }

    public void createTest(android.content.res.Resources res) {
        mWidth = mInPixelsAllocation.getType().getX();
        mHeight = mInPixelsAllocation.getType().getY();

        mConvolve = ScriptIntrinsicConvolve3x3.create(mRS, Element.U8_4(mRS));
        mMatrix = ScriptIntrinsicColorMatrix.create(mRS, Element.U8_4(mRS));

        float f[] = new float[9];
        f[0] =  0.f;    f[1] = -1.f;    f[2] =  0.f;
        f[3] = -1.f;    f[4] =  5.f;    f[5] = -1.f;
        f[6] =  0.f;    f[7] = -1.f;    f[8] =  0.f;
        mConvolve.setCoefficients(f);

        Matrix4f m = new Matrix4f();
        m.set(1, 0, 0.2f);
        m.set(1, 1, 0.9f);
        m.set(1, 2, 0.2f);
        mMatrix.setColorMatrix(m);

        Type.Builder tb = new Type.Builder(mRS, Element.U8_4(mRS));
        tb.setX(mWidth);
        tb.setY(mHeight);
        Type connect = tb.create();

        if (mUseNative) {
            ScriptGroup.Builder b = new ScriptGroup.Builder(mRS);
            b.addKernel(mConvolve.getKernelID());
            b.addKernel(mMatrix.getKernelID());
            b.addConnection(connect, mConvolve.getKernelID(), mMatrix.getKernelID());
            mGroup = b.create();
        } else {
            mScratchPixelsAllocation1 = Allocation.createTyped(mRS, connect);
        }
    }

    public void runTest() {
        mConvolve.setInput(mInPixelsAllocation);
        if (mUseNative) {
            mGroup.setOutput(mMatrix.getKernelID(), mOutPixelsAllocation);
            mGroup.execute();
        } else {
            mConvolve.forEach(mScratchPixelsAllocation1);
            mMatrix.forEach(mScratchPixelsAllocation1, mOutPixelsAllocation);
        }
    }

}
