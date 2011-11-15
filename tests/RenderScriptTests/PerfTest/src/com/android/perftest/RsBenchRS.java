/*
 * Copyright (C) 2010-2011 The Android Open Source Project
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
import android.renderscript.*;
import android.renderscript.Element.DataKind;
import android.renderscript.Element.DataType;
import android.renderscript.Allocation.MipmapControl;
import android.renderscript.Program.TextureType;
import android.renderscript.RenderScript.RSMessageHandler;
import android.renderscript.Sampler.Value;
import android.renderscript.Mesh.Primitive;
import android.renderscript.Matrix4f;
import android.renderscript.ProgramVertexFixedFunction;

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

    private ProgramStore mProgStoreBlendNone;
    private ProgramStore mProgStoreBlendAlpha;

    private ProgramFragment mProgFragmentTexture;
    private ProgramFragment mProgFragmentColor;

    private ProgramVertex mProgVertex;
    private ProgramVertexFixedFunction.Constants mPVA;
    private ProgramVertexFixedFunction.Constants mPvProjectionAlloc;

    private ScriptC_rsbench mScript;

    ScriptField_TestScripts_s.Item[] mIndividualTests;

    int mMode;
    int mMaxModes;

    String[] mTestNames;
    float[] mLocalTestResults;

    void appendTests(RsBenchBaseTest testSet) {
        ScriptField_TestScripts_s.Item[] newTests = testSet.getTests();
        if (mIndividualTests != null) {
            ScriptField_TestScripts_s.Item[] combined;
            combined = new ScriptField_TestScripts_s.Item[newTests.length + mIndividualTests.length];
            System.arraycopy(mIndividualTests, 0, combined, 0, mIndividualTests.length);
            System.arraycopy(newTests, 0, combined, mIndividualTests.length, newTests.length);
            mIndividualTests = combined;
        } else {
            mIndividualTests = newTests;
        }

        String[] newNames = testSet.getTestNames();
        if (mTestNames != null) {
            String[] combinedNames;
            combinedNames = new String[newNames.length + mTestNames.length];
            System.arraycopy(mTestNames, 0, combinedNames, 0, mTestNames.length);
            System.arraycopy(newNames, 0, combinedNames, mTestNames.length, newNames.length);
            mTestNames = combinedNames;
        } else {
            mTestNames = newNames;
        }
    }

    void createTestAllocation() {
        int numTests = mIndividualTests.length;
        ScriptField_TestScripts_s allTests;
        allTests = new ScriptField_TestScripts_s(mRS, numTests);
        for (int i = 0; i < numTests; i ++) {
            allTests.set(mIndividualTests[i], i, false);
        }
        allTests.copyAll();
        mScript.bind_gTestScripts(allTests);
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

    private void initProgramFragment() {

        ProgramFragmentFixedFunction.Builder texBuilder = new ProgramFragmentFixedFunction.Builder(mRS);
        texBuilder.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.REPLACE,
                              ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);
        mProgFragmentTexture = texBuilder.create();
        mProgFragmentTexture.bindSampler(Sampler.CLAMP_LINEAR(mRS), 0);

        ProgramFragmentFixedFunction.Builder colBuilder = new ProgramFragmentFixedFunction.Builder(mRS);
        colBuilder.setVaryingColor(false);
        mProgFragmentColor = colBuilder.create();

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
    }

    public void setDebugMode(int num) {
        mScript.invoke_setDebugMode(num);
    }

    public void setBenchmarkMode() {
        mScript.invoke_setBenchmarkMode();
    }

    private void initRS() {

        mScript = new ScriptC_rsbench(mRS, mRes, R.raw.rsbench);
        mRS.bindRootScript(mScript);

        mRS.setMessageHandler(mRsMessage);

        mScript.set_gMaxLoops(mLoops);

        prepareTestData();

        initProgramVertex();
        initProgramFragment();
        mScript.set_gFontSerif(Font.create(mRS, mRes, "serif", Font.Style.NORMAL, 8));

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
        mScript.set_gLinearClamp(Sampler.CLAMP_LINEAR(mRS));

        RsBenchBaseTest test = new TextTest();
        if (test.init(mRS, mRes)) {
            appendTests(test);
        }
        test = new FillTest();
        if (test.init(mRS, mRes)) {
            appendTests(test);
        }
        test = new MeshTest();
        if (test.init(mRS, mRes)) {
            appendTests(test);
        }
        test = new TorusTest();
        if (test.init(mRS, mRes)) {
            appendTests(test);
        }
        test = new UiTest();
        if (test.init(mRS, mRes)) {
            appendTests(test);
        }
        createTestAllocation();

        mScript.set_gLoadComplete(true);
    }
}
