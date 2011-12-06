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
import android.renderscript.ProgramStore.DepthFunc;
import android.renderscript.ProgramStore.BlendSrcFunc;
import android.renderscript.ProgramStore.BlendDstFunc;
import android.renderscript.RenderScript.RSMessageHandler;
import android.renderscript.Mesh.Primitive;
import android.renderscript.Matrix4f;
import android.renderscript.ProgramVertexFixedFunction;

import android.util.Log;


public class UiTest implements RsBenchBaseTest{

    private static final String TAG = "UiTest";
    private static final String SAMPLE_TEXT = "Bench Test";
    private static final String LIST_TEXT =
      "This is a sample list of text to show in the list view";
    private static int PARTICLES_COUNT = 12000;

    private RenderScriptGL mRS;
    private Resources mRes;

    Font mFontSans;

    private ScriptField_ListAllocs_s mTextureAllocs;
    private ScriptField_ListAllocs_s mSampleTextAllocs;
    private ScriptField_ListAllocs_s mSampleListViewAllocs;
    private ScriptField_VpConsts mPvStarAlloc;
    private ProgramVertexFixedFunction.Constants mPvProjectionAlloc;

    private Mesh mSingleMesh;
    private Mesh mParticlesMesh;

    private ScriptC_ui_test mUiScript;

    private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();

    ScriptField_TestScripts_s.Item[] mTests;

    private final String[] mNames = {
        "UI test with icon display 10 by 10",
        "UI test with icon display 100 by 100",
        "UI test with image and text display 3 pages",
        "UI test with image and text display 5 pages",
        "UI test with list view",
        "UI test with live wallpaper"
    };

    public UiTest() {
    }

    void addTest(int index, int testId, int user1, int user2, int user3) {
        mTests[index] = new ScriptField_TestScripts_s.Item();
        mTests[index].testScript = mUiScript;
        mTests[index].testName = Allocation.createFromString(mRS,
                                                             mNames[index],
                                                             Allocation.USAGE_SCRIPT);
        mTests[index].debugName = RsBenchRS.createZeroTerminatedAlloc(mRS,
                                                                      mNames[index],
                                                                      Allocation.USAGE_SCRIPT);

        ScriptField_UiTestData_s.Item dataItem = new ScriptField_UiTestData_s.Item();
        dataItem.testId = testId;
        dataItem.user1 = user1;
        dataItem.user2 = user2;
        dataItem.user3 = user3;
        ScriptField_UiTestData_s testData = new ScriptField_UiTestData_s(mRS, 1);
        testData.set(dataItem, 0, true);
        mTests[index].testData = testData.getAllocation();
    }

    public boolean init(RenderScriptGL rs, Resources res) {
        mRS = rs;
        mRes = res;
        mFontSans = Font.create(mRS, mRes, "sans-serif", Font.Style.NORMAL, 8);
        mSingleMesh = getSingleMesh(1, 1);  // a unit size mesh

        initUiScript();
        mTests = new ScriptField_TestScripts_s.Item[mNames.length];

        int index = 0;

        addTest(index++, 0, 0 /*meshMode*/, 0 /*unused*/, 0 /*unused*/);
        addTest(index++, 0, 1 /*meshMode*/, 0 /*unused*/, 0 /*unused*/);
        addTest(index++, 1, 7 /*wResolution*/, 5 /*hResolution*/, 0 /*meshMode*/);
        addTest(index++, 1, 7 /*wResolution*/, 5 /*hResolution*/, 1 /*meshMode*/);
        addTest(index++, 2, 0 /*unused*/, 0 /*unused*/, 0 /*unused*/);
        addTest(index++, 3, 7 /*wResolution*/, 5 /*hResolution*/, 0 /*unused*/);

        return true;
    }

    public ScriptField_TestScripts_s.Item[] getTests() {
        return mTests;
    }

