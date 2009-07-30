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
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

public class RolloRS {
    //public static final int STATE_SELECTED_ID = 0;
    public static final int STATE_DONE = 1;
    //public static final int STATE_PRESSURE = 2;
    public static final int STATE_ZOOM = 3;
    //public static final int STATE_WARP = 4;
    public static final int STATE_ORIENTATION = 5;
    public static final int STATE_SELECTION = 6;
    public static final int STATE_FIRST_VISIBLE = 7;
    public static final int STATE_COUNT = 8;
    public static final int STATE_TOUCH = 9;


    public RolloRS() {
    }

    public void init(RenderScript rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;
        mWidth = width;
        mHeight = height;
        initNamed();
        initRS();
    }

    public void setPosition(float column) {
        mAllocStateBuf[STATE_FIRST_VISIBLE] = (int)(column * (-20));
        mAllocState.data(mAllocStateBuf);
    }

    public void setTouch(boolean touch) {
        mAllocStateBuf[STATE_TOUCH] = touch ? 1 : 0;
        mAllocState.data(mAllocStateBuf);
    }

    public void setZoom(float z) {
        //Log.e("rs", "zoom " + Float.toString(z));

        mAllocStateBuf[STATE_ZOOM] = (int)(z * 1000.f);
        mAllocState.data(mAllocStateBuf);
    }

    public void setSelected(int index) {
        //Log.e("rs",  "setSelected " + Integer.toString(index));

        mAllocStateBuf[STATE_SELECTION] = index;
        mAllocStateBuf[STATE_DONE] = 1;
        mAllocState.data(mAllocStateBuf);
    }

    private int mWidth;
    private int mHeight;

    private Resources mRes;
    private RenderScript mRS;
    private RenderScript.Script mScript;
    private RenderScript.Sampler mSampler;
    private RenderScript.Sampler mSamplerText;
    private RenderScript.ProgramFragmentStore mPFSBackground;
    private RenderScript.ProgramFragmentStore mPFSText;
    private RenderScript.ProgramFragment mPFImages;
    private RenderScript.ProgramFragment mPFText;
    private RenderScript.ProgramVertex mPV;
    private ProgramVertexAlloc mPVAlloc;
    private RenderScript.ProgramVertex mPVOrtho;
    private ProgramVertexAlloc mPVOrthoAlloc;
    private RenderScript.Allocation[] mIcons;
    private RenderScript.Allocation[] mLabels;

    private int[] mAllocStateBuf;
    private RenderScript.Allocation mAllocState;

    private int[] mAllocIconIDBuf;
    private RenderScript.Allocation mAllocIconID;

    private int[] mAllocLabelIDBuf;
    private RenderScript.Allocation mAllocLabelID;

    private int[] mAllocScratchBuf;
    private RenderScript.Allocation mAllocScratch;

