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

import android.support.v8.renderscript.*;
import android.util.Log;

public class ColorCube extends TestBase {
    private Allocation mCube;
    private ScriptC_colorcube mScript;
    private ScriptIntrinsic3DLUT mIntrinsic;
    private boolean mUseIntrinsic;

    public ColorCube(boolean useIntrinsic) {
        mUseIntrinsic = useIntrinsic;
    }

    private void initCube() {
        final int sx = 32;
        final int sy = 32;
        final int sz = 16;

        Type.Builder tb = new Type.Builder(mRS, Element.U8_4(mRS));
        tb.setX(sx);
        tb.setY(sy);
        tb.setZ(sz);
        Type t = tb.create();
        mCube = Allocation.createTyped(mRS, t);

        int dat[] = new int[sx * sy * sz];
        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++ ) {
                    int v = 0xff000000;
                    v |= (0xff * x / (sx - 1));
                    v |= (0xff * y / (sy - 1)) << 8;
                    v |= (0xff * z / (sz - 1)) << 16;
                    dat[z*sy*sx + y*sx + x] = v;
                }
            }
        }

        mCube.copyFromUnchecked(dat);
    }

    public void createTest(android.content.res.Resources res) {
        mScript = new ScriptC_colorcube(mRS, res, R.raw.colorcube);
        mIntrinsic = ScriptIntrinsic3DLUT.create(mRS, Element.U8_4(mRS));

        initCube();
        mScript.invoke_setCube(mCube);
        mIntrinsic.setLUT(mCube);
    }

    public void runTest() {
        if (mUseIntrinsic) {
            mIntrinsic.forEach(mInPixelsAllocation, mOutPixelsAllocation);
        } else {
            mScript.forEach_root(mInPixelsAllocation, mOutPixelsAllocation);
        }
    }

}
