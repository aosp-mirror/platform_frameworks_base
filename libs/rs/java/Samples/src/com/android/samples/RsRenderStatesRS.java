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

package com.android.samples;

import java.io.Writer;

import android.content.res.Resources;
import android.renderscript.*;
import android.renderscript.ProgramStore.DepthFunc;
import android.util.Log;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;


public class RsRenderStatesRS {

    int mWidth;
    int mHeight;

    public RsRenderStatesRS() {
    }

    public void init(RenderScriptGL rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;
        mWidth = width;
        mHeight = height;
        mOptionsARGB.inScaled = false;
        mOptionsARGB.inPreferredConfig = Bitmap.Config.ARGB_8888;
        mMode = 0;
        mMaxModes = 4;
        initRS();
    }

    private Resources mRes;
    private RenderScriptGL mRS;

    private Sampler mSampler;

    private ProgramStore mProgStoreBlendNone;
    private ProgramStore mProgStoreBlendAlpha;
    private ProgramStore mProgStoreBlendAdd;

    private ProgramFragment mProgFragmentTexture;
    private ProgramFragment mProgFragmentColor;
    private ProgramVertex mProgVertex;
    private ProgramVertex.MatrixAllocation mPVA;

    private Allocation mTexOpaque;
    private Allocation mTexTransparent;

    private Allocation mAllocPV;

    private Mesh mMbyNMesh;

    Font mFontSans;
    Font mFontSerif;
    Font mFontSerifBold;
    Font mFontSerifItalic;
    Font mFontSerifBoldItalic;
    Font mFontMono;

    private Allocation mTextAlloc;

    private ScriptC_Rsrenderstates mScript;

    private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();

    int mMode;
    int mMaxModes;

    public void onActionDown(int x, int y) {
        mMode ++;
        mMode = mMode % mMaxModes;
        mScript.set_gDisplayMode(mMode);
    }


    private void initProgramStore() {
        // Use stock the stock program store object
        mProgStoreBlendNone = ProgramStore.BlendNone_DepthNoDepth(mRS);

        // Create a custom program store
        ProgramStore.Builder builder = new ProgramStore.Builder(mRS);
        builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
        builder.setBlendFunc(ProgramStore.BlendSrcFunc.SRC_ALPHA,
                             ProgramStore.BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        builder.setDitherEnable(false);
        builder.setDepthMask(false);
        mProgStoreBlendAlpha = builder.create();

        mProgStoreBlendAdd = ProgramStore.BlendAdd_DepthNoDepth(mRS);

        mScript.set_gProgStoreBlendNone(mProgStoreBlendNone);
        mScript.set_gProgStoreBlendAlpha(mProgStoreBlendAlpha);
        mScript.set_gProgStoreBlendAdd(mProgStoreBlendAdd);
    }

    private void initProgramFragment() {

        Sampler.Builder bs = new Sampler.Builder(mRS);
        bs.setMin(Sampler.Value.LINEAR);
        bs.setMag(Sampler.Value.LINEAR);
        bs.setWrapS(Sampler.Value.CLAMP);
        bs.setWrapT(Sampler.Value.CLAMP);
        mSampler = bs.create();

        ProgramFragment.Builder texBuilder = new ProgramFragment.Builder(mRS);
        texBuilder.setTexture(ProgramFragment.Builder.EnvMode.REPLACE,
                              ProgramFragment.Builder.Format.RGBA, 0);
        mProgFragmentTexture = texBuilder.create();
        mProgFragmentTexture.bindSampler(mSampler, 0);

        ProgramFragment.Builder colBuilder = new ProgramFragment.Builder(mRS);
        colBuilder.setVaryingColor(false);
        mProgFragmentColor = colBuilder.create();

        mScript.set_gProgFragmentColor(mProgFragmentColor);
        mScript.set_gProgFragmentTexture(mProgFragmentTexture);
    }

    private void initProgramVertex() {
        ProgramVertex.Builder pvb = new ProgramVertex.Builder(mRS);
        mProgVertex = pvb.create();

        mPVA = new ProgramVertex.MatrixAllocation(mRS);
        mProgVertex.bindAllocation(mPVA);
        mPVA.setupOrthoWindow(mWidth, mHeight);

        mScript.set_gProgVertex(mProgVertex);
    }

    private Allocation loadTextureRGB(int id) {
        final Allocation allocation = Allocation.createFromBitmapResource(mRS, mRes,
                id, Element.RGB_565(mRS), false);
        allocation.uploadToTexture(0);
        return allocation;
    }

    private Allocation loadTextureARGB(int id) {
        Bitmap b = BitmapFactory.decodeResource(mRes, id, mOptionsARGB);
        final Allocation allocation = Allocation.createFromBitmap(mRS, b, Element.RGBA_8888(mRS), false);
        allocation.uploadToTexture(0);
        return allocation;
    }

    private void loadImages() {
        mTexOpaque = loadTextureRGB(R.drawable.data);
        mTexTransparent = loadTextureARGB(R.drawable.leaf);

        mScript.set_gTexOpaque(mTexOpaque);
        mScript.set_gTexTransparent(mTexTransparent);
    }

    private void initFonts() {
        // Sans font by family name
        mFontSans = Font.createFromFamily(mRS, mRes, "sans-serif", Font.Style.NORMAL, 8);
        // Create font by file name
        mFontSerif = Font.create(mRS, mRes, "DroidSerif-Regular.ttf", 8);
        // Create fonts by family and style
        mFontSerifBold = Font.createFromFamily(mRS, mRes, "serif", Font.Style.BOLD, 8);
        mFontSerifItalic = Font.createFromFamily(mRS, mRes, "serif", Font.Style.ITALIC, 8);
        mFontSerifBoldItalic = Font.createFromFamily(mRS, mRes, "serif", Font.Style.BOLD_ITALIC, 8);
        mFontMono = Font.createFromFamily(mRS, mRes, "mono", Font.Style.NORMAL, 8);

        mScript.set_gFontSans(mFontSans);
        mScript.set_gFontSerif(mFontSerif);
        mScript.set_gFontSerifBold(mFontSerifBold);
        mScript.set_gFontSerifItalic(mFontSerifItalic);
        mScript.set_gFontSerifBoldItalic(mFontSerifBoldItalic);
        mScript.set_gFontMono(mFontMono);
    }

    private void initRS() {

        mScript = new ScriptC_Rsrenderstates(mRS, mRes, R.raw.rsrenderstates, true);

        initProgramStore();
        initProgramFragment();
        initProgramVertex();

        initFonts();

        loadImages();



        mRS.contextBindRootScript(mScript);
    }
}



