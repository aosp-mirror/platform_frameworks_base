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

package com.android.rs.image;

import java.lang.Math;

import android.view.Surface;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicConvolve3x3;
import android.renderscript.ScriptIntrinsicColorMatrix;
import android.renderscript.Type;
import android.renderscript.Matrix4f;
import android.renderscript.ScriptGroup;
import android.util.Log;

public class UsageIO extends TestBase {
    private ScriptIntrinsicColorMatrix mMatrix;

    private Allocation mScratchPixelsAllocation1;
    private Allocation mScratchPixelsAllocation2;

    public UsageIO() {
    }

    public void createTest(android.content.res.Resources res) {
        mMatrix = ScriptIntrinsicColorMatrix.create(mRS, Element.U8_4(mRS));

        Matrix4f m = new Matrix4f();
        m.set(1, 0, 0.2f);
        m.set(1, 1, 0.9f);
        m.set(1, 2, 0.2f);
        mMatrix.setColorMatrix(m);

        Type connect = mInPixelsAllocation.getType();

        mScratchPixelsAllocation1 = Allocation.createTyped(mRS, connect, Allocation.USAGE_IO_OUTPUT | Allocation.USAGE_SCRIPT);
        mScratchPixelsAllocation2 = Allocation.createTyped(mRS, connect, Allocation.USAGE_IO_INPUT | Allocation.USAGE_SCRIPT);

        Surface s = mScratchPixelsAllocation2.getSurface();
        mScratchPixelsAllocation1.setSurface(s);
    }

    public void runTest() {
        mScratchPixelsAllocation1.copyFrom(mInPixelsAllocation);
        mScratchPixelsAllocation1.ioSend();
        mScratchPixelsAllocation2.ioReceive();
        mMatrix.forEach(mScratchPixelsAllocation2, mOutPixelsAllocation);
    }

}
