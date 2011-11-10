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


public class MeshTest implements RsBenchBaseTest{

    private static final String TAG = "MeshTest";
    private RenderScriptGL mRS;
    private Resources mRes;

    int mBenchmarkDimX;
    int mBenchmarkDimY;

    private Mesh m10by10Mesh;
    private Mesh m100by100Mesh;
    private Mesh mWbyHMesh;

    private ScriptC_mesh_test mGeoScript;

    private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();

    ScriptField_TestScripts_s.Item[] mTests;

    private final String[] mNames = {
        "Full screen mesh 10 by 10",
        "Full screen mesh 100 by 100",
        "Full screen mesh W / 4 by H / 4"
    };

    public MeshTest() {
        mBenchmarkDimX = 1280;
        mBenchmarkDimY = 720;
    }

    void addTest(int index, int meshNum) {
        mTests[index] = new ScriptField_TestScripts_s.Item();
        mTests[index].testScript = mGeoScript;
        mTests[index].testName = Allocation.createFromString(mRS,
                                                             mNames[index],
                                                             Allocation.USAGE_SCRIPT);

        ScriptField_MeshTestData_s.Item dataItem = new ScriptField_MeshTestData_s.Item();
        dataItem.meshNum = meshNum;
        ScriptField_MeshTestData_s testData = new ScriptField_MeshTestData_s(mRS, 1);
        testData.set(dataItem, 0, true);
        mTests[index].testData = testData.getAllocation();
    }

    public boolean init(RenderScriptGL rs, Resources res) {
        mRS = rs;
        mRes = res;
        initGeoScript();
        mTests = new ScriptField_TestScripts_s.Item[mNames.length];

        int index = 0;
        addTest(index++, 0 /*meshNum*/);
        addTest(index++, 1 /*meshNum*/);
        addTest(index++, 2 /*meshNum*/);

        return true;
    }

    public ScriptField_TestScripts_s.Item[] getTests() {
        return mTests;
    }

    public String[] getTestNames() {
        return mNames;
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
                tmb.setTexture((float)x % 2, (float)y % 2);
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

    private Allocation loadTextureRGB(int id) {
        return Allocation.createFromBitmapResource(mRS, mRes, id,
                Allocation.MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                Allocation.USAGE_GRAPHICS_TEXTURE);
    }

    void initGeoScript() {
        mGeoScript = new ScriptC_mesh_test(mRS, mRes, R.raw.mesh_test);

        ProgramVertexFixedFunction.Builder pvb = new ProgramVertexFixedFunction.Builder(mRS);
        ProgramVertexFixedFunction progVertex = pvb.create();
        ProgramVertexFixedFunction.Constants PVA = new ProgramVertexFixedFunction.Constants(mRS);
        ((ProgramVertexFixedFunction)progVertex).bindConstants(PVA);
        Matrix4f proj = new Matrix4f();
        proj.loadOrthoWindow(mBenchmarkDimX, mBenchmarkDimY);
        PVA.setProjection(proj);

        mGeoScript.set_gProgVertex(progVertex);
        ProgramFragmentFixedFunction.Builder texBuilder = new ProgramFragmentFixedFunction.Builder(mRS);
        texBuilder.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.REPLACE,
                              ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);
        mGeoScript.set_gProgFragmentTexture(texBuilder.create());
        mGeoScript.set_gProgStoreBlendNone(ProgramStore.BLEND_NONE_DEPTH_NONE(mRS));

        mGeoScript.set_gLinearClamp(Sampler.CLAMP_LINEAR(mRS));
        mGeoScript.set_gTexOpaque(loadTextureRGB(R.drawable.data));

        m10by10Mesh = getMbyNMesh(mBenchmarkDimX, mBenchmarkDimY, 10, 10);
        m100by100Mesh = getMbyNMesh(mBenchmarkDimX, mBenchmarkDimY, 100, 100);
        mWbyHMesh= getMbyNMesh(mBenchmarkDimX, mBenchmarkDimY, mBenchmarkDimX/4, mBenchmarkDimY/4);

        mGeoScript.set_g10by10Mesh(m10by10Mesh);
        mGeoScript.set_g100by100Mesh(m100by100Mesh);
        mGeoScript.set_gWbyHMesh(mWbyHMesh);
    }
}
