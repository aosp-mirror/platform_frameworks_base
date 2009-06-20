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

import android.renderscript.RenderScript;
import android.renderscript.ProgramVertexAlloc;
import android.renderscript.Matrix;

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

public class FilmRS {

    public FilmRS() {
    }

    public void init(RenderScript rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;
        initNamed();
        initRS();
    }

    public void setFilmStripPosition(int x, int y)
    {
        if (x < 0) {
            x = 0;
        }
        if (x > 50) {
            x = 50;
        }
    
        float anim = ((float)x) / 50.f;
        mBufferPos[0] = -2f * anim - .2f;   // translation
        mBufferPos[1] = -90 + (anim * 40);  // rotation
        mBufferPos[2] = ((float)y) / 16.f - 8;  // focusPos
    
        mAllocPos.data(mBufferPos);
    }


    private Resources mRes;
    private RenderScript mRS;
    private RenderScript.Script mScriptStrip;
    private RenderScript.Script mScriptImage;
    private RenderScript.Element mElementVertex;
    private RenderScript.Element mElementIndex;
    private RenderScript.Sampler mSampler;
    private RenderScript.ProgramFragmentStore mPFSBackground;
    private RenderScript.ProgramFragmentStore mPFSImages;
    private RenderScript.ProgramFragment mPFBackground;
    private RenderScript.ProgramFragment mPFImages;
    private RenderScript.ProgramVertex mPV;
    private ProgramVertexAlloc mPVA;

    private RenderScript.Allocation mAllocEnv;
    private RenderScript.Allocation mAllocPos;
    private RenderScript.Allocation mAllocState;
    private RenderScript.Allocation mAllocPV;
    private RenderScript.TriangleMesh mMesh;

    private float[] mBufferPos;
    private float[] mBufferPV;

    private void initNamed() {
        mElementVertex = mRS.elementGetPredefined(
            RenderScript.ElementPredefined.NORM_ST_XYZ_F32);
        mElementIndex = mRS.elementGetPredefined(
            RenderScript.ElementPredefined.INDEX_16);

        mRS.triangleMeshBegin(mElementVertex, mElementIndex);
        FilmStripMesh fsm = new FilmStripMesh();
        fsm.init(mRS);
        mMesh = mRS.triangleMeshCreate();
        mMesh.setName("mesh");
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
        mPFBackground = mRS.programFragmentCreate();
        mPFBackground.setName("PFBackground");

        mRS.programFragmentBegin(null, null);
        mRS.programFragmentSetTexEnable(0, true);
        //mRS.programFragmentSetEnvMode(0, RS_TEX_ENV_MODE_REPLACE);
        //rsProgramFragmentSetType(0, gEnv.tex[0]->getType());
        mPFImages = mRS.programFragmentCreate();
        mPFImages.setName("PFImages");
        mPFImages.bindSampler(mSampler, 0);

        mRS.programFragmentStoreBegin(null, null);
        mRS.programFragmentStoreDepthFunc(RenderScript.DepthFunc.LESS);
        mRS.programFragmentStoreDitherEnable(true);
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

        mRS.programVertexBegin(null, null);
        mRS.programVertexSetTextureMatrixEnable(true);
        mPV = mRS.programVertexCreate();
        mPV.setName("PV");

        Log.e("rs", "Done loading named");
    }


    private Bitmap mBackground;

    int mParams[] = new int[10];

    private void initRS() {
        int partCount = 1024;

        mRS.scriptCBegin();
        mRS.scriptCSetClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        mRS.scriptCSetScript(mRes, R.raw.filmstrip);
        mRS.scriptCSetRoot(true);
        mScriptStrip = mRS.scriptCCreate();

        mBufferPos = new float[3];
        mAllocPos = mRS.allocationCreatePredefSized(
            RenderScript.ElementPredefined.USER_FLOAT, 
            mBufferPos.length);
        setFilmStripPosition(0, 0);

        mPVA = new ProgramVertexAlloc(mRS);
        mPV.bindAllocation(0, mPVA.mAlloc);
        mPVA.setupProjectionNormalized(320, 480);

        Matrix m = new Matrix();

        m.loadIdentity();

        m.translate(0, 0, 1);
        m.rotate(90, 0, 0, 1);
        m.rotate(20, 1, 0, 0);
        mPVA.loadModelview(m);





        //mScriptStrip.bindAllocation(mEnvAlloc, 0);
        mScriptStrip.bindAllocation(mAllocPos, 1);
        //mScriptStrip.bindAllocation(gStateAlloc, 2);
        mScriptStrip.bindAllocation(mPVA.mAlloc, 3);


        //mIntAlloc = mRS.allocationCreatePredefSized(RenderScript.ElementPredefined.USER_I32, 10);
        //mPartAlloc = mRS.allocationCreatePredefSized(RenderScript.ElementPredefined.USER_I32, partCount * 3 * 3);
        //mPartAlloc.setName("PartBuffer");
        //mVertAlloc = mRS.allocationCreatePredefSized(RenderScript.ElementPredefined.USER_I32, partCount * 5 + 1);
/*
        {
            Resources res = getResources();
            Drawable d = res.getDrawable(R.drawable.gadgets_clock_mp3);
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
        */

        mRS.contextBindRootScript(mScriptStrip);
    }
}