    private void initNamed() {
        mRS.samplerBegin();
        mRS.samplerSet(RenderScript.SamplerParam.FILTER_MIN,
                       RenderScript.SamplerValue.LINEAR);//_MIP_LINEAR);
        mRS.samplerSet(RenderScript.SamplerParam.FILTER_MAG,
                       RenderScript.SamplerValue.LINEAR);
        mRS.samplerSet(RenderScript.SamplerParam.WRAP_MODE_S,
                       RenderScript.SamplerValue.CLAMP);
        mRS.samplerSet(RenderScript.SamplerParam.WRAP_MODE_T,
                       RenderScript.SamplerValue.CLAMP);
        mSampler = mRS.samplerCreate();

        mRS.samplerBegin();
        mRS.samplerSet(RenderScript.SamplerParam.FILTER_MIN,
                       RenderScript.SamplerValue.NEAREST);
        mRS.samplerSet(RenderScript.SamplerParam.FILTER_MAG,
                       RenderScript.SamplerValue.NEAREST);
        mRS.samplerSet(RenderScript.SamplerParam.WRAP_MODE_S,
                       RenderScript.SamplerValue.CLAMP);
        mRS.samplerSet(RenderScript.SamplerParam.WRAP_MODE_T,
                       RenderScript.SamplerValue.CLAMP);
        mSamplerText = mRS.samplerCreate();


        mRS.programFragmentBegin(null, null);
        mRS.programFragmentSetTexEnable(0, true);
        mRS.programFragmentSetTexEnvMode(0, RenderScript.EnvMode.MODULATE);
        mPFImages = mRS.programFragmentCreate();
        mPFImages.setName("PF");
        mPFImages.bindSampler(mSampler, 0);

        mRS.programFragmentBegin(null, null);
        mRS.programFragmentSetTexEnable(0, true);
        mRS.programFragmentSetTexEnvMode(0, RenderScript.EnvMode.MODULATE);
        mPFText = mRS.programFragmentCreate();
        mPFText.setName("PFText");
        mPFText.bindSampler(mSamplerText, 0);

        mRS.programFragmentStoreBegin(null, null);
        mRS.programFragmentStoreDepthFunc(RenderScript.DepthFunc.LESS);
        mRS.programFragmentStoreDitherEnable(false);
        mRS.programFragmentStoreDepthMask(true);
        mRS.programFragmentStoreBlendFunc(RenderScript.BlendSrcFunc.SRC_ALPHA,
                                          RenderScript.BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        mPFSBackground = mRS.programFragmentStoreCreate();
        mPFSBackground.setName("PFS");

        mRS.programFragmentStoreBegin(null, null);
        mRS.programFragmentStoreDepthFunc(RenderScript.DepthFunc.ALWAYS);
        mRS.programFragmentStoreDitherEnable(false);
        mRS.programFragmentStoreDepthMask(false);
        mRS.programFragmentStoreBlendFunc(RenderScript.BlendSrcFunc.SRC_ALPHA,
                                          RenderScript.BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        mPFSText = mRS.programFragmentStoreCreate();
        mPFSText.setName("PFSText");

        mPVAlloc = new ProgramVertexAlloc(mRS);
        mRS.programVertexBegin(null, null);
        mRS.programVertexSetTextureMatrixEnable(false);
        mPV = mRS.programVertexCreate();
        mPV.setName("PV");
        mPV.bindAllocation(0, mPVAlloc.mAlloc);
        mPVAlloc.setupProjectionNormalized(mWidth, mHeight);

        mPVOrthoAlloc = new ProgramVertexAlloc(mRS);
        mRS.programVertexBegin(null, null);
        mRS.programVertexSetTextureMatrixEnable(true);
        mPVOrtho = mRS.programVertexCreate();
        mPVOrtho.setName("PVOrtho");
        mPVOrtho.bindAllocation(0, mPVOrthoAlloc.mAlloc);
        mPVOrthoAlloc.setupOrthoWindow(mWidth, mHeight);

        mRS.contextBindProgramVertex(mPV);

        mAllocScratchBuf = new int[32];
        mAllocScratch = mRS.allocationCreatePredefSized(
            RenderScript.ElementPredefined.USER_I32, mAllocScratchBuf.length);
        mAllocScratch.data(mAllocScratchBuf);

        Log.e("rs", "Done loading named");



        {
            mIcons = new RenderScript.Allocation[29];
            mAllocIconIDBuf = new int[mIcons.length];
            mAllocIconID = mRS.allocationCreatePredefSized(
                RenderScript.ElementPredefined.USER_I32, mAllocIconIDBuf.length);

            mLabels = new RenderScript.Allocation[29];
            mAllocLabelIDBuf = new int[mLabels.length];
            mAllocLabelID = mRS.allocationCreatePredefSized(
                RenderScript.ElementPredefined.USER_I32, mLabels.length);

            RenderScript.ElementPredefined ie565 =
                RenderScript.ElementPredefined.RGB_565;
            RenderScript.ElementPredefined ie8888 =
                RenderScript.ElementPredefined.RGBA_8888;

            mIcons[0] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.browser, ie8888, true);
            mIcons[1] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.market, ie8888, true);
            mIcons[2] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.photos, ie8888, true);
            mIcons[3] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.settings, ie8888, true);
            mIcons[4] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.calendar, ie8888, true);
            mIcons[5] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.g1155, ie8888, true);
            mIcons[6] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.g2140, ie8888, true);
            mIcons[7] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.maps, ie8888, true);
            mIcons[8] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.path431, ie8888, true);
            mIcons[9] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.path676, ie8888, true);
            mIcons[10] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.path754, ie8888, true);
            mIcons[11] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.path815, ie8888, true);
            mIcons[12] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.path1920, ie8888, true);
            mIcons[13] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.path1927, ie8888, true);
            mIcons[14] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.path3099, ie8888, true);
            mIcons[15] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.path3950, ie8888, true);
            mIcons[16] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.path4481, ie8888, true);
            mIcons[17] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.path5168, ie8888, true);
            mIcons[18] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.polygon2408, ie8888, true);

            mLabels[0] = makeTextBitmap("browser");
            mLabels[1] = makeTextBitmap("market");
            mLabels[2] = makeTextBitmap("photos");
            mLabels[3] = makeTextBitmap("settings");
            mLabels[4] = makeTextBitmap("calendar");
            mLabels[5] = makeTextBitmap("g1155");
            mLabels[6] = makeTextBitmap("g2140");
            mLabels[7] = makeTextBitmap("maps");
            mLabels[8] = makeTextBitmap("path431");
            mLabels[9] = makeTextBitmap("path676");
            mLabels[10] = makeTextBitmap("path754");
            mLabels[11] = makeTextBitmap("path815");
            mLabels[12] = makeTextBitmap("path1920");
            mLabels[13] = makeTextBitmap("path1927");
            mLabels[14] = makeTextBitmap("path3099");
            mLabels[15] = makeTextBitmap("path3950");
            mLabels[16] = makeTextBitmap("path4481");
            mLabels[17] = makeTextBitmap("path5168");
            mLabels[18] = makeTextBitmap("polygon2408");

            mIcons[19] = mIcons[0];
            mIcons[20] = mIcons[1];
            mIcons[21] = mIcons[2];
            mIcons[22] = mIcons[3];
            mIcons[23] = mIcons[4];
            mIcons[24] = mIcons[5];
            mIcons[25] = mIcons[6];
            mIcons[26] = mIcons[7];
            mIcons[27] = mIcons[8];
            mIcons[28] = mIcons[9];

            mLabels[19] = mLabels[0];
            mLabels[20] = mLabels[1];
            mLabels[21] = mLabels[2];
            mLabels[22] = mLabels[3];
            mLabels[23] = mLabels[4];
            mLabels[24] = mLabels[5];
            mLabels[25] = mLabels[6];
            mLabels[26] = mLabels[7];
            mLabels[27] = mLabels[8];
            mLabels[28] = mLabels[9];

