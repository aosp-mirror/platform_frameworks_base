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

package com.android.film;

import java.io.Writer;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.util.Log;

import android.renderscript.*;

public class FilmRS {
    class StripPosition {
        public float translate;
        public float rotate;
        public float focus;
        public int triangleOffsetCount;
    }
    StripPosition mPos = new StripPosition();


    private final int STATE_LAST_FOCUS = 1;

    public FilmRS() {
    }

    public void init(RenderScriptGL rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;
        initRS();
    }

    public void setFilmStripPosition(int x, int y)
    {
        if (x < 50) {
            x = 50;
        }
        if (x > 270) {
            x = 270;
        }

        float anim = ((float)x-50) / 270.f;
        mPos.translate = 2f * anim + 0.5f;   // translation
        mPos.rotate = (anim * 40);  // rotation
        mPos.focus = ((float)y) / 16.f - 10.f;  // focusPos
        mPos.triangleOffsetCount = mFSM.mTriangleOffsetsCount;
        mAllocPos.data(mPos);
    }


    private Resources mRes;
    private RenderScriptGL mRS;
    private Script mScriptStrip;
    private Script mScriptImage;
    private Sampler mSampler;
    private ProgramStore mPSBackground;
    private ProgramStore mPSImages;
    private ProgramFragment mPFBackground;
    private ProgramFragment mPFImages;
    private ProgramVertex mPVBackground;
    private ProgramVertex mPVImages;
    private ProgramVertex.MatrixAllocation mPVA;
    private Type mStripPositionType;

    private Allocation mImages[];
    private Allocation mAllocIDs;
    private Allocation mAllocPos;
    private Allocation mAllocState;
    private Allocation mAllocPV;
    private Allocation mAllocOffsetsTex;
    private Allocation mAllocOffsets;

    private SimpleMesh mMesh;
    private Light mLight;

    private FilmStripMesh mFSM;

    private int[] mBufferIDs;
    private float[] mBufferPos = new float[3];
    private int[] mBufferState;

    private void initPFS() {
        ProgramStore.Builder b = new ProgramStore.Builder(mRS, null, null);

        b.setDepthFunc(ProgramStore.DepthFunc.LESS);
        b.setDitherEnable(true);
        b.setDepthMask(true);
        mPSBackground = b.create();
        mPSBackground.setName("PSBackground");

        b.setDepthFunc(ProgramStore.DepthFunc.EQUAL);
        b.setDitherEnable(false);
        b.setDepthMask(false);
        b.setBlendFunc(ProgramStore.BlendSrcFunc.ONE,
                       ProgramStore.BlendDstFunc.ONE);
        mPSImages = b.create();
        mPSImages.setName("PSImages");
    }

    private void initPF() {
        Sampler.Builder bs = new Sampler.Builder(mRS);
        bs.setMin(Sampler.Value.LINEAR);//_MIP_LINEAR);
        bs.setMag(Sampler.Value.LINEAR);
        bs.setWrapS(Sampler.Value.CLAMP);
        bs.setWrapT(Sampler.Value.WRAP);
        mSampler = bs.create();

        ProgramFragment.Builder b = new ProgramFragment.Builder(mRS);
        mPFBackground = b.create();
        mPFBackground.setName("PFBackground");

        b = new ProgramFragment.Builder(mRS);
        b.setTexture(ProgramFragment.Builder.EnvMode.REPLACE,
                     ProgramFragment.Builder.Format.RGBA, 0);
        mPFImages = b.create();
        mPFImages.bindSampler(mSampler, 0);
        mPFImages.setName("PFImages");
    }

    private void initPV() {
        mLight = (new Light.Builder(mRS)).create();
        mLight.setPosition(0, -0.5f, -1.0f);

        ProgramVertex.Builder pvb = new ProgramVertex.Builder(mRS, null, null);
        //pvb.addLight(mLight);
        mPVBackground = pvb.create();
        mPVBackground.setName("PVBackground");

        pvb = new ProgramVertex.Builder(mRS, null, null);
        pvb.setTextureMatrixEnable(true);
        mPVImages = pvb.create();
        mPVImages.setName("PVImages");
    }