    public String[] getTestNames() {
        return mNames;
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

    private void createParticlesMesh() {
        ScriptField_Particle p = new ScriptField_Particle(mRS, PARTICLES_COUNT);

        final Mesh.AllocationBuilder meshBuilder = new Mesh.AllocationBuilder(mRS);
        meshBuilder.addVertexAllocation(p.getAllocation());
        final int vertexSlot = meshBuilder.getCurrentVertexTypeIndex();
        meshBuilder.addIndexSetType(Primitive.POINT);
        mParticlesMesh = meshBuilder.create();

        mUiScript.set_gParticlesMesh(mParticlesMesh);
        mUiScript.bind_Particles(p);
    }

    /**
     * Create a mesh with a single quad for the given width and height.
     */
    private Mesh getSingleMesh(float width, float height) {
        Mesh.TriangleMeshBuilder tmb = new Mesh.TriangleMeshBuilder(mRS,
                                           2, Mesh.TriangleMeshBuilder.TEXTURE_0);
        float xOffset = width/2;
        float yOffset = height/2;
        tmb.setTexture(0, 0);
        tmb.addVertex(-1.0f * xOffset, -1.0f * yOffset);
        tmb.setTexture(1, 0);
        tmb.addVertex(xOffset, -1.0f * yOffset);
        tmb.setTexture(1, 1);
        tmb.addVertex(xOffset, yOffset);
        tmb.setTexture(0, 1);
        tmb.addVertex(-1.0f * xOffset, yOffset);
        tmb.addTriangle(0, 3, 1);
        tmb.addTriangle(1, 3, 2);
        return tmb.create(true);
    }

    private Matrix4f getProjectionNormalized(int w, int h) {
        // range -1,1 in the narrow axis at z = 0.
        Matrix4f m1 = new Matrix4f();
        Matrix4f m2 = new Matrix4f();

        if(w > h) {
            float aspect = ((float)w) / h;
            m1.loadFrustum(-aspect,aspect,  -1,1,  1,100);
        } else {
            float aspect = ((float)h) / w;
            m1.loadFrustum(-1,1, -aspect,aspect, 1,100);
        }

        m2.loadRotate(180, 0, 1, 0);
        m1.loadMultiply(m1, m2);

        m2.loadScale(-2, 2, 1);
        m1.loadMultiply(m1, m2);

        m2.loadTranslate(0, 0, 2);
        m1.loadMultiply(m1, m2);
        return m1;
    }

    private void updateProjectionMatrices() {
        Matrix4f projNorm = getProjectionNormalized(1280, 720);
        ScriptField_VpConsts.Item i = new ScriptField_VpConsts.Item();
        i.Proj = projNorm;
        i.MVP = projNorm;
        mPvStarAlloc.set(i, 0, true);
        mPvProjectionAlloc.setProjection(projNorm);
    }

    void initUiScript() {
        mUiScript = new ScriptC_ui_test(mRS, mRes, R.raw.ui_test);

        ProgramFragmentFixedFunction.Builder colBuilder = new ProgramFragmentFixedFunction.Builder(mRS);
        colBuilder.setVaryingColor(false);
        ProgramFragmentFixedFunction.Builder texBuilder = new ProgramFragmentFixedFunction.Builder(mRS);
        texBuilder.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.REPLACE,
                              ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);

        ProgramVertexFixedFunction.Builder pvb = new ProgramVertexFixedFunction.Builder(mRS);
        ProgramVertexFixedFunction progVertex = pvb.create();
        ProgramVertexFixedFunction.Constants PVA = new ProgramVertexFixedFunction.Constants(mRS);
        ((ProgramVertexFixedFunction)progVertex).bindConstants(PVA);
        Matrix4f proj = new Matrix4f();
        proj.loadOrthoWindow(1280, 720);
        PVA.setProjection(proj);

        mUiScript.set_gProgVertex(progVertex);
        mUiScript.set_gProgFragmentColor(colBuilder.create());
        mUiScript.set_gProgFragmentTexture(texBuilder.create());
        mUiScript.set_gProgStoreBlendAlpha(ProgramStore.BLEND_ALPHA_DEPTH_NONE(mRS));

        mUiScript.set_gLinearClamp(Sampler.CLAMP_LINEAR(mRS));

        mUiScript.set_gTexTorus(loadTextureRGB(R.drawable.torusmap));
        mUiScript.set_gTexOpaque(loadTextureRGB(R.drawable.data));
        mUiScript.set_gTexGlobe(loadTextureRGB(R.drawable.globe));
        mUiScript.set_gSingleMesh(mSingleMesh);

        // For GALAXY
        ProgramStore.Builder psb = new ProgramStore.Builder(mRS);
        psb.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ZERO);
        mRS.bindProgramStore(psb.create());