/*
            mIcons[19] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.solitaire, ie8888, true);
            mIcons[20] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.sudoku, ie8888, true);
            mIcons[21] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.taptaprevenge, ie8888, true);
            mIcons[22] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.tetris, ie8888, true);
            mIcons[23] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.tictactoe, ie8888, true);
            mIcons[24] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.tweetie, ie8888, true);
            mIcons[25] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.urbanspoon, ie8888, true);
            mIcons[26] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.waterslide_extreme, ie8888, true);
            mIcons[27] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.weather_channel, ie8888, true);
            mIcons[28] = mRS.allocationCreateFromBitmapResource(mRes, R.raw.zippo, ie8888, true);
*/


            for(int ct=0; ct < mIcons.length; ct++) {
                mIcons[ct].uploadToTexture(0);
                mLabels[ct].uploadToTexture(0);
                mAllocIconIDBuf[ct] = mIcons[ct].getID();
                mAllocLabelIDBuf[ct] = mLabels[ct].getID();
            }
            mAllocIconID.data(mAllocIconIDBuf);
            mAllocLabelID.data(mAllocLabelIDBuf);
        }

    }

    RenderScript.Allocation makeTextBitmap(String t) {
        Bitmap b = Bitmap.createBitmap(128, 32, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(20);
        p.setColor(0xffffffff);
        c.drawText(t, 2, 26, p);
        return mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
    }


    private void initRS() {
        mRS.scriptCBegin();
        mRS.scriptCSetClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        mRS.scriptCSetScript(mRes, R.raw.rollo);
        //mRS.scriptCSetScript(mRes, R.raw.rollo2);
        mRS.scriptCSetRoot(true);
        mScript = mRS.scriptCCreate();

        mAllocStateBuf = new int[] {0, 0, 0, 8, 0, 0, -1, 0, mAllocIconIDBuf.length, 0, 0};
        mAllocState = mRS.allocationCreatePredefSized(
            RenderScript.ElementPredefined.USER_I32, mAllocStateBuf.length);
        mScript.bindAllocation(mAllocState, 0);
        mScript.bindAllocation(mAllocIconID, 1);
        mScript.bindAllocation(mAllocScratch, 2);
        mScript.bindAllocation(mAllocLabelID, 3);
        setPosition(0);
        setZoom(1);

        //RenderScript.File f = mRS.fileOpen("/sdcard/test.a3d");

        mRS.contextBindRootScript(mScript);
    }
}


