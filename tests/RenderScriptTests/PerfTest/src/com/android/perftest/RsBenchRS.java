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
import android.util.Log;


public class RsBenchRS {

    private static final String TAG = "RsBenchRS";

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
    private ProgramVertexFixedFunction.Constants mPVA;

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

    private ProgramRaster mCullBack;
    private ProgramRaster mCullFront;
    private ProgramRaster mCullNone;

    private Allocation mTexTorus;
    private Allocation mTexOpaque;
    private Allocation mTexTransparent;
    private Allocation mTexChecker;

    private Mesh m10by10Mesh;
    private Mesh m100by100Mesh;
    private Mesh mWbyHMesh;
    private Mesh mTorus;

    Font mFontSans;
    Font mFontSerif;
    private Allocation mTextAlloc;

    private ScriptC_rsbench mScript;

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
        proj.loadOrthoWindow(mBenchmarkDimX, mBenchmarkDimY);
        mPVA.setProjection(proj);

        mScript.set_gProgVertex(mProgVertex);
    }

    private void initCustomShaders() {
        mVSConst = new ScriptField_VertexShaderConstants_s(mRS, 1);
        mFSConst = new ScriptField_FragentShaderConstants_s(mRS, 1);
        mScript.bind_gVSConstants(mVSConst);
        mScript.bind_gFSConstants(mFSConst);

        mVSConstPixel = new ScriptField_VertexShaderConstants3_s(mRS, 1);
        mFSConstPixel = new ScriptField_FragentShaderConstants3_s(mRS, 1);
        mScript.bind_gVSConstPixel(mVSConstPixel);
        mScript.bind_gFSConstPixel(mFSConstPixel);

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

        mScript.set_gProgVertexCustom(mProgVertexCustom);
        mScript.set_gProgFragmentCustom(mProgFragmentCustom);
        mScript.set_gProgVertexPixelLight(mProgVertexPixelLight);
        mScript.set_gProgVertexPixelLightMove(mProgVertexPixelLightMove);
        mScript.set_gProgFragmentPixelLight(mProgFragmentPixelLight);
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

        mScript.set_gTexTorus(mTexTorus);
        mScript.set_gTexOpaque(mTexOpaque);
        mScript.set_gTexTransparent(mTexTransparent);
        mScript.set_gTexChecker(mTexChecker);
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

    private void initMesh() {
        m10by10Mesh = getMbyNMesh(mBenchmarkDimX, mBenchmarkDimY, 10, 10);
        mScript.set_g10by10Mesh(m10by10Mesh);
        m100by100Mesh = getMbyNMesh(mBenchmarkDimX, mBenchmarkDimY, 100, 100);
        mScript.set_g100by100Mesh(m100by100Mesh);
        mWbyHMesh= getMbyNMesh(mBenchmarkDimX, mBenchmarkDimY, mBenchmarkDimX/4, mBenchmarkDimY/4);
        mScript.set_gWbyHMesh(mWbyHMesh);

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

        mScript.set_gLinearClamp(mLinearClamp);
        mScript.set_gLinearWrap(mLinearWrap);
        mScript.set_gMipLinearWrap(mMipLinearWrap);
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

    private void initRS() {

        mScript = new ScriptC_rsbench(mRS, mRes, R.raw.rsbench);
        mRS.setMessageHandler(mRsMessage);

        mMaxModes = mScript.get_gMaxModes();
        mScript.set_gMaxLoops(mLoops);

        prepareTestData();

        initSamplers();
        initProgramStore();
        initProgramFragment();
        initProgramVertex();
        initFonts();
        loadImages();
        initMesh();
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

        mRS.bindRootScript(mScript);
    }
}
