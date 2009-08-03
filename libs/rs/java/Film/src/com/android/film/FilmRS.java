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

import android.renderscript.Matrix;
import android.renderscript.ProgramVertexAlloc;
import android.renderscript.RenderScript;
import android.renderscript.Element;
import android.renderscript.Allocation;
import android.renderscript.Dimension;

public class FilmRS {
    private final int POS_TRANSLATE = 0;
    private final int POS_ROTATE = 1;
    private final int POS_FOCUS = 2;

    private final int STATE_TRIANGLE_OFFSET_COUNT = 0;
    private final int STATE_LAST_FOCUS = 1;

    public FilmRS() {
    }

    public void init(RenderScript rs, Resources res, int width, int height) {
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
        mBufferPos[POS_TRANSLATE] = 2f * anim + 0.5f;   // translation
        mBufferPos[POS_ROTATE] = (anim * 40);  // rotation
        mBufferPos[POS_FOCUS] = ((float)y) / 16.f - 10.f;  // focusPos
        mAllocPos.data(mBufferPos);
    }


    private Resources mRes;
    private RenderScript mRS;
    private RenderScript.Script mScriptStrip;
    private RenderScript.Script mScriptImage;
    private Element mElementVertex;
    private Element mElementIndex;
    private RenderScript.Sampler mSampler;
    private RenderScript.ProgramFragmentStore mPFSBackground;
    private RenderScript.ProgramFragmentStore mPFSImages;
    private RenderScript.ProgramFragment mPFBackground;
    private RenderScript.ProgramFragment mPFImages;
    private RenderScript.ProgramVertex mPVBackground;
    private RenderScript.ProgramVertex mPVImages;
    private ProgramVertexAlloc mPVA;

    private Allocation mImages[];
    private Allocation mAllocIDs;
    private Allocation mAllocPos;
    private Allocation mAllocState;
    private Allocation mAllocPV;
    private Allocation mAllocOffsetsTex;
    private Allocation mAllocOffsets;

    private RenderScript.TriangleMesh mMesh;
    private RenderScript.Light mLight;

    private FilmStripMesh mFSM;

    private int[] mBufferIDs;
    private float[] mBufferPos = new float[3];
    private int[] mBufferState;

    private void initPFS() {
        mRS.programFragmentStoreBegin(null, null);
        mRS.programFragmentStoreDepthFunc(RenderScript.DepthFunc.LESS);
        mRS.programFragmentStoreDitherEnable(true);
        mRS.programFragmentStoreDepthMask(true);
        mPFSBackground = mRS.programFragmentStoreCreate();
        mPFSBackground.setName("PFSBackground");

        mRS.programFragmentStoreBegin(null, null);
        mRS.programFragmentStoreDepthFunc(RenderScript.DepthFunc.EQUAL);
        mRS.programFragmentStoreDitherEnable(false);
        mRS.programFragmentStoreDepthMask(false);
        mRS.programFragmentStoreBlendFunc(RenderScript.BlendSrcFunc.ONE,
                                          RenderScript.BlendDstFunc.ONE);
        mPFSImages = mRS.programFragmentStoreCreate();
        mPFSImages.setName("PFSImages");
    }

    private void initPF() {
        mRS.samplerBegin();
        mRS.samplerSet(RenderScript.SamplerParam.FILTER_MIN,
                       RenderScript.SamplerValue.LINEAR);//_MIP_LINEAR);
        mRS.samplerSet(RenderScript.SamplerParam.FILTER_MAG,
                       RenderScript.SamplerValue.LINEAR);
        mRS.samplerSet(RenderScript.SamplerParam.WRAP_MODE_S,
                       RenderScript.SamplerValue.CLAMP);
        mRS.samplerSet(RenderScript.SamplerParam.WRAP_MODE_T,
                       RenderScript.SamplerValue.WRAP);
        mSampler = mRS.samplerCreate();


        mRS.programFragmentBegin(null, null);
        mPFBackground = mRS.programFragmentCreate();
        mPFBackground.setName("PFBackground");

        mRS.programFragmentBegin(null, null);
        mRS.programFragmentSetTexEnable(0, true);
        mRS.programFragmentSetTexEnvMode(0, RenderScript.EnvMode.REPLACE);
        mPFImages = mRS.programFragmentCreate();
        mPFImages.bindSampler(mSampler, 0);
        mPFImages.setName("PFImages");
    }

