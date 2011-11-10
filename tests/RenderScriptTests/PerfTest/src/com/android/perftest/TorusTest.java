/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.perftest;

import android.os.Environment;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.renderscript.*;
import android.renderscript.Element.DataKind;
import android.renderscript.Element.DataType;
import android.renderscript.Allocation.MipmapControl;
import android.renderscript.Program.TextureType;
import android.renderscript.RenderScript.RSMessageHandler;
import android.renderscript.Mesh.Primitive;
import android.renderscript.Matrix4f;
import android.renderscript.ProgramVertexFixedFunction;

import android.util.Log;


public class TorusTest implements RsBenchBaseTest{

    private static final String TAG = "TorusTest";
    private RenderScriptGL mRS;
    private Resources mRes;

    private ProgramStore mProgStoreBlendNoneDepth;
    private ProgramStore mProgStoreBlendNone;
    private ProgramStore mProgStoreBlendAlpha;

    private ProgramFragment mProgFragmentTexture;
    private ProgramFragment mProgFragmentColor;

    private ProgramVertex mProgVertex;
    private ProgramVertexFixedFunction.Constants mPVA;
    private ProgramVertexFixedFunction.Constants mPvProjectionAlloc;

    // Custom shaders
    private ProgramVertex mProgVertexCustom;
    private ProgramFragment mProgFragmentCustom;
    private ProgramFragment mProgFragmentMultitex;
    private ProgramVertex mProgVertexPixelLight;
    private ProgramVertex mProgVertexPixelLightMove;
    private ProgramFragment mProgFragmentPixelLight;
    private ScriptField_VertexShaderConstants_s mVSConst;
    private ScriptField_FragentShaderConstants_s mFSConst;
    private ScriptField_VertexShaderConstants3_s mVSConstPixel;
    private ScriptField_FragentShaderConstants3_s mFSConstPixel;

    private Allocation mTexTorus;
    private Mesh mTorus;

    private ScriptC_torus_test mTorusScript;

    private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();

    ScriptField_TestScripts_s.Item[] mTests;

    private final String[] mNames = {
        "Geo test 25.6k flat color",
        "Geo test 51.2k flat color",
        "Geo test 204.8k small tries flat color",
        "Geo test 25.6k single texture",
        "Geo test 51.2k single texture",
        "Geo test 204.8k small tries single texture",
        "Geo test 25.6k geo heavy vertex",
        "Geo test 51.2k geo heavy vertex",
        "Geo test 204.8k geo raster load heavy vertex",
        "Geo test 25.6k heavy fragment",
        "Geo test 51.2k heavy fragment",
        "Geo test 204.8k small tries heavy fragment",
        "Geo test 25.6k heavy fragment heavy vertex",
        "Geo test 51.2k heavy fragment heavy vertex",
        "Geo test 204.8k small tries heavy fragment heavy vertex"
    };

    public TorusTest() {
    }

    void addTest(int index, int testId, int user1, int user2) {
        mTests[index] = new ScriptField_TestScripts_s.Item();
        mTests[index].testScript = mTorusScript;
        mTests[index].testName = Allocation.createFromString(mRS,
                                                             mNames[index],
                                                             Allocation.USAGE_SCRIPT);

        ScriptField_TorusTestData_s.Item dataItem = new ScriptField_TorusTestData_s.Item();
        dataItem.testId = testId;
        dataItem.user1 = user1;
        dataItem.user2 = user2;
        ScriptField_TorusTestData_s testData = new ScriptField_TorusTestData_s(mRS, 1);
        testData.set(dataItem, 0, true);
        mTests[index].testData = testData.getAllocation();
    }

