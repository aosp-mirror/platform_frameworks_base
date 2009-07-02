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

    public void setPosition(float dx, float pressure) {
        mAllocStateBuf[0] += (int)(dx);
        mAllocStateBuf[2] = (int)(pressure * 0x40000);
        mAllocState.data(mAllocStateBuf);
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
    private RenderScript.Allocation[] mIcons;

    private int[] mAllocStateBuf;
    private RenderScript.Allocation mAllocState;

    private int[] mAllocIconIDBuf;
    private RenderScript.Allocation mAllocIconID;

    private void initNamed() {
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
        mRS.programFragmentStoreDitherEnable(false);
        mRS.programFragmentStoreDepthMask(false);
        mRS.programFragmentStoreBlendFunc(RenderScript.BlendSrcFunc.ONE, 
                                          RenderScript.BlendDstFunc.ONE);
        mPFSBackground = mRS.programFragmentStoreCreate();
        mPFSBackground.setName("PFS");


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


        {
            mIcons = new RenderScript.Allocation[4];
            mAllocIconIDBuf = new int[mIcons.length];
            mAllocIconID = mRS.allocationCreatePredefSized(
                RenderScript.ElementPredefined.USER_I32, mAllocIconIDBuf.length);

            
            BitmapDrawable bd;
            Bitmap b;
            
            bd = (BitmapDrawable)mRes.getDrawable(R.drawable.browser);
            mIcons[0] = mRS.allocationCreateFromBitmap(bd.getBitmap(), RenderScript.ElementPredefined.RGB_565, true);

            bd = (BitmapDrawable)mRes.getDrawable(R.drawable.market);
            mIcons[1] = mRS.allocationCreateFromBitmap(bd.getBitmap(), RenderScript.ElementPredefined.RGB_565, true);

            bd = (BitmapDrawable)mRes.getDrawable(R.drawable.photos);
            mIcons[2] = mRS.allocationCreateFromBitmap(bd.getBitmap(), RenderScript.ElementPredefined.RGB_565, true);

            bd = (BitmapDrawable)mRes.getDrawable(R.drawable.settings);
            mIcons[3] = mRS.allocationCreateFromBitmap(bd.getBitmap(), RenderScript.ElementPredefined.RGB_565, true);

            for(int ct=0; ct < mIcons.length; ct++) {
                mIcons[ct].uploadToTexture(0);
                mAllocIconIDBuf[ct] = mIcons[ct].getID();
            }
            mAllocIconID.data(mAllocIconIDBuf);
        }

    }



    private void initRS() {
        mRS.scriptCBegin();
        mRS.scriptCSetClearColor(0.0f, 0.0f, 0.1f, 1.0f);
        mRS.scriptCSetScript(mRes, R.raw.rollo);
        mRS.scriptCSetRoot(true);
        mScript = mRS.scriptCCreate();

        mAllocStateBuf = new int[] {0, 38, 0};
        mAllocState = mRS.allocationCreatePredefSized(
            RenderScript.ElementPredefined.USER_I32, mAllocStateBuf.length);
        mScript.bindAllocation(mAllocState, 0);
        mScript.bindAllocation(mAllocIconID, 1);
        setPosition(0, 0);

        mRS.contextBindRootScript(mScript);
    }
}



