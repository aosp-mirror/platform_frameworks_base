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

package com.android.rollo;

import java.io.Writer;

import android.renderscript.RenderScript;
import android.renderscript.ProgramVertexAlloc;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.KeyEvent;
import android.view.MotionEvent;

public class RolloRS {

    public RolloRS() {
    }

    public void init(RenderScript rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;
        initNamed();
        initRS();
    }


    private Resources mRes;
    private RenderScript mRS;


    private RenderScript.Script mScript;

    private RenderScript.Sampler mSampler;
    private RenderScript.ProgramFragmentStore mPFSBackground;
    private RenderScript.ProgramFragmentStore mPFSImages;
    private RenderScript.ProgramFragment mPFBackground;
    private RenderScript.ProgramFragment mPFImages;
    private RenderScript.ProgramVertex mPV;
    private ProgramVertexAlloc mPVAlloc;

    private RenderScript.Allocation mAllocEnv;
    private RenderScript.Allocation mAllocPos;
    private RenderScript.Allocation mAllocState;
    //private RenderScript.Allocation mAllocPV;
    private RenderScript.TriangleMesh mMeshCard;
    private RenderScript.TriangleMesh mMeshTab;

    private float[] mBufferPos;
    //private float[] mBufferPV;

    private void initNamed() {
        mMeshTab = RolloMesh.createTab(mRS);
        mMeshTab.setName("MeshTab");
        mMeshCard = RolloMesh.createCard(mRS);
        mMeshCard.setName("MeshCard");
        Log.e("rs", "Done loading strips");

        mRS.samplerBegin();
        mRS.samplerSet(RenderScript.SamplerParam.FILTER_MIN,
                       RenderScript.SamplerValue.LINEAR_MIP_LINEAR);
        mRS.samplerSet(RenderScript.SamplerParam.WRAP_MODE_S,
                       RenderScript.SamplerValue.CLAMP);
        mRS.samplerSet(RenderScript.SamplerParam.WRAP_MODE_T,
                       RenderScript.SamplerValue.CLAMP);
        mSampler = mRS.samplerCreate();


        mRS.programFragmentBegin(null, null);
        mRS.programFragmentSetTexEnable(0, true);
        //mRS.programFragmentSetEnvMode(0, RS_TEX_ENV_MODE_REPLACE);
        mPFImages = mRS.programFragmentCreate();
        mPFImages.setName("PF");
        mPFImages.bindSampler(mSampler, 0);

        mRS.programFragmentStoreBegin(null, null);
        mRS.programFragmentStoreDepthFunc(RenderScript.DepthFunc.ALWAYS);
        mRS.programFragmentStoreDitherEnable(true);
        mPFSBackground = mRS.programFragmentStoreCreate();
        mPFSBackground.setName("PFSBackground");

        /*
        mRS.programFragmentStoreBegin(null, null);
        mRS.programFragmentStoreDepthFunc(RenderScript.DepthFunc.EQUAL);
        mRS.programFragmentStoreDitherEnable(false);
        mRS.programFragmentStoreDepthMask(false);
        mRS.programFragmentStoreBlendFunc(RenderScript.BlendSrcFunc.ONE, 
                                          RenderScript.BlendDstFunc.ONE);
        mPFSImages = mRS.programFragmentStoreCreate();
        mPFSImages.setName("PFSImages");
*/


        mPVAlloc = new ProgramVertexAlloc(mRS);
        mRS.programVertexBegin(null, null);
        mRS.programVertexSetTextureMatrixEnable(true);
        mPV = mRS.programVertexCreate();
        mPV.setName("PV");
        mPV.bindAllocation(0, mPVAlloc.mAlloc);

        mPVAlloc.setupProjectionNormalized(320, 480);
        //mPVAlloc.setupOrthoNormalized(320, 480);
        mRS.contextBindProgramVertex(mPV);


        Log.e("rs", "Done loading named");
    }


    private void initRS() {
        mRS.scriptCBegin();
        mRS.scriptCSetClearColor(0.0f, 0.7f, 0.0f, 1.0f);
        mRS.scriptCSetScript(mRes, R.raw.rollo);
        mRS.scriptCSetRoot(true);
        mScript = mRS.scriptCCreate();


        mRS.contextBindRootScript(mScript);
    }
}



