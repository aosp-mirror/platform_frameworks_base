// Copyright (C) 2010-2011 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma version(1)

#pragma rs java_package_name(com.android.perftest)

#include "rs_graphics.rsh"
#include "shader_def.rsh"
#include "subtest_def.rsh"

/* Message sent from script to renderscript */
const int RS_MSG_TEST_DONE = 100;
const int RS_MSG_RESULTS_READY = 101;

static const int gMaxModes = 64;
int gMaxLoops = 1;
int gDisplayMode = 1;

// Allocation to write the results into
static float gResultBuffer[gMaxModes];

rs_font gFontSerif;
rs_sampler gLinearClamp;

rs_program_vertex gProgVertex;
rs_program_fragment gProgFragmentTexture;

rs_allocation gRenderBufferColor;
rs_allocation gRenderBufferDepth;

VertexShaderInputs *gVSInputs;

typedef struct TestScripts_s {
    rs_allocation testData;
    rs_allocation testName;
    rs_script testScript;
} TestScripts;
TestScripts *gTestScripts;

bool gLoadComplete = false;

static float gDt = 0;

void init() {
}

static int gRenderSurfaceW;
static int gRenderSurfaceH;

static void fillSurfaceParams(TestData *testData) {
    testData->renderSurfaceW = gRenderSurfaceW;
    testData->renderSurfaceH = gRenderSurfaceH;
    testData->dt = gDt;
}

static void setupOffscreenTarget() {
    rsgBindColorTarget(gRenderBufferColor, 0);
    rsgBindDepthTarget(gRenderBufferDepth);
}

static void bindProgramVertexOrtho() {
    // Default vertex shader
    rsgBindProgramVertex(gProgVertex);
    // Setup the projection matrix
    rs_matrix4x4 proj;
    rsMatrixLoadOrtho(&proj, 0, gRenderSurfaceW, gRenderSurfaceH, 0, -500, 500);
    rsgProgramVertexLoadProjectionMatrix(&proj);
}

static void runSubTest(int index) {
    TestData testData;
    fillSurfaceParams(&testData);

    rs_allocation null_alloc;
    rsForEach(gTestScripts[index].testScript,
              gTestScripts[index].testData,
              null_alloc,
              &testData,
              sizeof(testData));
}


static bool checkInit() {

    static int countdown = 3;

    // Perform all the uploads so we only measure rendered time
    if(countdown > 1) {
        int testCount = rsAllocationGetDimX(rsGetAllocation(gTestScripts));
        for(int i = 0; i < testCount; i ++) {
            rsgClearColor(0.2f, 0.2f, 0.2f, 0.0f);
            runSubTest(i);
            rsgFinish();
        }
        countdown --;
        rsgClearColor(0.2f, 0.2f, 0.2f, 0.0f);

        rsgFontColor(0.9f, 0.9f, 0.95f, 1.0f);
        rsgBindFont(gFontSerif);
        if (countdown == 1) {
            rsgDrawText("Rendering", 50, 50);
        } else {
            rsgDrawText("Initializing", 50, 50);
        }

        return false;
    }

    return true;
}

static int benchMode = 0;
static int benchSubMode = 0;
static int runningLoops = 0;
static bool sendMsgFlag = false;

static bool gIsDebugMode = false;
void setDebugMode(int testNumber) {
    gIsDebugMode = true;
    benchMode = testNumber;
    rsgClearAllRenderTargets();
}

void setBenchmarkMode() {
    gIsDebugMode = false;
    benchMode = 0;
    runningLoops = 0;
}

static void drawOffscreenResult(int posX, int posY, int width, int height) {
    bindProgramVertexOrtho();

    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    rsgProgramVertexLoadModelMatrix(&matrix);

    rsgBindProgramFragment(gProgFragmentTexture);

    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentTexture, 0, gRenderBufferColor);

    float startX = posX, startY = posY;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 1,
                         startX, startY + height, 0, 0, 0,
                         startX + width, startY + height, 0, 1, 0,
                         startX + width, startY, 0, 1, 1);
}

static void benchmark() {

    gDt = 1.0f / 60.0f;

    rsgFinish();
    int64_t start = rsUptimeMillis();

    int drawPos = 0;
    int frameCount = 100;
    for(int i = 0; i < frameCount; i ++) {
        setupOffscreenTarget();
        gRenderSurfaceW = rsAllocationGetDimX(gRenderBufferColor);
        gRenderSurfaceH = rsAllocationGetDimY(gRenderBufferColor);
        rsgClearColor(0.1f, 0.1f, 0.1f, 1.0f);
        rsgClearDepth(1.0f);

        runSubTest(benchMode);
        rsgClearAllRenderTargets();
        gRenderSurfaceW = rsgGetWidth();
        gRenderSurfaceH = rsgGetHeight();
        int size = 8;
        // draw each frame at (8, 3/4 gRenderSurfaceH) with size
        drawOffscreenResult((drawPos+=size)%gRenderSurfaceW, (gRenderSurfaceH * 3) / 4, size, size);
    }

    rsgFinish();

    int64_t end = rsUptimeMillis();
    float fps = (float)(frameCount) / ((float)(end - start)*0.001f);
    rsDebug("Finishes test ", fps);

    gResultBuffer[benchMode] = fps;
    drawOffscreenResult(0, 0,
                        gRenderSurfaceW / 2,
                        gRenderSurfaceH / 2);
    int left = 0, right = 0, top = 0, bottom = 0;
    uint width = rsgGetWidth();
    uint height = rsgGetHeight();
    rsgFontColor(0.9f, 0.9f, 0.95f, 1.0f);
    rsgBindFont(gFontSerif);
    rsgMeasureText(gTestScripts[benchMode].testName, &left, &right, &top, &bottom);
    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgDrawText(gTestScripts[benchMode].testName, 2 -left, height - 2 + bottom);

    benchMode ++;
    int testCount = rsAllocationGetDimX(rsGetAllocation(gTestScripts));
    if (benchMode == testCount) {
        rsSendToClientBlocking(RS_MSG_RESULTS_READY, gResultBuffer, testCount*sizeof(float));
        benchMode = 0;
        runningLoops++;
        if ((gMaxLoops > 0) && (runningLoops > gMaxLoops) && !sendMsgFlag) {
            //Notifiy the test to stop and get results
            rsDebug("gMaxLoops and runningLoops: ", gMaxLoops, runningLoops);
            rsSendToClientBlocking(RS_MSG_TEST_DONE);
            sendMsgFlag = true;
        }
    }
}

static void debug() {
    gDt = rsGetDt();
    runSubTest(benchMode);
}

int root(void) {
    gRenderSurfaceW = rsgGetWidth();
    gRenderSurfaceH = rsgGetHeight();
    rsgClearColor(0.2f, 0.2f, 0.2f, 1.0f);
    rsgClearDepth(1.0f);

    if (!gLoadComplete) {
        rsgFontColor(0.9f, 0.9f, 0.95f, 1.0f);
        rsgBindFont(gFontSerif);
        rsgDrawText("Loading", 50, 50);
        return 0;
    }

    if(!checkInit()) {
        return 1;
    }

    if (gIsDebugMode) {
        debug();
    } else {
        benchmark();
    }

    return 1;
}
