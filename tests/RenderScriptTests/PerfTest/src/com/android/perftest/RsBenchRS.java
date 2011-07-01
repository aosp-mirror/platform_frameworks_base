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

package com.android.perftest;

import java.io.Writer;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

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
import android.renderscript.Sampler.Value;
import android.renderscript.Mesh.Primitive;
import android.renderscript.Matrix4f;
import android.renderscript.ProgramVertexFixedFunction;

import android.util.Log;


public class RsBenchRS {

    private static final String TAG = "RsBenchRS";
    private static final String SAMPLE_TEXT = "Bench Test";
    private static final String LIST_TEXT =
      "This is a sample list of text to show in the list view";
    private static int PARTICLES_COUNT = 12000;
    int mWidth;
    int mHeight;
    int mLoops;
    int mCurrentLoop;

    int mBenchmarkDimX;
    int mBenchmarkDimY;

    public RsBenchRS() {
    }

    public void init(RenderScriptGL rs, Resources res, int width, int height, int loops) {
        mRS = rs;
        mRes = res;
        mWidth = width;
        mHeight = height;
        mOptionsARGB.inScaled = false;
        mOptionsARGB.inPreferredConfig = Bitmap.Config.ARGB_8888;
        mMode = 0;
        mMaxModes = 0;
        mLoops = loops;
        mCurrentLoop = 0;
        mBenchmarkDimX = 1280;
        mBenchmarkDimY = 720;
        initRS();
    }

    private boolean stopTest = false;

    private Resources mRes;
    private RenderScriptGL mRS;

    private ProgramStore mProgStoreBlendNoneDepth;
    private ProgramStore mProgStoreBlendNone;
    private ProgramStore mProgStoreBlendAlpha;
    private ProgramStore mProgStoreBlendAdd;

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
    private Allocation mTexOpaque;
    private Allocation mTexTransparent;
    private Allocation mTexChecker;
    private Allocation mTexGlobe;

    private Mesh m10by10Mesh;
    private Mesh m100by100Mesh;
    private Mesh mWbyHMesh;
    private Mesh mTorus;
    private Mesh mSingleMesh;
    private Mesh mParticlesMesh;

    Font mFontSans;
    Font mFontSerif;
    private Allocation mTextAlloc;

    private ScriptField_ListAllocs_s mTextureAllocs;
    private ScriptField_ListAllocs_s mSampleTextAllocs;
    private ScriptField_ListAllocs_s mSampleListViewAllocs;
    private ScriptField_VpConsts mPvStarAlloc;


    private ScriptC_rsbench mScript;
    private ScriptC_text_test mTextScript;
    private ScriptC_torus_test mTorusScript;

    private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();

    int mMode;
    int mMaxModes;

    String[] mTestNames;
    float[] mLocalTestResults;

    public void onActionDown(int x, int y) {
        mMode ++;
        mMode = mMode % mMaxModes;
        mScript.set_gDisplayMode(mMode);
    }

    private void saveTestResults() {
        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            Log.v(TAG, "sdcard is read only");
            return;
        }
        File sdCard = Environment.getExternalStorageDirectory();
        if (!sdCard.canWrite()) {
            Log.v(TAG, "ssdcard is read only");
            return;
        }

        File resultFile = new File(sdCard, "rsbench_result" + mCurrentLoop + ".csv");
        resultFile.setWritable(true, false);

