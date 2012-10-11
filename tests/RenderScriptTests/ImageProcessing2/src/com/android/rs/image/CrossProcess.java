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

public class CrossProcess extends TestBase {
    private ScriptIntrinsicLUT mIntrinsic;

    public void createTest(android.content.res.Resources res) {
        mIntrinsic = ScriptIntrinsicLUT.create(mRS, Element.U8_4(mRS));
        for (int ct=0; ct < 256; ct++) {
            float f = ((float)ct) / 255.f;

            float r = f;
            if (r < 0.5f) {
                r = 4.0f * r * r * r;
            } else {
                r = 1.0f - r;
                r = 1.0f - (4.0f * r * r * r);
            }
            mIntrinsic.setRed(ct, (int)(r * 255.f + 0.5f));

            float g = f;
            if (g < 0.5f) {
                g = 2.0f * g * g;
            } else {
                g = 1.0f - g;
                g = 1.0f - (2.0f * g * g);
            }
            mIntrinsic.setGreen(ct, (int)(g * 255.f + 0.5f));

            float b = f * 0.5f + 0.25f;
            mIntrinsic.setBlue(ct, (int)(b * 255.f + 0.5f));
        }

    }

    public void runTest() {
        mIntrinsic.forEach(mInPixelsAllocation, mOutPixelsAllocation);
    }

}