        psb.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE);
        mUiScript.set_gPSLights(psb.create());

        // For Galaxy live wallpaper drawing
        ProgramFragmentFixedFunction.Builder builder = new ProgramFragmentFixedFunction.Builder(mRS);
        builder.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.REPLACE,
                           ProgramFragmentFixedFunction.Builder.Format.RGB, 0);
        ProgramFragment pfb = builder.create();
        pfb.bindSampler(Sampler.WRAP_NEAREST(mRS), 0);
        mUiScript.set_gPFBackground(pfb);

        builder = new ProgramFragmentFixedFunction.Builder(mRS);
        builder.setPointSpriteTexCoordinateReplacement(true);
        builder.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.MODULATE,
                           ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);
        builder.setVaryingColor(true);
        ProgramFragment pfs = builder.create();
        pfs.bindSampler(Sampler.WRAP_LINEAR_MIP_LINEAR(mRS), 0);
        mUiScript.set_gPFStars(pfs);

        mTextureAllocs = new ScriptField_ListAllocs_s(mRS, 100);
        for (int i = 0; i < 100; i++) {
            ScriptField_ListAllocs_s.Item texElem = new ScriptField_ListAllocs_s.Item();
            texElem.item = loadTextureRGB(R.drawable.globe);
            mTextureAllocs.set(texElem, i, false);
        }
        mTextureAllocs.copyAll();
        mUiScript.bind_gTexList100(mTextureAllocs);

        mSampleTextAllocs = new ScriptField_ListAllocs_s(mRS, 100);
        for (int i = 0; i < 100; i++) {
            ScriptField_ListAllocs_s.Item textElem = new ScriptField_ListAllocs_s.Item();
            textElem.item = Allocation.createFromString(mRS, SAMPLE_TEXT, Allocation.USAGE_SCRIPT);
            mSampleTextAllocs.set(textElem, i, false);
        }
        mSampleTextAllocs.copyAll();
        mUiScript.bind_gSampleTextList100(mSampleTextAllocs);

        mSampleListViewAllocs = new ScriptField_ListAllocs_s(mRS, 1000);
        for (int i = 0; i < 1000; i++) {
            ScriptField_ListAllocs_s.Item textElem = new ScriptField_ListAllocs_s.Item();
            textElem.item = Allocation.createFromString(mRS, LIST_TEXT, Allocation.USAGE_SCRIPT);
            mSampleListViewAllocs.set(textElem, i, false);
        }
        mSampleListViewAllocs.copyAll();
        mUiScript.bind_gListViewText(mSampleListViewAllocs);

        // For galaxy live wallpaper
        mPvStarAlloc = new ScriptField_VpConsts(mRS, 1);
        mUiScript.bind_vpConstants(mPvStarAlloc);
        mPvProjectionAlloc = new ProgramVertexFixedFunction.Constants(mRS);
        updateProjectionMatrices();

        pvb = new ProgramVertexFixedFunction.Builder(mRS);
        ProgramVertex pvbp = pvb.create();
        ((ProgramVertexFixedFunction)pvbp).bindConstants(mPvProjectionAlloc);
        mUiScript.set_gPVBkProj(pvbp);

        createParticlesMesh();

        ProgramVertex.Builder sb = new ProgramVertex.Builder(mRS);
        String t =  "varying vec4 varColor;\n" +
                    "varying vec2 varTex0;\n" +
                    "void main() {\n" +
                    "  float dist = ATTRIB_position.y;\n" +
                    "  float angle = ATTRIB_position.x;\n" +
                    "  float x = dist * sin(angle);\n" +
                    "  float y = dist * cos(angle) * 0.892;\n" +
                    "  float p = dist * 5.5;\n" +
                    "  float s = cos(p);\n" +
                    "  float t = sin(p);\n" +
                    "  vec4 pos;\n" +
                    "  pos.x = t * x + s * y;\n" +
                    "  pos.y = s * x - t * y;\n" +
                    "  pos.z = ATTRIB_position.z;\n" +
                    "  pos.w = 1.0;\n" +
                    "  gl_Position = UNI_MVP * pos;\n" +
                    "  gl_PointSize = ATTRIB_color.a * 10.0;\n" +
                    "  varColor.rgb = ATTRIB_color.rgb;\n" +
                    "  varColor.a = 1.0;\n" +
                    "}\n";
        sb.setShader(t);
        sb.addInput(mParticlesMesh.getVertexAllocation(0).getType().getElement());
        sb.addConstant(mPvStarAlloc.getType());
        ProgramVertex pvs = sb.create();
        pvs.bindConstants(mPvStarAlloc.getAllocation(), 0);
        mUiScript.set_gPVStars(pvs);

        // For Galaxy live wallpaper
        mUiScript.set_gTSpace(loadTextureRGB(R.drawable.space));
        mUiScript.set_gTLight1(loadTextureRGB(R.drawable.light1));
        mUiScript.set_gTFlares(loadTextureARGB(R.drawable.flares));

        mUiScript.set_gFontSans(mFontSans);
    }
}