        try {
            BufferedWriter results = new BufferedWriter(new FileWriter(resultFile));
            for (int i = 0; i < mLocalTestResults.length; i ++) {
                results.write(mTestNames[i] + ", " + mLocalTestResults[i] + ",\n");
            }
            results.close();
            Log.v(TAG, "Saved results in: " + resultFile.getAbsolutePath());
        } catch (IOException e) {
            Log.v(TAG, "Unable to write result file " + e.getMessage());
        }
    }

    /**
     * Create a message handler to handle message sent from the script
     */
    protected RSMessageHandler mRsMessage = new RSMessageHandler() {
        public void run() {
            if (mID == mScript.get_RS_MSG_RESULTS_READY()) {
                for (int i = 0; i < mLocalTestResults.length; i ++) {
                    mLocalTestResults[i] = Float.intBitsToFloat(mData[i]);
                }
                saveTestResults();
                if (mLoops > 0) {
                    mCurrentLoop ++;
                    mCurrentLoop = mCurrentLoop % mLoops;
                }
                return;

            } else if (mID == mScript.get_RS_MSG_TEST_DONE()) {
                synchronized(this) {
                    stopTest = true;
                    this.notifyAll();
                }
                return;
            } else {
                Log.v(TAG, "Perf test got unexpected message");
                return;
            }
        }
    };

    /**
     * Wait for message from the script
     */
    public boolean testIsFinished() {
        synchronized(this) {
            while (true) {
                if (stopTest) {
                    return true;
                } else {
                    try {
                        this.wait(60*1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
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

        // For GALAXY
        builder = new ProgramStore.Builder(mRS);
        builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ZERO);
        mRS.bindProgramStore(builder.create());

        builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE);
        mScript.set_gPSLights(builder.create());

    }

    private void initProgramFragment() {

        ProgramFragmentFixedFunction.Builder texBuilder = new ProgramFragmentFixedFunction.Builder(mRS);
        texBuilder.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.REPLACE,
                              ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);
        mProgFragmentTexture = texBuilder.create();
        mProgFragmentTexture.bindSampler(Sampler.CLAMP_LINEAR(mRS), 0);

        ProgramFragmentFixedFunction.Builder colBuilder = new ProgramFragmentFixedFunction.Builder(mRS);
        colBuilder.setVaryingColor(false);
        mProgFragmentColor = colBuilder.create();

        mScript.set_gProgFragmentColor(mProgFragmentColor);

        mScript.set_gProgFragmentTexture(mProgFragmentTexture);



        // For Galaxy live wallpaper drawing
        ProgramFragmentFixedFunction.Builder builder = new ProgramFragmentFixedFunction.Builder(mRS);
        builder.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.REPLACE,
                           ProgramFragmentFixedFunction.Builder.Format.RGB, 0);
        ProgramFragment pfb = builder.create();
        pfb.bindSampler(Sampler.WRAP_NEAREST(mRS), 0);
        mScript.set_gPFBackground(pfb);

        builder = new ProgramFragmentFixedFunction.Builder(mRS);
        builder.setPointSpriteTexCoordinateReplacement(true);
        builder.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.MODULATE,
                           ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);
        builder.setVaryingColor(true);
        ProgramFragment pfs = builder.create();
        pfs.bindSampler(Sampler.WRAP_LINEAR_MIP_LINEAR(mRS), 0);
        mScript.set_gPFStars(pfs);

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
      Matrix4f projNorm = getProjectionNormalized(mBenchmarkDimX, mBenchmarkDimY);
      ScriptField_VpConsts.Item i = new ScriptField_VpConsts.Item();
      i.Proj = projNorm;
      i.MVP = projNorm;
      mPvStarAlloc.set(i, 0, true);
      mPvProjectionAlloc.setProjection(projNorm);
  }

    private void initProgramVertex() {
        ProgramVertexFixedFunction.Builder pvb = new ProgramVertexFixedFunction.Builder(mRS);
        mProgVertex = pvb.create();

        mPVA = new ProgramVertexFixedFunction.Constants(mRS);
        ((ProgramVertexFixedFunction)mProgVertex).bindConstants(mPVA);
        Matrix4f proj = new Matrix4f();
        proj.loadOrthoWindow(mBenchmarkDimX, mBenchmarkDimY);
        mPVA.setProjection(proj);

        mScript.set_gProgVertex(mProgVertex);


        // For galaxy live wallpaper
        mPvStarAlloc = new ScriptField_VpConsts(mRS, 1);
        mScript.bind_vpConstants(mPvStarAlloc);
        mPvProjectionAlloc = new ProgramVertexFixedFunction.Constants(mRS);
        updateProjectionMatrices();

        pvb = new ProgramVertexFixedFunction.Builder(mRS);
        ProgramVertex pvbp = pvb.create();
        ((ProgramVertexFixedFunction)pvbp).bindConstants(mPvProjectionAlloc);
        mScript.set_gPVBkProj(pvbp);

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
        mScript.set_gPVStars(pvs);
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
        mTexGlobe = loadTextureRGB(R.drawable.globe);

        mScript.set_gTexTorus(mTexTorus);
        mScript.set_gTexOpaque(mTexOpaque);
        mScript.set_gTexTransparent(mTexTransparent);
        mScript.set_gTexChecker(mTexChecker);
        mScript.set_gTexGlobe(mTexGlobe);

        // For Galaxy live wallpaper
        mScript.set_gTSpace(loadTextureRGB(R.drawable.space));
        mScript.set_gTLight1(loadTextureRGB(R.drawable.light1));
        mScript.set_gTFlares(loadTextureARGB(R.drawable.flares));
    }

    private void initFonts() {
        // Sans font by family name
        mFontSans = Font.create(mRS, mRes, "sans-serif", Font.Style.NORMAL, 8);
        mFontSerif = Font.create(mRS, mRes, "serif", Font.Style.NORMAL, 8);
        // Create fonts by family and style

        mTextAlloc = Allocation.createFromString(mRS, "String from allocation", Allocation.USAGE_SCRIPT);

        mScript.set_gFontSans(mFontSans);
        mScript.set_gFontSerif(mFontSerif);
    }

    private void createParticlesMesh() {
        ScriptField_Particle p = new ScriptField_Particle(mRS, PARTICLES_COUNT);

        final Mesh.AllocationBuilder meshBuilder = new Mesh.AllocationBuilder(mRS);
        meshBuilder.addVertexAllocation(p.getAllocation());
        final int vertexSlot = meshBuilder.getCurrentVertexTypeIndex();
        meshBuilder.addIndexSetType(Primitive.POINT);
        mParticlesMesh = meshBuilder.create();

        mScript.set_gParticlesMesh(mParticlesMesh);
        mScript.bind_Particles(p);
    }

    private void initMesh() {
        m10by10Mesh = getMbyNMesh(mBenchmarkDimX, mBenchmarkDimY, 10, 10);
        mScript.set_g10by10Mesh(m10by10Mesh);
        m100by100Mesh = getMbyNMesh(mBenchmarkDimX, mBenchmarkDimY, 100, 100);
        mScript.set_g100by100Mesh(m100by100Mesh);
        mWbyHMesh= getMbyNMesh(mBenchmarkDimX, mBenchmarkDimY, mBenchmarkDimX/4, mBenchmarkDimY/4);
        mScript.set_gWbyHMesh(mWbyHMesh);
        mSingleMesh = getSingleMesh(1, 1);  // a unit size mesh
        mScript.set_gSingleMesh(mSingleMesh);

        FileA3D model = FileA3D.createFromResource(mRS, mRes, R.raw.torus);
        FileA3D.IndexEntry entry = model.getIndexEntry(0);
        if (entry == null || entry.getEntryType() != FileA3D.EntryType.MESH) {
            Log.e("rs", "could not load model");
        } else {
            mTorus = (Mesh)entry.getObject();
        }

        createParticlesMesh();
    }

    private void initSamplers() {
        mScript.set_gLinearClamp(Sampler.CLAMP_LINEAR(mRS));
        mScript.set_gLinearWrap(Sampler.WRAP_LINEAR(mRS));
        mScript.set_gMipLinearWrap(Sampler.WRAP_LINEAR_MIP_LINEAR(mRS));
        mScript.set_gNearestClamp(Sampler.CLAMP_NEAREST(mRS));
    }

    private void initProgramRaster() {
        mScript.set_gCullBack(ProgramRaster.CULL_BACK(mRS));
        mScript.set_gCullFront(ProgramRaster.CULL_FRONT(mRS));
        mScript.set_gCullNone(ProgramRaster.CULL_NONE(mRS));
    }

    private int strlen(byte[] array) {
        int count = 0;
        while(count < array.length && array[count] != 0) {
            count ++;
        }
        return count;
    }

    private void prepareTestData() {
        mTestNames = new String[mMaxModes];
        mLocalTestResults = new float[mMaxModes];
        int scratchSize = 1024;
        Allocation scratch = Allocation.createSized(mRS, Element.U8(mRS), scratchSize);
        byte[] tmp = new byte[scratchSize];
        mScript.bind_gStringBuffer(scratch);
        for (int i = 0; i < mMaxModes; i ++) {
            mScript.invoke_getTestName(i);
            scratch.copyTo(tmp);
            int len = strlen(tmp);
            mTestNames[i] = new String(tmp, 0, len);
        }
    }

    public void setDebugMode(int num) {
        mScript.invoke_setDebugMode(num);
    }

    public void setBenchmarkMode() {
        mScript.invoke_setBenchmarkMode();
    }

    void initTextScript() {
        mTextScript = new ScriptC_text_test(mRS, mRes, R.raw.text_test);
        mTextScript.set_gFontSans(mFontSans);
        mTextScript.set_gFontSerif(mFontSerif);
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

    private void initRS() {

        mScript = new ScriptC_rsbench(mRS, mRes, R.raw.rsbench);


        mRS.setMessageHandler(mRsMessage);

        mMaxModes = mScript.get_gMaxModes();
        mScript.set_gMaxLoops(mLoops);

        prepareTestData();

        initSamplers();
        initMesh();
        initProgramVertex();
        initProgramStore();
        initProgramFragment();
        initFonts();
        loadImages();
        initProgramRaster();
        initCustomShaders();

        Type.Builder b = new Type.Builder(mRS, Element.RGBA_8888(mRS));
        b.setX(mBenchmarkDimX).setY(mBenchmarkDimY);
        Allocation offscreen = Allocation.createTyped(mRS,
                                                      b.create(),
                                                      Allocation.USAGE_GRAPHICS_TEXTURE |
                                                      Allocation.USAGE_GRAPHICS_RENDER_TARGET);
        mScript.set_gRenderBufferColor(offscreen);

        b = new Type.Builder(mRS,
                             Element.createPixel(mRS, DataType.UNSIGNED_16,
                             DataKind.PIXEL_DEPTH));
        b.setX(mBenchmarkDimX).setY(mBenchmarkDimY);
        offscreen = Allocation.createTyped(mRS,
                                           b.create(),
                                           Allocation.USAGE_GRAPHICS_RENDER_TARGET);
        mScript.set_gRenderBufferDepth(offscreen);

        mTextureAllocs = new ScriptField_ListAllocs_s(mRS, 100);
        for (int i = 0; i < 100; i++) {
            ScriptField_ListAllocs_s.Item texElem = new ScriptField_ListAllocs_s.Item();
            texElem.item = loadTextureRGB(R.drawable.globe);
            mTextureAllocs.set(texElem, i, false);
        }
        mTextureAllocs.copyAll();
        mScript.bind_gTexList100(mTextureAllocs);

        mSampleTextAllocs = new ScriptField_ListAllocs_s(mRS, 100);
        for (int i = 0; i < 100; i++) {
            ScriptField_ListAllocs_s.Item textElem = new ScriptField_ListAllocs_s.Item();
            textElem.item = Allocation.createFromString(mRS, SAMPLE_TEXT, Allocation.USAGE_SCRIPT);
            mSampleTextAllocs.set(textElem, i, false);
        }
        mSampleTextAllocs.copyAll();
        mScript.bind_gSampleTextList100(mSampleTextAllocs);

        mSampleListViewAllocs = new ScriptField_ListAllocs_s(mRS, 1000);
        for (int i = 0; i < 1000; i++) {
            ScriptField_ListAllocs_s.Item textElem = new ScriptField_ListAllocs_s.Item();
            textElem.item = Allocation.createFromString(mRS, LIST_TEXT, Allocation.USAGE_SCRIPT);
            mSampleListViewAllocs.set(textElem, i, false);
        }
        mSampleListViewAllocs.copyAll();
        mScript.bind_gListViewText(mSampleListViewAllocs);

        initTextScript();
        initTorusScript();

        mScript.set_gFontScript(mTextScript);
        mScript.set_gTorusScript(mTorusScript);
        mScript.set_gDummyAlloc(Allocation.createSized(mRS, Element.I32(mRS), 1));

        mRS.bindRootScript(mScript);
    }
}
