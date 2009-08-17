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

import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;


public class FountainRS {
    public static final int PART_COUNT = 20000;

    static class SomeData {
        public int x;
        public int y;
        public int rate;
        public int count;
        public float r;
        public float g;
        public float b;
    }

    public FountainRS() {
    }

    public void init(RenderScript rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;
        initRS();
    }

    public void newTouchPosition(int x, int y, int rate) {
        if (mSD.rate == 0) {
            mSD.r = ((x & 0x1) != 0) ? 0.f : 1.f;
            mSD.g = ((x & 0x2) != 0) ? 0.f : 1.f;
            mSD.b = ((x & 0x4) != 0) ? 0.f : 1.f;
            if ((mSD.r + mSD.g + mSD.b) < 0.9f) {
                mSD.r = 0.8f;
                mSD.g = 0.5f;
                mSD.b = 1.f;
            }
        }
        mSD.rate = rate;
        mSD.x = x;
        mSD.y = y;
        mIntAlloc.data(mSD);
    }


    /////////////////////////////////////////

    private Resources mRes;

    private RenderScript mRS;
    private Allocation mIntAlloc;
    private Allocation mPartAlloc;
    private Script mScript;
    private SimpleMesh mSM;
    private SomeData mSD;
    private Type mSDType;

    private void initRS() {
        mSD = new SomeData();
        mSDType = Type.createFromClass(mRS, SomeData.class, 1, "SomeData");
        mIntAlloc = Allocation.createTyped(mRS, mSDType);
        mSD.count = PART_COUNT;
        mIntAlloc.data(mSD);

        Element.Builder eb = new Element.Builder(mRS);
        eb.addFloat(Element.DataKind.USER); //dx
        eb.addFloat(Element.DataKind.USER); //dy
        eb.addFloatXY();
        eb.addUNorm8RGBA();
        Element primElement = eb.create();


        SimpleMesh.Builder smb = new SimpleMesh.Builder(mRS);
        int vtxSlot = smb.addVertexType(primElement, PART_COUNT);
        smb.setPrimitive(Primitive.POINT);
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
        sb.setType(mSDType, "Control", 0);
        mScript = sb.create();
        mScript.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        mScript.bindAllocation(mIntAlloc, 0);
        mScript.bindAllocation(mPartAlloc, 1);
        mRS.contextBindRootScript(mScript);
    }

}


