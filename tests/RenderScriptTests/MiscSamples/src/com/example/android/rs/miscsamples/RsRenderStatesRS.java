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

package com.example.android.rs.miscsamples;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.renderscript.*;
import android.renderscript.Font.Style;
import android.renderscript.Program.TextureType;
import android.renderscript.ProgramStore.DepthFunc;
import android.renderscript.ProgramStore.BlendSrcFunc;
import android.renderscript.ProgramStore.BlendDstFunc;
import android.renderscript.Sampler.Value;
import android.util.Log;


public class RsRenderStatesRS {

    int mWidth;
    int mHeight;

    public RsRenderStatesRS() {
    }

    public void init(RenderScriptGL rs, Resources res) {
        mRS = rs;
        mWidth = mRS.getWidth();
        mHeight = mRS.getHeight();
        mRes = res;
        mOptionsARGB.inScaled = false;
        mOptionsARGB.inPreferredConfig = Bitmap.Config.ARGB_8888;
        mMode = 0;
        mMaxModes = 0;
        initRS();
    }

    public void surfaceChanged() {
        mWidth = mRS.getWidth();
        mHeight = mRS.getHeight();

        Matrix4f proj = new Matrix4f();
        proj.loadOrthoWindow(mWidth, mHeight);
        mPVA.setProjection(proj);
    }

    private Resources mRes;
    private RenderScriptGL mRS;

    private Sampler mLinearClamp;
    private Sampler mLinearWrap;
    private Sampler mMipLinearWrap;
    private Sampler mNearestClamp;
    private Sampler mMipLinearAniso8;
    private Sampler mMipLinearAniso15;

    private ProgramStore mProgStoreBlendNoneDepth;
    private ProgramStore mProgStoreBlendNone;
    private ProgramStore mProgStoreBlendAlpha;
    private ProgramStore mProgStoreBlendAdd;

    private ProgramFragment mProgFragmentTexture;
    private ProgramFragment mProgFragmentColor;

    private ProgramVertex mProgVertex;
    private ProgramVertexFixedFunction.Constants mPVA;

    // Custom shaders
    private ProgramVertex mProgVertexCustom;
    private ProgramFragment mProgFragmentCustom;
    private ProgramFragment mProgFragmentMultitex;
    private ScriptField_VertexShaderConstants_s mVSConst;
    private ScriptField_VertexShaderConstants2_s mVSConst2;
    private ScriptField_FragentShaderConstants_s mFSConst;
    private ScriptField_FragentShaderConstants2_s mFSConst2;

    private ProgramVertex mProgVertexCustom2;
    private ProgramFragment mProgFragmentCustom2;

    private ProgramVertex mProgVertexCube;
    private ProgramFragment mProgFragmentCube;

    private ProgramRaster mCullBack;
    private ProgramRaster mCullFront;
    private ProgramRaster mCullNone;

    private Allocation mTexTorus;
    private Allocation mTexOpaque;
    private Allocation mTexTransparent;
    private Allocation mTexChecker;
    private Allocation mTexCube;

    private Mesh mMbyNMesh;
    private Mesh mTorus;

    Font mFontSans;
    Font mFontSerif;
    Font mFontSerifBold;
    Font mFontSerifItalic;
    Font mFontSerifBoldItalic;
    Font mFontMono;
    private Allocation mTextAlloc;

    private ScriptC_rsrenderstates mScript;

    private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();

    int mMode;
    int mMaxModes;

    public void onActionDown(int x, int y) {
        mMode ++;
        mMode = mMode % mMaxModes;
        mScript.set_gDisplayMode(mMode);
    }

    ProgramStore BLEND_ADD_DEPTH_NONE(RenderScript rs) {
        ProgramStore.Builder builder = new ProgramStore.Builder(rs);
        builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ONE);
        builder.setDitherEnabled(false);
        builder.setDepthMaskEnabled(false);
        return builder.create();
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
        mProgStoreBlendNone = ProgramStore.BLEND_NONE_DEPTH_NONE(mRS);

