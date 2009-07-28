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
        Log.e("rs",  "setSelected " + Integer.toString(index));

        mAllocStateBuf[STATE_SELECTION] = index;
        mAllocStateBuf[STATE_DONE] = 1;
        mAllocState.data(mAllocStateBuf);
    }


    private Resources mRes;
    private RenderScript mRS;
    private RenderScript.Script mScript;
    private RenderScript.Sampler mSampler;
    private RenderScript.ProgramFragmentStore mPFSBackground;
    private RenderScript.ProgramFragment mPFBackground;
    private RenderScript.ProgramFragment mPFImages;
    private RenderScript.ProgramVertex mPV;
    private ProgramVertexAlloc mPVAlloc;
    private RenderScript.ProgramVertex mPVOrtho;
    private ProgramVertexAlloc mPVOrthoAlloc;
    private RenderScript.Allocation[] mIcons;
    private RenderScript.Allocation mIconPlate;
    private RenderScript.Allocation mBackground;

    private int[] mAllocStateBuf;
    private RenderScript.Allocation mAllocState;

    private int[] mAllocIconIDBuf;
    private RenderScript.Allocation mAllocIconID;

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


        mRS.programFragmentBegin(null, null);
        mRS.programFragmentSetTexEnable(0, true);
        mRS.programFragmentSetTexEnvMode(0, RenderScript.EnvMode.MODULATE);
        mPFImages = mRS.programFragmentCreate();
        mPFImages.setName("PF");
        mPFImages.bindSampler(mSampler, 0);

        mRS.programFragmentStoreBegin(null, null);
        mRS.programFragmentStoreDepthFunc(RenderScript.DepthFunc.LESS);
        mRS.programFragmentStoreDitherEnable(false);
        mRS.programFragmentStoreDepthMask(true);
        mRS.programFragmentStoreBlendFunc(RenderScript.BlendSrcFunc.SRC_ALPHA,
                                          RenderScript.BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        mPFSBackground = mRS.programFragmentStoreCreate();
        mPFSBackground.setName("PFS");

        mPVAlloc = new ProgramVertexAlloc(mRS);
        mRS.programVertexBegin(null, null);
        mRS.programVertexSetTextureMatrixEnable(false);
        mPV = mRS.programVertexCreate();
        mPV.setName("PV");
        mPV.bindAllocation(0, mPVAlloc.mAlloc);
        mPVAlloc.setupProjectionNormalized(320, 480);

        mPVOrthoAlloc = new ProgramVertexAlloc(mRS);
        mRS.programVertexBegin(null, null);
        mRS.programVertexSetTextureMatrixEnable(true);
        mPVOrtho = mRS.programVertexCreate();
        mPVOrtho.setName("PVOrtho");
        mPVOrtho.bindAllocation(0, mPVOrthoAlloc.mAlloc);
        mPVOrthoAlloc.setupOrthoWindow(320, 480);

        mRS.contextBindProgramVertex(mPV);

        mAllocScratchBuf = new int[32];
        for(int ct=0; ct < mAllocScratchBuf.length; ct++) {
            mAllocScratchBuf[ct] = 0;
        }
        mAllocScratch = mRS.allocationCreatePredefSized(
            RenderScript.ElementPredefined.USER_I32, mAllocScratchBuf.length);
        mAllocScratch.data(mAllocScratchBuf);

        Log.e("rs", "Done loading named");



        {
            mIcons = new RenderScript.Allocation[29];
            mAllocIconIDBuf = new int[mIcons.length];
            mAllocIconID = mRS.allocationCreatePredefSized(
                RenderScript.ElementPredefined.USER_I32, mAllocIconIDBuf.length);


            Bitmap b;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;

            b = BitmapFactory.decodeResource(mRes, R.raw.cf_background, opts);
            mBackground = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);
            mBackground.setName("TexBk");


            b = BitmapFactory.decodeResource(mRes, R.raw.browser, opts);
            mIcons[0] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.market, opts);
            mIcons[1] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.photos, opts);
            mIcons[2] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.settings, opts);
            mIcons[3] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

/*
            b = BitmapFactory.decodeResource(mRes, R.raw.assasins_creed, opts);
            mIcons[4] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.bankofamerica, opts);
            mIcons[5] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.chess, opts);
            mIcons[6] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.dictionary, opts);
            mIcons[7] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.facebook, opts);
            mIcons[8] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.flashlight, opts);
            mIcons[9] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.flight_control, opts);
            mIcons[10] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.google_earth, opts);
            mIcons[11] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.harry_potter, opts);
            mIcons[12] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.movies, opts);
            mIcons[13] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.nytimes, opts);
            mIcons[14] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.pandora, opts);
            mIcons[15] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);



            b = BitmapFactory.decodeResource(mRes, R.raw.public_radio, opts);
            mIcons[16] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.shazam, opts);
            mIcons[17] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.skype, opts);
            mIcons[18] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.solitaire, opts);
            mIcons[19] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.sudoku, opts);
            mIcons[20] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.taptaprevenge, opts);
            mIcons[21] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.tetris, opts);
            mIcons[22] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.tictactoe, opts);
            mIcons[23] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.tweetie, opts);
            mIcons[24] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.urbanspoon, opts);
            mIcons[25] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.waterslide_extreme, opts);
            mIcons[26] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.weather_channel, opts);
            mIcons[27] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);

            b = BitmapFactory.decodeResource(mRes, R.raw.zippo, opts);
            mIcons[28] = mRS.allocationCreateFromBitmap(b, RenderScript.ElementPredefined.RGB_565, true);
*/
            mIcons[4] =  mIcons[3];
            mIcons[5] =  mIcons[2];
            mIcons[6] =  mIcons[1];
            mIcons[7] =  mIcons[0];
            mIcons[8] =  mIcons[1];
            mIcons[9] =  mIcons[2];
            mIcons[10] = mIcons[3];
            mIcons[11] = mIcons[2];
            mIcons[12] = mIcons[1];
            mIcons[13] = mIcons[0];
            mIcons[14] = mIcons[1];
            mIcons[15] = mIcons[2];
            mIcons[16] = mIcons[3];
            mIcons[17] = mIcons[2];
            mIcons[18] = mIcons[1];
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



            for(int ct=0; ct < mIcons.length; ct++) {
                mIcons[ct].uploadToTexture(0);
                mAllocIconIDBuf[ct] = mIcons[ct].getID();
            }
            mAllocIconID.data(mAllocIconIDBuf);

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
            Log.e("xx", "plate");
            mIconPlate.uploadToTexture(0);
            mIconPlate.setName("Plate");
            mPFImages.bindTexture(mIconPlate, 0);
        }

    }

    private void makeTextBitmap() {
        //Bitmap.createBitmap(width, height, Bitmap.Config);
        //new Canvas(theBitmap);
        //canvas.drawText();
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
        setPosition(0);
        setZoom(1);

        //RenderScript.File f = mRS.fileOpen("/sdcard/test.a3d");

        mRS.contextBindRootScript(mScript);
    }
}


