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
import android.renderscript.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;


import android.util.Log;


public class FillTest implements RsBenchBaseTest{

    private static final String TAG = "FillTest";
    private RenderScriptGL mRS;
    private Resources mRes;

    // Custom shaders
    private ProgramFragment mProgFragmentMultitex;
    private ProgramFragment mProgFragmentSingletex;
    private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();
    int mBenchmarkDimX;
    int mBenchmarkDimY;

    private ScriptC_fill_test mFillScript;
    ScriptField_TestScripts_s.Item[] mTests;

    private final String[] mNames = {
        "Fill screen 10x singletexture",
        "Fill screen 10x 3tex multitexture",
        "Fill screen 10x blended singletexture",
        "Fill screen 10x blended 3tex multitexture"
    };

    public FillTest() {
        mOptionsARGB.inScaled = false;
        mOptionsARGB.inPreferredConfig = Bitmap.Config.ARGB_8888;
        mBenchmarkDimX = 1280;
        mBenchmarkDimY = 720;
    }

    void addTest(int index, int testId, int blend, int quadCount) {
        mTests[index] = new ScriptField_TestScripts_s.Item();
        mTests[index].testScript = mFillScript;
        mTests[index].testName = Allocation.createFromString(mRS,
                                                             mNames[index],
                                                             Allocation.USAGE_SCRIPT);
        mTests[index].debugName = RsBenchRS.createZeroTerminatedAlloc(mRS,
                                                                      mNames[index],
                                                                      Allocation.USAGE_SCRIPT);

        ScriptField_FillTestData_s.Item dataItem = new ScriptField_FillTestData_s.Item();
        dataItem.testId = testId;
        dataItem.blend = blend;
        dataItem.quadCount = quadCount;
        ScriptField_FillTestData_s testData = new ScriptField_FillTestData_s(mRS, 1);
        testData.set(dataItem, 0, true);
        mTests[index].testData = testData.getAllocation();
    }

    public boolean init(RenderScriptGL rs, Resources res) {
        mRS = rs;
        mRes = res;
        initCustomShaders();
        initFillScript();
        mTests = new ScriptField_TestScripts_s.Item[mNames.length];

        int index = 0;

        addTest(index++, 1 /*testId*/, 0 /*blend*/, 10 /*quadCount*/);
        addTest(index++, 0 /*testId*/, 0 /*blend*/, 10 /*quadCount*/);
        addTest(index++, 1 /*testId*/, 1 /*blend*/, 10 /*quadCount*/);
        addTest(index++, 0 /*testId*/, 1 /*blend*/, 10 /*quadCount*/);

        return true;
    }

    public ScriptField_TestScripts_s.Item[] getTests() {
        return mTests;
    }

    public String[] getTestNames() {
        return mNames;
    }

    private void initCustomShaders() {
        ProgramFragment.Builder pfbCustom = new ProgramFragment.Builder(mRS);
        pfbCustom.setShader(mRes, R.raw.multitexf);
        for (int texCount = 0; texCount < 3; texCount ++) {
            pfbCustom.addTexture(Program.TextureType.TEXTURE_2D);
        }
        mProgFragmentMultitex = pfbCustom.create();

        pfbCustom = new ProgramFragment.Builder(mRS);
        pfbCustom.setShader(mRes, R.raw.singletexf);
        pfbCustom.addTexture(Program.TextureType.TEXTURE_2D);
        mProgFragmentSingletex = pfbCustom.create();
    }

    private Allocation loadTextureARGB(int id) {
        Bitmap b = BitmapFactory.decodeResource(mRes, id, mOptionsARGB);
        return Allocation.createFromBitmap(mRS, b,
                Allocation.MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                Allocation.USAGE_GRAPHICS_TEXTURE);
    }

    private Allocation loadTextureRGB(int id) {
        return Allocation.createFromBitmapResource(mRS, mRes, id,
                Allocation.MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                Allocation.USAGE_GRAPHICS_TEXTURE);
    }

    void initFillScript() {
        mFillScript = new ScriptC_fill_test(mRS, mRes, R.raw.fill_test);

        ProgramVertexFixedFunction.Builder pvb = new ProgramVertexFixedFunction.Builder(mRS);
        ProgramVertexFixedFunction progVertex = pvb.create();
        ProgramVertexFixedFunction.Constants PVA = new ProgramVertexFixedFunction.Constants(mRS);
        ((ProgramVertexFixedFunction)progVertex).bindConstants(PVA);
        Matrix4f proj = new Matrix4f();
        proj.loadOrthoWindow(mBenchmarkDimX, mBenchmarkDimY);
        PVA.setProjection(proj);
        mFillScript.set_gProgVertex(progVertex);

        mFillScript.set_gProgFragmentTexture(mProgFragmentSingletex);
        mFillScript.set_gProgFragmentMultitex(mProgFragmentMultitex);
        mFillScript.set_gProgStoreBlendNone(ProgramStore.BLEND_NONE_DEPTH_NONE(mRS));
        mFillScript.set_gProgStoreBlendAlpha(ProgramStore.BLEND_ALPHA_DEPTH_NONE(mRS));

        mFillScript.set_gLinearClamp(Sampler.CLAMP_LINEAR(mRS));
        mFillScript.set_gLinearWrap(Sampler.WRAP_LINEAR(mRS));
        mFillScript.set_gTexTorus(loadTextureRGB(R.drawable.torusmap));
        mFillScript.set_gTexOpaque(loadTextureRGB(R.drawable.data));
        mFillScript.set_gTexTransparent(loadTextureARGB(R.drawable.leaf));
        mFillScript.set_gTexChecker(loadTextureRGB(R.drawable.checker));
    }
}