        // Create a custom program store
        ProgramStore.Builder builder = new ProgramStore.Builder(mRS);
        builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
        builder.setBlendFunc(ProgramStore.BlendSrcFunc.SRC_ALPHA,
                             ProgramStore.BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        builder.setDitherEnabled(false);
        builder.setDepthMaskEnabled(false);
        mProgStoreBlendAlpha = builder.create();

        mProgStoreBlendAdd = BLEND_ADD_DEPTH_NONE(mRS);

        mScript.set_gProgStoreBlendNoneDepth(mProgStoreBlendNoneDepth);
        mScript.set_gProgStoreBlendNone(mProgStoreBlendNone);
        mScript.set_gProgStoreBlendAlpha(mProgStoreBlendAlpha);
        mScript.set_gProgStoreBlendAdd(mProgStoreBlendAdd);
    }

    private void initProgramFragment() {

        ProgramFragmentFixedFunction.Builder texBuilder = new ProgramFragmentFixedFunction.Builder(mRS);
        texBuilder.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.REPLACE,
                              ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);
        mProgFragmentTexture = texBuilder.create();
        mProgFragmentTexture.bindSampler(mLinearClamp, 0);

        ProgramFragmentFixedFunction.Builder colBuilder = new ProgramFragmentFixedFunction.Builder(mRS);
        colBuilder.setVaryingColor(false);
        mProgFragmentColor = colBuilder.create();

