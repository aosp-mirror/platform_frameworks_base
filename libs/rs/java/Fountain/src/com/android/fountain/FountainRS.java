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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import android.renderscript.RenderScript;
import android.renderscript.ProgramVertexAlloc;
import android.renderscript.Element;
import android.renderscript.Allocation;
import android.renderscript.Script;
import android.renderscript.ScriptC;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;

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
    private Allocation mIntAlloc;
    private Allocation mPartAlloc;
    private Allocation mVertAlloc;
    private Script mScript;
    private ProgramStore mPFS;
    private ProgramFragment mPF;

    private Bitmap mBackground;

    int mParams[] = new int[10];

    private void initRS() {
        int partCount = 1024;

        mIntAlloc = Allocation.createSized(mRS, Element.USER_I32, 10);
        mPartAlloc = Allocation.createSized(mRS, Element.USER_I32, partCount * 3 * 3);
        mPartAlloc.setName("PartBuffer");
        mVertAlloc = Allocation.createSized(mRS, Element.USER_I32, partCount * 5 + 1);

        ProgramStore.Builder bs = new ProgramStore.Builder(mRS, null, null);
        bs.setBlendFunc(ProgramStore.BlendSrcFunc.SRC_ALPHA, ProgramStore.BlendDstFunc.ONE);
        bs.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
        bs.setDepthMask(false);
        bs.setDitherEnable(false);
        mPFS = bs.create();
        mPFS.setName("PFSBlend");

        ProgramFragment.Builder bf = new ProgramFragment.Builder(mRS, null, null);
        mPF = bf.create();
        mPF.setName("PgmFragParts");

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

        ScriptC.Builder sb = new ScriptC.Builder(mRS);
        sb.setScript(mRes, R.raw.fountain);
        sb.setRoot(true);
        mScript = sb.create();
        mScript.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        mScript.bindAllocation(mIntAlloc, 0);
        mScript.bindAllocation(mPartAlloc, 1);
        mScript.bindAllocation(mVertAlloc, 2);
        mRS.contextBindRootScript(mScript);
    }

}


