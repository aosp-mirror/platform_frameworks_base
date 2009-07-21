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
        mBufferPos[0] = 2f * anim + 0.5f;   // translation
        mBufferPos[1] = (anim * 40);  // rotation
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
    private RenderScript.ProgramVertex mPVBackground;
    private RenderScript.ProgramVertex mPVImages;
    private ProgramVertexAlloc mPVA;

    private RenderScript.Allocation mAllocEnv;
    private RenderScript.Allocation mAllocPos;
    private RenderScript.Allocation mAllocState;
    private RenderScript.Allocation mAllocPV;
    private RenderScript.TriangleMesh mMesh;
    private RenderScript.Light mLight;

    private float[] mBufferPos;
    private float[] mBufferPV;

    private void initSamplers() {
        mRS.samplerBegin();
        mRS.samplerSet(RenderScript.SamplerParam.FILTER_MIN,
                       RenderScript.SamplerValue.LINEAR_MIP_LINEAR);
        mRS.samplerSet(RenderScript.SamplerParam.WRAP_MODE_S,
                       RenderScript.SamplerValue.CLAMP);
        mRS.samplerSet(RenderScript.SamplerParam.WRAP_MODE_T,
                       RenderScript.SamplerValue.CLAMP);
        mSampler = mRS.samplerCreate();
    }

    private void initPFS() {
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
    }

    private void initPF() {
        mRS.programFragmentBegin(null, null);
        mPFBackground = mRS.programFragmentCreate();
        mPFBackground.setName("PFBackground");

        mRS.programFragmentBegin(null, null);
        mRS.programFragmentSetTexEnable(0, true);
        //mRS.programFragmentSetEnvMode(0, RS_TEX_ENV_MODE_REPLACE);
        //rsProgramFragmentSetType(0, gEnv.tex[0]->getType());
        mPFImages = mRS.programFragmentCreate();
        mPFImages.setName("PFImages");
    }

    private void initPV() {
        mRS.lightBegin();
        mLight = mRS.lightCreate();
        mLight.setPosition(0, -0.5f, -1.0f);

        mRS.programVertexBegin(null, null);
        mRS.programVertexSetTextureMatrixEnable(true);
        mRS.programVertexAddLight(mLight);
        mPVBackground = mRS.programVertexCreate();
        mPVBackground.setName("PVBackground");

        mRS.programVertexBegin(null, null);
        mPVImages = mRS.programVertexCreate();
        mPVImages.setName("PVImages");
    }


    int mParams[] = new int[10];

    private void initRS() {
        mElementVertex = mRS.elementGetPredefined(
            RenderScript.ElementPredefined.NORM_ST_XYZ_F32);
        mElementIndex = mRS.elementGetPredefined(
            RenderScript.ElementPredefined.INDEX_16);

        mRS.triangleMeshBegin(mElementVertex, mElementIndex);
        FilmStripMesh fsm = new FilmStripMesh();
        fsm.init(mRS);
        mMesh = mRS.triangleMeshCreate();
        mMesh.setName("mesh");

        initPFS();
        initSamplers();
        initPF();
        initPV();
        mPFImages.bindSampler(mSampler, 0);

        Log.e("rs", "Done loading named");

        mRS.scriptCBegin();
        mRS.scriptCSetClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        mRS.scriptCSetScript(mRes, R.raw.filmstrip);
        mRS.scriptCSetRoot(true);
        mScriptStrip = mRS.scriptCCreate();

        mBufferPos = new float[3];
        mAllocPos = mRS.allocationCreatePredefSized(
            RenderScript.ElementPredefined.USER_FLOAT, 
            mBufferPos.length);

        mPVA = new ProgramVertexAlloc(mRS);
        mPVBackground.bindAllocation(0, mPVA.mAlloc);
        mPVImages.bindAllocation(0, mPVA.mAlloc);
        mPVA.setupProjectionNormalized(320, 480);


        mScriptStrip.bindAllocation(mAllocPos, 1);
       //mScriptStrip.bindAllocation(gStateAlloc, 2);
        mScriptStrip.bindAllocation(mPVA.mAlloc, 3);


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
        */

        setFilmStripPosition(0, 0);

        mRS.contextBindRootScript(mScriptStrip);
    }
}