        mScript.set_gProgFragmentColor(mProgFragmentColor);
        mScript.set_gProgFragmentTexture(mProgFragmentTexture);
    }

    private void initProgramVertex() {
        ProgramVertexFixedFunction.Builder pvb = new ProgramVertexFixedFunction.Builder(mRS);
        mProgVertex = pvb.create();

        mPVA = new ProgramVertexFixedFunction.Constants(mRS);
        ((ProgramVertexFixedFunction)mProgVertex).bindConstants(mPVA);
        Matrix4f proj = new Matrix4f();
        proj.loadOrthoWindow(mWidth, mHeight);
        mPVA.setProjection(proj);

        mScript.set_gProgVertex(mProgVertex);
    }

    private void initCustomShaders() {
        mVSConst = new ScriptField_VertexShaderConstants_s(mRS, 1);
        mVSConst2 = new ScriptField_VertexShaderConstants2_s(mRS, 1);
        mFSConst = new ScriptField_FragentShaderConstants_s(mRS, 1);
        mFSConst2 = new ScriptField_FragentShaderConstants2_s(mRS, 1);

        mScript.bind_gVSConstants(mVSConst);
        mScript.bind_gVSConstants2(mVSConst2);
        mScript.bind_gFSConstants(mFSConst);
        mScript.bind_gFSConstants2(mFSConst2);

        // Initialize the shader builder
        ProgramVertex.Builder pvbCustom = new ProgramVertex.Builder(mRS);
        // Specify the resource that contains the shader string
        pvbCustom.setShader(mRes, R.raw.shaderv);
        // Use a script field to spcify the input layout
        pvbCustom.addInput(ScriptField_VertexShaderInputs_s.createElement(mRS));
        // Define the constant input layout
        pvbCustom.addConstant(mVSConst.getAllocation().getType());
        mProgVertexCustom = pvbCustom.create();
        // Bind the source of constant data
        mProgVertexCustom.bindConstants(mVSConst.getAllocation(), 0);

        ProgramFragment.Builder pfbCustom = new ProgramFragment.Builder(mRS);
        // Specify the resource that contains the shader string
        pfbCustom.setShader(mRes, R.raw.shaderf);
        //Tell the builder how many textures we have
        pfbCustom.addTexture(Program.TextureType.TEXTURE_2D);
        // Define the constant input layout
        pfbCustom.addConstant(mFSConst.getAllocation().getType());
        mProgFragmentCustom = pfbCustom.create();
        // Bind the source of constant data
        mProgFragmentCustom.bindConstants(mFSConst.getAllocation(), 0);

        pvbCustom = new ProgramVertex.Builder(mRS);
        pvbCustom.setShader(mRes, R.raw.shaderarrayv);
        pvbCustom.addInput(ScriptField_VertexShaderInputs_s.createElement(mRS));
        pvbCustom.addConstant(mVSConst2.getAllocation().getType());
        mProgVertexCustom2 = pvbCustom.create();
        mProgVertexCustom2.bindConstants(mVSConst2.getAllocation(), 0);

        pfbCustom = new ProgramFragment.Builder(mRS);
        pfbCustom.setShader(mRes, R.raw.shaderarrayf);
        pfbCustom.addTexture(Program.TextureType.TEXTURE_2D);
        pfbCustom.addConstant(mFSConst2.getAllocation().getType());
        mProgFragmentCustom2 = pfbCustom.create();
        mProgFragmentCustom2.bindConstants(mFSConst2.getAllocation(), 0);

        // Cubemap test shaders
        pvbCustom = new ProgramVertex.Builder(mRS);
        pvbCustom.setShader(mRes, R.raw.shadercubev);
        pvbCustom.addInput(ScriptField_VertexShaderInputs_s.createElement(mRS));
        pvbCustom.addConstant(mVSConst.getAllocation().getType());
        mProgVertexCube = pvbCustom.create();
        mProgVertexCube.bindConstants(mVSConst.getAllocation(), 0);

        pfbCustom = new ProgramFragment.Builder(mRS);
        pfbCustom.setShader(mRes, R.raw.shadercubef);
        pfbCustom.addTexture(Program.TextureType.TEXTURE_CUBE);
        mProgFragmentCube = pfbCustom.create();

        pfbCustom = new ProgramFragment.Builder(mRS);
        pfbCustom.setShader(mRes, R.raw.multitexf);
        for (int texCount = 0; texCount < 3; texCount ++) {
            pfbCustom.addTexture(Program.TextureType.TEXTURE_2D);
        }
        mProgFragmentMultitex = pfbCustom.create();

        mScript.set_gProgVertexCustom(mProgVertexCustom);
        mScript.set_gProgFragmentCustom(mProgFragmentCustom);
        mScript.set_gProgVertexCustom2(mProgVertexCustom2);
        mScript.set_gProgFragmentCustom2(mProgFragmentCustom2);
        mScript.set_gProgVertexCube(mProgVertexCube);
        mScript.set_gProgFragmentCube(mProgFragmentCube);
        mScript.set_gProgFragmentMultitex(mProgFragmentMultitex);
    }

    private Allocation loadTextureRGB(int id) {
        return Allocation.createFromBitmapResource(mRS, mRes, id,
                                                   Allocation.MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                                                   Allocation.USAGE_GRAPHICS_TEXTURE);
    }

    private Allocation loadTextureARGB(int id) {
        Bitmap b = BitmapFactory.decodeResource(mRes, id, mOptionsARGB);
        return Allocation.createFromBitmap(mRS, b,
                                           Allocation.MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                                           Allocation.USAGE_GRAPHICS_TEXTURE);
    }

    private void loadImages() {
        mTexTorus = loadTextureRGB(R.drawable.torusmap);
        mTexOpaque = loadTextureRGB(R.drawable.data);
        mTexTransparent = loadTextureARGB(R.drawable.leaf);
        mTexChecker = loadTextureRGB(R.drawable.checker);
        Bitmap b = BitmapFactory.decodeResource(mRes, R.drawable.cubemap_test);
        mTexCube = Allocation.createCubemapFromBitmap(mRS, b);

        mScript.set_gTexTorus(mTexTorus);
        mScript.set_gTexOpaque(mTexOpaque);
        mScript.set_gTexTransparent(mTexTransparent);
        mScript.set_gTexChecker(mTexChecker);
        mScript.set_gTexCube(mTexCube);
    }

    private void initFonts() {
        // Sans font by family name
        mFontSans = Font.create(mRS, mRes, "sans-serif", Font.Style.NORMAL, 8);
        mFontSerif = Font.create(mRS, mRes, "serif", Font.Style.NORMAL, 8);
        // Create fonts by family and style
        mFontSerifBold = Font.create(mRS, mRes, "serif", Font.Style.BOLD, 8);
        mFontSerifItalic = Font.create(mRS, mRes, "serif", Font.Style.ITALIC, 8);
        mFontSerifBoldItalic = Font.create(mRS, mRes, "serif", Font.Style.BOLD_ITALIC, 8);
        mFontMono = Font.create(mRS, mRes, "mono", Font.Style.NORMAL, 8);

        mTextAlloc = Allocation.createFromString(mRS, "String from allocation", Allocation.USAGE_SCRIPT);

        mScript.set_gFontSans(mFontSans);
        mScript.set_gFontSerif(mFontSerif);
        mScript.set_gFontSerifBold(mFontSerifBold);
        mScript.set_gFontSerifItalic(mFontSerifItalic);
        mScript.set_gFontSerifBoldItalic(mFontSerifBoldItalic);
        mScript.set_gFontMono(mFontMono);
        mScript.set_gTextAlloc(mTextAlloc);
    }

    private void initMesh() {
        mMbyNMesh = getMbyNMesh(256, 256, 10, 10);
        mScript.set_gMbyNMesh(mMbyNMesh);

        FileA3D model = FileA3D.createFromResource(mRS, mRes, R.raw.torus);
        FileA3D.IndexEntry entry = model.getIndexEntry(0);
        if (entry == null || entry.getEntryType() != FileA3D.EntryType.MESH) {
            Log.e("rs", "could not load model");
        } else {
            mTorus = (Mesh)entry.getObject();
            mScript.set_gTorusMesh(mTorus);
        }
    }

    private void initSamplers() {
        Sampler.Builder bs = new Sampler.Builder(mRS);
        bs.setMinification(Sampler.Value.LINEAR);
        bs.setMagnification(Sampler.Value.LINEAR);
        bs.setWrapS(Sampler.Value.WRAP);
        bs.setWrapT(Sampler.Value.WRAP);
        mLinearWrap = bs.create();

        mLinearClamp = Sampler.CLAMP_LINEAR(mRS);
        mNearestClamp = Sampler.CLAMP_NEAREST(mRS);
        mMipLinearWrap = Sampler.WRAP_LINEAR_MIP_LINEAR(mRS);

        bs = new Sampler.Builder(mRS);
        bs.setMinification(Sampler.Value.LINEAR_MIP_LINEAR);
        bs.setMagnification(Sampler.Value.LINEAR);
        bs.setWrapS(Sampler.Value.WRAP);
        bs.setWrapT(Sampler.Value.WRAP);
        bs.setAnisotropy(8.0f);
        mMipLinearAniso8 = bs.create();
        bs.setAnisotropy(15.0f);
        mMipLinearAniso15 = bs.create();

        mScript.set_gLinearClamp(mLinearClamp);
        mScript.set_gLinearWrap(mLinearWrap);
        mScript.set_gMipLinearWrap(mMipLinearWrap);
        mScript.set_gMipLinearAniso8(mMipLinearAniso8);
        mScript.set_gMipLinearAniso15(mMipLinearAniso15);
        mScript.set_gNearestClamp(mNearestClamp);
    }

    private void initProgramRaster() {
        mCullBack = ProgramRaster.CULL_BACK(mRS);
        mCullFront = ProgramRaster.CULL_FRONT(mRS);
        mCullNone = ProgramRaster.CULL_NONE(mRS);

        mScript.set_gCullBack(mCullBack);
        mScript.set_gCullFront(mCullFront);
        mScript.set_gCullNone(mCullNone);
    }

    private void initRS() {

        mScript = new ScriptC_rsrenderstates(mRS, mRes, R.raw.rsrenderstates);

        mMaxModes = mScript.get_gMaxModes();

        initSamplers();
        initProgramStore();
        initProgramFragment();
        initProgramVertex();
        initFonts();
        loadImages();
        initMesh();
        initProgramRaster();
        initCustomShaders();

        mRS.bindRootScript(mScript);
    }
}



