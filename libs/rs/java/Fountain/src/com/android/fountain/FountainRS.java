/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.fountain;

import java.io.Writer;

import android.renderscript.RenderScript;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

public class FountainRS {

    public FountainRS() {
    }

    public void init(RenderScript rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;
        initRS();
    }

    public void newTouchPosition(int x, int y) {
        mParams[0] = 1;
        mParams[1] = x;
        mParams[2] = y;
        mIntAlloc.subData1D(2, 3, mParams);
    }


    /////////////////////////////////////////

    private Resources mRes;

    private RenderScript mRS;
    private RenderScript.Allocation mIntAlloc;
    private RenderScript.Allocation mPartAlloc;
    private RenderScript.Allocation mVertAlloc;
    private RenderScript.Script mScript;
    private RenderScript.ProgramFragmentStore mPFS;
    private RenderScript.ProgramFragment mPF;
    private RenderScript.ProgramFragment mPF2;
    private RenderScript.Allocation mTexture;
    private RenderScript.Sampler mSampler;

    private Bitmap mBackground;

    int mParams[] = new int[10];

    private void initRS() {
        int partCount = 1024;

        mIntAlloc = mRS.allocationCreatePredefSized(RenderScript.ElementPredefined.USER_I32, 10);
        mPartAlloc = mRS.allocationCreatePredefSized(RenderScript.ElementPredefined.USER_I32, partCount * 3 * 3);
        mPartAlloc.setName("PartBuffer");
        mVertAlloc = mRS.allocationCreatePredefSized(RenderScript.ElementPredefined.USER_I32, partCount * 5 + 1);

        {
            Drawable d = mRes.getDrawable(R.drawable.gadgets_clock_mp3);
            BitmapDrawable bd = (BitmapDrawable)d;
            Bitmap b = bd.getBitmap();
            mTexture = mRS.allocationCreateFromBitmap(b,
                                                      RenderScript.ElementPredefined.RGB_565,
                                                      true);
            mTexture.uploadToTexture(0);
        }

        mRS.programFragmentStoreBegin(null, null);
        mRS.programFragmentStoreBlendFunc(RenderScript.BlendSrcFunc.SRC_ALPHA, RenderScript.BlendDstFunc.ONE);
        mRS.programFragmentStoreDepthFunc(RenderScript.DepthFunc.ALWAYS);
        mPFS = mRS.programFragmentStoreCreate();
        mPFS.setName("MyBlend");
        mRS.contextBindProgramFragmentStore(mPFS);

        mRS.samplerBegin();
        mRS.samplerSet(RenderScript.SamplerParam.FILTER_MAG, RenderScript.SamplerValue.LINEAR);
        mRS.samplerSet(RenderScript.SamplerParam.FILTER_MIN, RenderScript.SamplerValue.LINEAR);
        mSampler = mRS.samplerCreate();


        mRS.programFragmentBegin(null, null);
        mPF = mRS.programFragmentCreate();
        mPF.setName("PgmFragParts");

        mRS.programFragmentBegin(null, null);
        mRS.programFragmentSetTexEnable(0, true);
        mPF2 = mRS.programFragmentCreate();
        mRS.contextBindProgramFragment(mPF2);
        mPF2.bindTexture(mTexture, 0);
        mPF2.bindSampler(mSampler, 0);
        mPF2.setName("PgmFragBackground");

        mParams[0] = 0;
        mParams[1] = partCount;
        mParams[2] = 0;
        mParams[3] = 0;
        mParams[4] = 0;
        mIntAlloc.data(mParams);

        int t2[] = new int[partCount * 4*3];
        for (int ct=0; ct < t2.length; ct++) {
            t2[ct] = 0;
        }
        mPartAlloc.data(t2);

        mRS.scriptCBegin();
        mRS.scriptCSetClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        mRS.scriptCSetScript(mRes, R.raw.fountain);
        mRS.scriptCSetRoot(true);
        mScript = mRS.scriptCCreate();

        mScript.bindAllocation(mIntAlloc, 0);
        mScript.bindAllocation(mPartAlloc, 1);
        mScript.bindAllocation(mVertAlloc, 2);
        mRS.contextBindRootScript(mScript);
    }

}


