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
import android.renderscript.ProgramVertex;
import android.renderscript.Element;
import android.renderscript.Allocation;
import android.renderscript.Script;
import android.renderscript.ScriptC;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.Sampler;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;

import android.content.Context;
import android.content.res.Resources;
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
    private Script mScript;
    private Sampler mSampler;
    private Sampler mSamplerText;
    private ProgramStore mPSBackground;
    private ProgramStore mPSText;
    private ProgramFragment mPFImages;
    private ProgramFragment mPFText;
    private ProgramVertex mPV;
    private ProgramVertex.MatrixAllocation mPVAlloc;
    private ProgramVertex mPVOrtho;
    private ProgramVertex.MatrixAllocation mPVOrthoAlloc;
    private Allocation[] mIcons;
    private Allocation[] mLabels;

    private int[] mAllocStateBuf;
    private Allocation mAllocState;

    private int[] mAllocIconIDBuf;
    private Allocation mAllocIconID;

    private int[] mAllocLabelIDBuf;
    private Allocation mAllocLabelID;

    private int[] mAllocScratchBuf;
    private Allocation mAllocScratch;

    private void initNamed() {
        Sampler.Builder sb = new Sampler.Builder(mRS);
        sb.setMin(Sampler.Value.LINEAR);//_MIP_LINEAR);
        sb.setMag(Sampler.Value.LINEAR);
        sb.setWrapS(Sampler.Value.CLAMP);
        sb.setWrapT(Sampler.Value.CLAMP);
        mSampler = sb.create();

        sb.setMin(Sampler.Value.NEAREST);
        sb.setMag(Sampler.Value.NEAREST);
        mSamplerText = sb.create();


        ProgramFragment.Builder bf = new ProgramFragment.Builder(mRS, null, null);
        bf.setTexEnable(true, 0);
        bf.setTexEnvMode(ProgramFragment.EnvMode.MODULATE, 0);
        mPFImages = bf.create();
        mPFImages.setName("PF");
        mPFImages.bindSampler(mSampler, 0);

        bf.setTexEnvMode(ProgramFragment.EnvMode.MODULATE, 0);
        mPFText = bf.create();
        mPFText.setName("PFText");
        mPFText.bindSampler(mSamplerText, 0);

        ProgramStore.Builder bs = new ProgramStore.Builder(mRS, null, null);
        bs.setDepthFunc(ProgramStore.DepthFunc.LESS);
        bs.setDitherEnable(false);
        bs.setDepthMask(true);
        bs.setBlendFunc(ProgramStore.BlendSrcFunc.SRC_ALPHA,
                        ProgramStore.BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        mPSBackground = bs.create();
        mPSBackground.setName("PFS");

        bs.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
        bs.setDepthMask(false);
        bs.setBlendFunc(ProgramStore.BlendSrcFunc.SRC_ALPHA,
                        ProgramStore.BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        mPSText = bs.create();
        mPSText.setName("PFSText");

        mPVAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPVAlloc.setupProjectionNormalized(mWidth, mHeight);

        ProgramVertex.Builder pvb = new ProgramVertex.Builder(mRS, null, null);
        mPV = pvb.create();
        mPV.setName("PV");
        mPV.bindAllocation(mPVAlloc);

        mPVOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPVOrthoAlloc.setupOrthoWindow(mWidth, mHeight);

        pvb.setTextureMatrixEnable(true);
        mPVOrtho = pvb.create();
        mPVOrtho.setName("PVOrtho");
        mPVOrtho.bindAllocation(mPVOrthoAlloc);

        mRS.contextBindProgramVertex(mPV);

        mAllocScratchBuf = new int[32];
        mAllocScratch = Allocation.createSized(mRS,
            Element.USER_I32(mRS), mAllocScratchBuf.length);
        mAllocScratch.data(mAllocScratchBuf);

        Log.e("rs", "Done loading named");



        {
            mIcons = new Allocation[29];
            mAllocIconIDBuf = new int[mIcons.length];
            mAllocIconID = Allocation.createSized(mRS,
                Element.USER_I32(mRS), mAllocIconIDBuf.length);

            mLabels = new Allocation[29];
            mAllocLabelIDBuf = new int[mLabels.length];
            mAllocLabelID = Allocation.createSized(mRS,
                Element.USER_I32(mRS), mLabels.length);

            Element ie8888 = Element.RGBA_8888(mRS);

            mIcons[0] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.browser, ie8888, true);
            mIcons[1] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.market, ie8888, true);
            mIcons[2] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.photos, ie8888, true);
            mIcons[3] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.settings, ie8888, true);
            mIcons[4] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.calendar, ie8888, true);
            mIcons[5] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.g1155, ie8888, true);
            mIcons[6] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.g2140, ie8888, true);
            mIcons[7] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.maps, ie8888, true);
            mIcons[8] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.path431, ie8888, true);
            mIcons[9] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.path676, ie8888, true);
            mIcons[10] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.path754, ie8888, true);
            mIcons[11] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.path815, ie8888, true);
            mIcons[12] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.path1920, ie8888, true);
            mIcons[13] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.path1927, ie8888, true);
            mIcons[14] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.path3099, ie8888, true);
            mIcons[15] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.path3950, ie8888, true);
            mIcons[16] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.path4481, ie8888, true);
            mIcons[17] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.path5168, ie8888, true);
            mIcons[18] = Allocation.createFromBitmapResource(mRS, mRes, R.raw.polygon2408, ie8888, true);

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

    Allocation makeTextBitmap(String t) {
        Bitmap b = Bitmap.createBitmap(128, 32, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(20);
        p.setColor(0xffffffff);
        c.drawText(t, 2, 26, p);
        return Allocation.createFromBitmap(mRS, b, Element.RGBA_8888(mRS), true);
    }


    private void initRS() {
        ScriptC.Builder sb = new ScriptC.Builder(mRS);
        sb.setScript(mRes, R.raw.rollo);
        //sb.setScript(mRes, R.raw.rollo2);
        sb.setRoot(true);
        mScript = sb.create();
        mScript.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);

        mAllocStateBuf = new int[] {0, 0, 0, 8, 0, 0, -1, 0, mAllocIconIDBuf.length, 0, 0};
        mAllocState = Allocation.createSized(mRS,
            Element.USER_I32(mRS), mAllocStateBuf.length);
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


