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
        mMaxModes = 7;
        initRS();
    }

    private Resources mRes;
    private RenderScriptGL mRS;

    private Sampler mLinearClamp;
    private Sampler mLinearWrap;
    private Sampler mMipLinearWrap;
    private Sampler mNearestClamp;

    private ProgramStore mProgStoreBlendNoneDepth;
    private ProgramStore mProgStoreBlendNone;
    private ProgramStore mProgStoreBlendAlpha;
    private ProgramStore mProgStoreBlendAdd;

    private ProgramFragment mProgFragmentTexture;
    private ProgramFragment mProgFragmentColor;

    private ProgramVertex mProgVertex;
    private ProgramVertex.MatrixAllocation mPVA;

    // Custom shaders
    private ProgramVertex mProgVertexCustom;
    private ProgramVertex.MatrixAllocation mPVACustom;
    private ProgramFragment mProgFragmentCustom;
    private ScriptField_VertexShaderConstants_s mVSConst;
    private ScriptField_FragentShaderConstants_s mFSConst;

    private ProgramRaster mCullBack;
    private ProgramRaster mCullFront;

    private Allocation mTexTorus;
    private Allocation mTexOpaque;
    private Allocation mTexTransparent;

    private Allocation mAllocPV;

    private Mesh mMbyNMesh;
    private Mesh mTorus;

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

    private Mesh getMbyNMesh(float width, float height, int wResolution, int hResolution) {

        Mesh.TriangleMeshBuilder tmb = new Mesh.TriangleMeshBuilder(mRS,
                                           2, Mesh.TriangleMeshBuilder.TEXTURE_0);

        for (int y = 0; y <= hResolution; y++) {
            final float normalizedY = (float)y / hResolution;
            final float yOffset = (normalizedY - 0.5f) * height;
            for (int x = 0; x <= wResolution; x++) {
                float normalizedX = (float)x / wResolution;
                float xOffset = (normalizedX - 0.5f) * width;
                tmb.setTexture(normalizedX, normalizedY);
                tmb.addVertex(xOffset, yOffset);
             }
        }

        for (int y = 0; y < hResolution; y++) {
            final int curY = y * (wResolution + 1);
            final int belowY = (y + 1) * (wResolution + 1);
            for (int x = 0; x < wResolution; x++) {
                int curV = curY + x;
                int belowV = belowY + x;
                tmb.addTriangle(curV, belowV, curV + 1);
                tmb.addTriangle(belowV, belowV + 1, curV + 1);
            }
        }

        return tmb.create(true);
    }

    private void initProgramStore() {
        // Use stock the stock program store object
        mProgStoreBlendNoneDepth = ProgramStore.BLEND_NONE_DEPTH_TEST(mRS);
        mProgStoreBlendNone = ProgramStore.BLEND_NONE_DEPTH_NO_DEPTH(mRS);

        // Create a custom program store
        ProgramStore.Builder builder = new ProgramStore.Builder(mRS);
        builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
        builder.setBlendFunc(ProgramStore.BlendSrcFunc.SRC_ALPHA,
                             ProgramStore.BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        builder.setDitherEnable(false);
        builder.setDepthMask(false);
        mProgStoreBlendAlpha = builder.create();

        mProgStoreBlendAdd = ProgramStore.BLEND_ADD_DEPTH_NO_DEPTH(mRS);

        mScript.set_gProgStoreBlendNoneDepth(mProgStoreBlendNoneDepth);
        mScript.set_gProgStoreBlendNone(mProgStoreBlendNone);
        mScript.set_gProgStoreBlendAlpha(mProgStoreBlendAlpha);
        mScript.set_gProgStoreBlendAdd(mProgStoreBlendAdd);
    }

    private void initProgramFragment() {

        ProgramFragment.Builder texBuilder = new ProgramFragment.Builder(mRS);
        texBuilder.setTexture(ProgramFragment.Builder.EnvMode.REPLACE,
                              ProgramFragment.Builder.Format.RGBA, 0);
        mProgFragmentTexture = texBuilder.create();
        mProgFragmentTexture.bindSampler(mLinearClamp, 0);

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

    private void initCustomShaders() {
        mVSConst = new ScriptField_VertexShaderConstants_s(mRS, 1);
        mFSConst = new ScriptField_FragentShaderConstants_s(mRS, 1);

        mScript.bind_gVSConstants(mVSConst);
        mScript.bind_gFSConstants(mFSConst);

        // Initialize the shader builder
        ProgramVertex.ShaderBuilder pvbCustom = new ProgramVertex.ShaderBuilder(mRS);
        // Specify the resource that contains the shader string
        pvbCustom.setShader(mRes, R.raw.shaderv);
        // Use a script field to spcify the input layout
        pvbCustom.addInput(ScriptField_VertexShaderInputs_s.createElement(mRS));
        // Define the constant input layout
        pvbCustom.addConstant(mVSConst.getAllocation().getType());
        mProgVertexCustom = pvbCustom.create();
        // Bind the source of constant data
        mProgVertexCustom.bindConstants(mVSConst.getAllocation(), 1);
        mPVACustom = new ProgramVertex.MatrixAllocation(mRS);
        mProgVertexCustom.bindAllocation(mPVACustom);

        ProgramFragment.ShaderBuilder pfbCustom = new ProgramFragment.ShaderBuilder(mRS);
        // Specify the resource that contains the shader string
        pfbCustom.setShader(mRes, R.raw.shaderf);
        //Tell the builder how many textures we have
        pfbCustom.setTextureCount(1);
        // Define the constant input layout
        pfbCustom.addConstant(mFSConst.getAllocation().getType());
        mProgFragmentCustom = pfbCustom.create();
        // Bind the source of constant data
        mProgFragmentCustom.bindConstants(mFSConst.getAllocation(), 1);

        mScript.set_gProgVertexCustom(mProgVertexCustom);
        mScript.set_gProgFragmentCustom(mProgFragmentCustom);
    }

    private Allocation loadTextureRGB(int id) {
        final Allocation allocation = Allocation.createFromBitmapResource(mRS, mRes,
                id, Element.RGB_565(mRS), true);
        allocation.uploadToTexture(0);
        return allocation;
    }

    private Allocation loadTextureARGB(int id) {
        Bitmap b = BitmapFactory.decodeResource(mRes, id, mOptionsARGB);
        final Allocation allocation = Allocation.createFromBitmap(mRS, b, Element.RGBA_8888(mRS), true);
        allocation.uploadToTexture(0);
        return allocation;
    }

    private void loadImages() {
        mTexTorus = loadTextureRGB(R.drawable.torusmap);
        mTexOpaque = loadTextureRGB(R.drawable.data);
        mTexTransparent = loadTextureARGB(R.drawable.leaf);

        mScript.set_gTexTorus(mTexTorus);
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

    private void initMesh() {
        mMbyNMesh = getMbyNMesh(256, 256, 10, 10);
        mScript.set_gMbyNMesh(mMbyNMesh);

        FileA3D model = FileA3D.createFromResource(mRS, mRes, R.raw.torus);
        FileA3D.IndexEntry entry = model.getIndexEntry(0);
        if(entry == null || entry.getClassID() != FileA3D.ClassID.MESH) {
            Log.e("rs", "could not load model");
        }
        else {
            mTorus = (Mesh)entry.getObject();
            mScript.set_gTorusMesh(mTorus);
        }
    }

    private void initSamplers() {
        Sampler.Builder bs = new Sampler.Builder(mRS);
        bs.setMin(Sampler.Value.LINEAR);
        bs.setMag(Sampler.Value.LINEAR);
        bs.setWrapS(Sampler.Value.WRAP);
        bs.setWrapT(Sampler.Value.WRAP);
        mLinearWrap = bs.create();

        mLinearClamp = Sampler.CLAMP_LINEAR(mRS);
        mNearestClamp = Sampler.CLAMP_NEAREST(mRS);
        mMipLinearWrap = Sampler.WRAP_LINEAR_MIP_LINEAR(mRS);

        mScript.set_gLinearClamp(mLinearClamp);
        mScript.set_gLinearWrap(mLinearWrap);
        mScript.set_gMipLinearWrap(mMipLinearWrap);
        mScript.set_gNearestClamp(mNearestClamp);
    }

    private void initProgramRaster() {
        mCullBack = ProgramRaster.CULL_BACK(mRS);
        mCullFront = ProgramRaster.CULL_FRONT(mRS);

        mScript.set_gCullBack(mCullBack);
        mScript.set_gCullFront(mCullFront);
    }

    private void initRS() {

        mScript = new ScriptC_Rsrenderstates(mRS, mRes, R.raw.rsrenderstates, true);

        initSamplers();
        initProgramStore();
        initProgramFragment();
        initProgramVertex();
        initFonts();
        loadImages();
        initMesh();
        initProgramRaster();
        initCustomShaders();

        mRS.contextBindRootScript(mScript);
    }
}



