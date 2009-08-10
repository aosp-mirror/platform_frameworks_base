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
import android.renderscript.ProgramVertex;
import android.renderscript.Element;
import android.renderscript.Allocation;
import android.renderscript.Script;
import android.renderscript.ScriptC;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.SimpleMesh;
import android.renderscript.Type;
import android.renderscript.Primitive;


public class FountainRS {
    public static final int PART_COUNT = 4000;

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
    private SimpleMesh mSM;

    private Bitmap mBackground;

    int mParams[] = new int[10];

    private void initRS() {
        mIntAlloc = Allocation.createSized(mRS, Element.USER_I32, 10);
        mVertAlloc = Allocation.createSized(mRS, Element.USER_I32, PART_COUNT * 5 + 1);

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
        mParams[1] = PART_COUNT;
        mParams[2] = 0;
        mParams[3] = 0;
        mParams[4] = 0;
        mParams[5] = 0;
        mParams[6] = 0;
        mIntAlloc.data(mParams);

        Element.Builder eb = new Element.Builder(mRS);
        eb.add(Element.DataType.UNSIGNED, Element.DataKind.RED, true, 8);
        eb.add(Element.DataType.UNSIGNED, Element.DataKind.GREEN, true, 8);
        eb.add(Element.DataType.UNSIGNED, Element.DataKind.BLUE, true, 8);
        eb.add(Element.DataType.UNSIGNED, Element.DataKind.ALPHA, true, 8);
        eb.add(Element.DataType.FLOAT, Element.DataKind.X, false, 32);
        eb.add(Element.DataType.FLOAT, Element.DataKind.Y, false, 32);
        Element primElement = eb.create();

        SimpleMesh.Builder smb = new SimpleMesh.Builder(mRS);
        int vtxSlot = smb.addVertexType(primElement, PART_COUNT * 3);
        smb.setPrimitive(Primitive.TRIANGLE);
        mSM = smb.create();
        mSM.setName("PartMesh");

        mPartAlloc = mSM.createVertexAllocation(vtxSlot);
        mPartAlloc.setName("PartBuffer");
        mSM.bindVertexAllocation(mPartAlloc, 0);

        // All setup of named objects should be done by this point
        // because we are about to compile the script.
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