    public boolean init(RenderScriptGL rs, Resources res) {
        mRS = rs;
        mRes = res;
        initCustomShaders();
        loadImages();
        initMesh();
        initTorusScript();
        mTests = new ScriptField_TestScripts_s.Item[mNames.length];

        int index = 0;
        addTest(index++, 0, 0 /*useTexture*/, 1 /*numMeshes*/);
        addTest(index++, 0, 0 /*useTexture*/, 2 /*numMeshes*/);
        addTest(index++, 0, 0 /*useTexture*/, 8 /*numMeshes*/);
        addTest(index++, 0, 1 /*useTexture*/, 1 /*numMeshes*/);
        addTest(index++, 0, 1 /*useTexture*/, 2 /*numMeshes*/);
        addTest(index++, 0, 1 /*useTexture*/, 8 /*numMeshes*/);

        // Secont test
        addTest(index++, 1, 1 /*numMeshes*/, 0 /*unused*/);
        addTest(index++, 1, 2 /*numMeshes*/, 0 /*unused*/);
        addTest(index++, 1, 8 /*numMeshes*/, 0 /*unused*/);

        // Third test
        addTest(index++, 2, 1 /*numMeshes*/, 0 /*heavyVertex*/);
        addTest(index++, 2, 2 /*numMeshes*/, 0 /*heavyVertex*/);
        addTest(index++, 2, 8 /*numMeshes*/, 0 /*heavyVertex*/);
        addTest(index++, 2, 1 /*numMeshes*/, 1 /*heavyVertex*/);
        addTest(index++, 2, 2 /*numMeshes*/, 1 /*heavyVertex*/);
        addTest(index++, 2, 8 /*numMeshes*/, 1 /*heavyVertex*/);

        return true;
    }

    public ScriptField_TestScripts_s.Item[] getTests() {
        return mTests;
    }

    public String[] getTestNames() {
        return mNames;
    }

    private void initCustomShaders() {
        mVSConst = new ScriptField_VertexShaderConstants_s(mRS, 1);
        mFSConst = new ScriptField_FragentShaderConstants_s(mRS, 1);

        mVSConstPixel = new ScriptField_VertexShaderConstants3_s(mRS, 1);
        mFSConstPixel = new ScriptField_FragentShaderConstants3_s(mRS, 1);

        // Initialize the shader builder
        ProgramVertex.Builder pvbCustom = new ProgramVertex.Builder(mRS);
        // Specify the resource that contains the shader string
        pvbCustom.setShader(mRes, R.raw.shaderv);
        // Use a script field to specify the input layout
        pvbCustom.addInput(ScriptField_VertexShaderInputs_s.createElement(mRS));
        // Define the constant input layout
        pvbCustom.addConstant(mVSConst.getAllocation().getType());
        mProgVertexCustom = pvbCustom.create();
        // Bind the source of constant data
        mProgVertexCustom.bindConstants(mVSConst.getAllocation(), 0);

        ProgramFragment.Builder pfbCustom = new ProgramFragment.Builder(mRS);
        // Specify the resource that contains the shader string
        pfbCustom.setShader(mRes, R.raw.shaderf);
        // Tell the builder how many textures we have
        pfbCustom.addTexture(Program.TextureType.TEXTURE_2D);
        // Define the constant input layout
        pfbCustom.addConstant(mFSConst.getAllocation().getType());
        mProgFragmentCustom = pfbCustom.create();
        // Bind the source of constant data
        mProgFragmentCustom.bindConstants(mFSConst.getAllocation(), 0);

        pvbCustom = new ProgramVertex.Builder(mRS);
        pvbCustom.setShader(mRes, R.raw.shader2v);
        pvbCustom.addInput(ScriptField_VertexShaderInputs_s.createElement(mRS));
        pvbCustom.addConstant(mVSConstPixel.getAllocation().getType());
        mProgVertexPixelLight = pvbCustom.create();
        mProgVertexPixelLight.bindConstants(mVSConstPixel.getAllocation(), 0);

        pvbCustom = new ProgramVertex.Builder(mRS);
        pvbCustom.setShader(mRes, R.raw.shader2movev);
        pvbCustom.addInput(ScriptField_VertexShaderInputs_s.createElement(mRS));
        pvbCustom.addConstant(mVSConstPixel.getAllocation().getType());
        mProgVertexPixelLightMove = pvbCustom.create();
        mProgVertexPixelLightMove.bindConstants(mVSConstPixel.getAllocation(), 0);

        pfbCustom = new ProgramFragment.Builder(mRS);
        pfbCustom.setShader(mRes, R.raw.shader2f);
        pfbCustom.addTexture(Program.TextureType.TEXTURE_2D);
        pfbCustom.addConstant(mFSConstPixel.getAllocation().getType());
        mProgFragmentPixelLight = pfbCustom.create();
        mProgFragmentPixelLight.bindConstants(mFSConstPixel.getAllocation(), 0);

        pfbCustom = new ProgramFragment.Builder(mRS);
        pfbCustom.setShader(mRes, R.raw.multitexf);
        for (int texCount = 0; texCount < 3; texCount ++) {
            pfbCustom.addTexture(Program.TextureType.TEXTURE_2D);
        }
        mProgFragmentMultitex = pfbCustom.create();

        ProgramFragmentFixedFunction.Builder colBuilder = new ProgramFragmentFixedFunction.Builder(mRS);
        colBuilder.setVaryingColor(false);
        mProgFragmentColor = colBuilder.create();

        ProgramFragmentFixedFunction.Builder texBuilder = new ProgramFragmentFixedFunction.Builder(mRS);
        texBuilder.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.REPLACE,
                              ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);
        mProgFragmentTexture = texBuilder.create();