    private void initPV() {
        mRS.lightBegin();
        mLight = mRS.lightCreate();
        mLight.setPosition(0, -0.5f, -1.0f);

        mRS.programVertexBegin(null, null);
        mRS.programVertexAddLight(mLight);
        mPVBackground = mRS.programVertexCreate();
        mPVBackground.setName("PVBackground");

        mRS.programVertexBegin(null, null);
        mRS.programVertexSetTextureMatrixEnable(true);
        mPVImages = mRS.programVertexCreate();
        mPVImages.setName("PVImages");
    }

    private void loadImages() {
        mBufferIDs = new int[13];
        mImages = new Allocation[13];
        mAllocIDs = Allocation.createSized(mRS,
            Element.USER_FLOAT, mBufferIDs.length);

        Element ie = Element.RGB_565;
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
            a.destroy();

            mImages[ct].uploadToTexture(1);
            mBufferIDs[ct] = mImages[ct].getID();
        }
        mAllocIDs.data(mBufferIDs);
    }

    private void initState()
    {
        mBufferState = new int[10];
        mAllocState = Allocation.createSized(mRS,
            Element.USER_FLOAT, mBufferState.length);

        mBufferState[STATE_TRIANGLE_OFFSET_COUNT] = mFSM.mTriangleOffsetsCount;
        mBufferState[STATE_LAST_FOCUS] = -1;

        mAllocState.data(mBufferState);
    }

    private void initRS() {
        mElementVertex = Element.NORM_ST_XYZ_F32;
        mElementIndex = Element.INDEX_16;

        mRS.triangleMeshBegin(mElementVertex, mElementIndex);
        mFSM = new FilmStripMesh();
        mFSM.init(mRS);
        mMesh = mRS.triangleMeshCreate();
        mMesh.setName("mesh");

        initPFS();
        initPF();
        initPV();

        Log.e("rs", "Done loading named");

        mRS.scriptCBegin();
        mRS.scriptCSetClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        mRS.scriptCSetScript(mRes, R.raw.filmstrip);
        mRS.scriptCSetRoot(true);
        mScriptStrip = mRS.scriptCCreate();

        mAllocPos = Allocation.createSized(mRS,
            Element.USER_FLOAT, mBufferPos.length);

        loadImages();
        initState();

        mPVA = new ProgramVertexAlloc(mRS);
        mPVBackground.bindAllocation(0, mPVA.mAlloc);
        mPVImages.bindAllocation(0, mPVA.mAlloc);
        mPVA.setupProjectionNormalized(320, 480);


        mScriptStrip.bindAllocation(mAllocIDs, 0);
        mScriptStrip.bindAllocation(mAllocPos, 1);
        mScriptStrip.bindAllocation(mAllocState, 2);
        mScriptStrip.bindAllocation(mPVA.mAlloc, 3);


        mAllocOffsets = Allocation.createSized(mRS,
            Element.USER_I32, mFSM.mTriangleOffsets.length);
        mAllocOffsets.data(mFSM.mTriangleOffsets);
        mScriptStrip.bindAllocation(mAllocOffsets, 4);

        mAllocOffsetsTex = Allocation.createSized(mRS,
            Element.USER_FLOAT, mFSM.mTriangleOffsetsTex.length);
        mAllocOffsetsTex.data(mFSM.mTriangleOffsetsTex);
        mScriptStrip.bindAllocation(mAllocOffsetsTex, 5);

        setFilmStripPosition(0, 0);
        mRS.contextBindRootScript(mScriptStrip);
    }
}



