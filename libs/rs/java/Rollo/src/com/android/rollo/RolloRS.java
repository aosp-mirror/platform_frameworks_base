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

import android.renderscript.RSSurfaceView;
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
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.KeyEvent;
import android.view.MotionEvent;

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
    private RenderScript.ProgramFragment mPFBackground;
    private RenderScript.ProgramFragment mPFImages;
    private RenderScript.ProgramFragment mPFText;
    private RenderScript.ProgramVertex mPV;
    private ProgramVertexAlloc mPVAlloc;
    private RenderScript.ProgramVertex mPVOrtho;
    private ProgramVertexAlloc mPVOrthoAlloc;
    private RenderScript.Allocation[] mIcons;
    private RenderScript.Allocation[] mLabels;
    private RenderScript.Allocation mIconPlate;
    private RenderScript.Allocation mBackground;

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


            Bitmap b;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;

            b = BitmapFactory.decodeResource(mRes, R.raw.cf_background, opts);
            mBackground = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);
            mBackground.setName("TexBk");


            b = BitmapFactory.decodeResource(mRes, R.raw.browser, opts);
            mIcons[0] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[0] = makeTextBitmap("browser");

            b = BitmapFactory.decodeResource(mRes, R.raw.market, opts);
            mIcons[1] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[1] = makeTextBitmap("market");

            b = BitmapFactory.decodeResource(mRes, R.raw.photos, opts);
            mIcons[2] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[2] = makeTextBitmap("photos");

            b = BitmapFactory.decodeResource(mRes, R.raw.settings, opts);
            mIcons[3] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[3] = makeTextBitmap("settings");

            b = BitmapFactory.decodeResource(mRes, R.raw.calendar, opts);
            mIcons[4] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[4] = makeTextBitmap("creed");

            b = BitmapFactory.decodeResource(mRes, R.raw.g1155, opts);
            mIcons[5] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[5] = makeTextBitmap("BOA");

            b = BitmapFactory.decodeResource(mRes, R.raw.g2140, opts);
            mIcons[6] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[6] = makeTextBitmap("chess");

            b = BitmapFactory.decodeResource(mRes, R.raw.maps, opts);
            mIcons[7] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[7] = makeTextBitmap("Dictionary");

            b = BitmapFactory.decodeResource(mRes, R.raw.path431, opts);
            mIcons[8] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[8] = makeTextBitmap("facebook");

            b = BitmapFactory.decodeResource(mRes, R.raw.path676, opts);
            mIcons[9] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[9] = makeTextBitmap("Flash Light");

            b = BitmapFactory.decodeResource(mRes, R.raw.path754, opts);
            mIcons[10] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[10] = makeTextBitmap("Flight Control");

            b = BitmapFactory.decodeResource(mRes, R.raw.path815, opts);
            mIcons[11] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[11] = makeTextBitmap("google earth");

            b = BitmapFactory.decodeResource(mRes, R.raw.path1920, opts);
            mIcons[12] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[12] = makeTextBitmap("Harry Potter");

            b = BitmapFactory.decodeResource(mRes, R.raw.path1927, opts);
            mIcons[13] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[13] = makeTextBitmap("Movies");

            b = BitmapFactory.decodeResource(mRes, R.raw.path3099, opts);
            mIcons[14] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[14] = makeTextBitmap("NY Times");

            b = BitmapFactory.decodeResource(mRes, R.raw.path3950, opts);
            mIcons[15] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[15] = makeTextBitmap("Pandora");

            b = BitmapFactory.decodeResource(mRes, R.raw.path4481, opts);
            mIcons[16] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[16] = makeTextBitmap("Public Radio");

            b = BitmapFactory.decodeResource(mRes, R.raw.path5168, opts);
            mIcons[17] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[17] = makeTextBitmap("Public Radio");

            b = BitmapFactory.decodeResource(mRes, R.raw.polygon2408, opts);
            mIcons[18] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGBA_8888, true);
            mLabels[18] = makeTextBitmap("Public Radio");

            /*
            b = BitmapFactory.decodeResource(mRes, R.raw.solitaire, opts);
            mIcons[19] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);
            mLabels[19] = makeTextBitmap("Public Radio");

            b = BitmapFactory.decodeResource(mRes, R.raw.sudoku, opts);
            mIcons[20] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);
            mLabels[20] = makeTextBitmap("Public Radio");

            b = BitmapFactory.decodeResource(mRes, R.raw.taptaprevenge, opts);
            mIcons[21] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);
            mLabels[21] = makeTextBitmap("Public Radio");

            b = BitmapFactory.decodeResource(mRes, R.raw.tetris, opts);
            mIcons[22] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);
            mLabels[22] = makeTextBitmap("Public Radio");

            b = BitmapFactory.decodeResource(mRes, R.raw.tictactoe, opts);
            mIcons[23] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);
            mLabels[23] = makeTextBitmap("Public Radio");

            b = BitmapFactory.decodeResource(mRes, R.raw.tweetie, opts);
            mIcons[24] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);
            mLabels[24] = makeTextBitmap("Public Radio");

            b = BitmapFactory.decodeResource(mRes, R.raw.urbanspoon, opts);
            mIcons[25] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);
            mLabels[25] = makeTextBitmap("Public Radio");

            b = BitmapFactory.decodeResource(mRes, R.raw.waterslide_extreme, opts);
            mIcons[26] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);
            mLabels[26] = makeTextBitmap("Public Radio");

            b = BitmapFactory.decodeResource(mRes, R.raw.weather_channel, opts);
            mIcons[27] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);
            mLabels[27] = makeTextBitmap("Public Radio");

            b = BitmapFactory.decodeResource(mRes, R.raw.zippo, opts);
            mIcons[28] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);
            mLabels[28] = makeTextBitmap("Public Radio");
*/

            mIcons[19] = mIcons[0];
            mIcons[20] = mIcons[1];
            mIcons[21] = mIcons[2];
            mIcons[22] = mIcons[3];
            mIcons[23] = mIcons[2];
            mIcons[24] = mIcons[1];
            mIcons[25] = mIcons[0];
            mIcons[26] = mIcons[1];
            mIcons[27] = mIcons[2];
            mIcons[28] = mIcons[3];

            mLabels[19] = mLabels[0];
            mLabels[20] = mLabels[1];
            mLabels[21] = mLabels[2];
            mLabels[22] = mLabels[3];
            mLabels[23] = mLabels[2];
            mLabels[24] = mLabels[1];
            mLabels[25] = mLabels[0];
            mLabels[26] = mLabels[1];
            mLabels[27] = mLabels[2];
            mLabels[28] = mLabels[3];


            for(int ct=0; ct < mIcons.length; ct++) {
                mIcons[ct].uploadToTexture(0);
                mLabels[ct].uploadToTexture(0);
                mAllocIconIDBuf[ct] = mIcons[ct].getID();
                mAllocLabelIDBuf[ct] = mLabels[ct].getID();
            }
            mAllocIconID.data(mAllocIconIDBuf);
            mAllocLabelID.data(mAllocLabelIDBuf);

            RenderScript.Element e = mRS.elementGetPredefined(RenderScript.ElementPredefined.RGB_565);
            mRS.typeBegin(e);
            mRS.typeAdd(RenderScript.Dimension.X, 64);
            mRS.typeAdd(RenderScript.Dimension.Y, 64);
            RenderScript.Type t = mRS.typeCreate();
            mIconPlate = mRS.allocationCreateTyped(t);
            //t.destroy();
            //e.destroy();

            int tmp[] = new int[64 * 32];
            for(int ct = 0; ct < (64*32); ct++) {
                tmp[ct] = 7 | (13 << 5) | (7 << 11);
                tmp[ct] = tmp[ct] | (tmp[ct] << 16);
            }
            for(int ct = 0; ct < 32; ct++) {
                tmp[ct] = 0;
                tmp[ct + (63*32)] = 0;
            }
            for(int ct = 0; ct < 64; ct++) {
                tmp[ct * 32] = 0;
                tmp[ct * 32 + 31] = 0;
            }
            mIconPlate.data(tmp);
            mIconPlate.uploadToTexture(0);
            mIconPlate.setName("Plate");
            mPFImages.bindTexture(mIconPlate, 0);
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
        //mRS.scriptCSetClearDepth(0);
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