        ProgramVertexFixedFunction.Builder pvb = new ProgramVertexFixedFunction.Builder(mRS);
        mProgVertex = pvb.create();
        ProgramVertexFixedFunction.Constants PVA = new ProgramVertexFixedFunction.Constants(mRS);
        ((ProgramVertexFixedFunction)mProgVertex).bindConstants(PVA);
        Matrix4f proj = new Matrix4f();
        proj.loadOrthoWindow(1280, 720);
        PVA.setProjection(proj);
    }

    private Allocation loadTextureRGB(int id) {
        return Allocation.createFromBitmapResource(mRS, mRes, id,
                Allocation.MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                Allocation.USAGE_GRAPHICS_TEXTURE);
    }

    private void loadImages() {
        mTexTorus = loadTextureRGB(R.drawable.torusmap);
    }

    private void initMesh() {
        FileA3D model = FileA3D.createFromResource(mRS, mRes, R.raw.torus);
        FileA3D.IndexEntry entry = model.getIndexEntry(0);
        if (entry == null || entry.getEntryType() != FileA3D.EntryType.MESH) {
            Log.e("rs", "could not load model");
        } else {
            mTorus = (Mesh)entry.getObject();
        }
    }

    void initTorusScript() {
        mTorusScript = new ScriptC_torus_test(mRS, mRes, R.raw.torus_test);
        mTorusScript.set_gCullFront(ProgramRaster.CULL_FRONT(mRS));
        mTorusScript.set_gCullBack(ProgramRaster.CULL_BACK(mRS));
        mTorusScript.set_gLinearClamp(Sampler.CLAMP_LINEAR(mRS));
        mTorusScript.set_gTorusMesh(mTorus);
        mTorusScript.set_gTexTorus(mTexTorus);
        mTorusScript.set_gProgVertexCustom(mProgVertexCustom);
        mTorusScript.set_gProgFragmentCustom(mProgFragmentCustom);
        mTorusScript.set_gProgVertexPixelLight(mProgVertexPixelLight);
        mTorusScript.set_gProgVertexPixelLightMove(mProgVertexPixelLightMove);
        mTorusScript.set_gProgFragmentPixelLight(mProgFragmentPixelLight);
        mTorusScript.bind_gVSConstPixel(mVSConstPixel);
        mTorusScript.bind_gFSConstPixel(mFSConstPixel);
        mTorusScript.bind_gVSConstants(mVSConst);
        mTorusScript.bind_gFSConstants(mFSConst);
        mTorusScript.set_gProgVertex(mProgVertex);
        mTorusScript.set_gProgFragmentTexture(mProgFragmentTexture);
        mTorusScript.set_gProgFragmentColor(mProgFragmentColor);
        mTorusScript.set_gProgStoreBlendNoneDepth(mProgStoreBlendNoneDepth);
    }
}