    private void loadImages() {
        mBufferIDs = new int[13];
        mImages = new Allocation[13];
        mAllocIDs = Allocation.createSized(mRS,
            Element.createUser(mRS, Element.DataType.FLOAT_32),
            mBufferIDs.length);

        Element ie = Element.createPixel(mRS, Element.DataType.UNSIGNED_5_6_5, Element.DataKind.PIXEL_RGB);
        mImages[0] = Allocation.createFromBitmapResourceBoxed(mRS, mRes, R.drawable.p01, ie, true);
        mImages[1] = Allocation.createFromBitmapResourceBoxed(mRS, mRes, R.drawable.p02, ie, true);
        mImages[2] = Allocation.createFromBitmapResourceBoxed(mRS, mRes, R.drawable.p03, ie, true);
        mImages[3] = Allocation.createFromBitmapResourceBoxed(mRS, mRes, R.drawable.p04, ie, true);
        mImages[4] = Allocation.createFromBitmapResourceBoxed(mRS, mRes, R.drawable.p05, ie, true);
        mImages[5] = Allocation.createFromBitmapResourceBoxed(mRS, mRes, R.drawable.p06, ie, true);
        mImages[6] = Allocation.createFromBitmapResourceBoxed(mRS, mRes, R.drawable.p07, ie, true);
        mImages[7] = Allocation.createFromBitmapResourceBoxed(mRS, mRes, R.drawable.p08, ie, true);
        mImages[8] = Allocation.createFromBitmapResourceBoxed(mRS, mRes, R.drawable.p09, ie, true);
        mImages[9] = Allocation.createFromBitmapResourceBoxed(mRS, mRes, R.drawable.p10, ie, true);
        mImages[10] = Allocation.createFromBitmapResourceBoxed(mRS, mRes, R.drawable.p11, ie, true);
        mImages[11] = Allocation.createFromBitmapResourceBoxed(mRS, mRes, R.drawable.p12, ie, true);
        mImages[12] = Allocation.createFromBitmapResourceBoxed(mRS, mRes, R.drawable.p13, ie, true);

        int black[] = new int[1024];
        for(int ct=0; ct < mImages.length; ct++) {
            Allocation.Adapter2D a = mImages[ct].createAdapter2D();

            int size = 512;
            int mip = 0;
            while(size >= 2) {
                a.subData(0, 0, 2, size, black);
                a.subData(size-2, 0, 2, size, black);
                a.subData(0, 0, size, 2, black);
                a.subData(0, size-2, size, 2, black);
                size >>= 1;
                mip++;
                a.setConstraint(Dimension.LOD, mip);
            }

            mImages[ct].uploadToTexture(1);
            mBufferIDs[ct] = mImages[ct].getID();
        }
        mAllocIDs.data(mBufferIDs);
    }

    private void initState()
    {
        mBufferState = new int[10];
        mAllocState = Allocation.createSized(mRS,
            Element.createUser(mRS, Element.DataType.FLOAT_32),
            mBufferState.length);
        mBufferState[STATE_LAST_FOCUS] = -1;
        mAllocState.data(mBufferState);
    }

    private void initRS() {
        mFSM = new FilmStripMesh();
        mMesh = mFSM.init(mRS);
        mMesh.setName("mesh");

        initPFS();
        initPF();
        initPV();

        Log.e("rs", "Done loading named");

        mStripPositionType = Type.createFromClass(mRS, StripPosition.class, 1);

        ScriptC.Builder sb = new ScriptC.Builder(mRS);
        sb.setScript(mRes, R.raw.filmstrip);
        sb.setRoot(true);
        sb.setType(mStripPositionType, "Pos", 1);
        mScriptStrip = sb.create();
        mScriptStrip.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        mAllocPos = Allocation.createTyped(mRS, mStripPositionType);

        loadImages();
        initState();

        mPVA = new ProgramVertex.MatrixAllocation(mRS);
        mPVBackground.bindAllocation(mPVA);
        mPVImages.bindAllocation(mPVA);
        mPVA.setupProjectionNormalized(320, 480);


        mScriptStrip.bindAllocation(mAllocIDs, 0);
        mScriptStrip.bindAllocation(mAllocPos, 1);
        mScriptStrip.bindAllocation(mAllocState, 2);
        mScriptStrip.bindAllocation(mPVA.mAlloc, 3);


        mAllocOffsets = Allocation.createSized(mRS,
            Element.createUser(mRS, Element.DataType.SIGNED_32), mFSM.mTriangleOffsets.length);
        mAllocOffsets.data(mFSM.mTriangleOffsets);
        mScriptStrip.bindAllocation(mAllocOffsets, 4);

        mAllocOffsetsTex = Allocation.createSized(mRS,
            Element.createUser(mRS, Element.DataType.FLOAT_32), mFSM.mTriangleOffsetsTex.length);
        mAllocOffsetsTex.data(mFSM.mTriangleOffsetsTex);
        mScriptStrip.bindAllocation(mAllocOffsetsTex, 5);

        setFilmStripPosition(0, 0);
        mRS.contextBindRootScript(mScriptStrip);
    }
}



