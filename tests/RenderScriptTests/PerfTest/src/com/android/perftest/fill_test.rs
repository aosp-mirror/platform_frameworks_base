// Copyright (C) 2011 The Android Open Source Project
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
#include "subtest_def.rsh"

rs_program_vertex gProgVertex;
rs_program_fragment gProgFragmentTexture;
rs_program_fragment gProgFragmentMultitex;

rs_program_store gProgStoreBlendNone;
rs_program_store gProgStoreBlendAlpha;

rs_allocation gTexOpaque;
rs_allocation gTexTorus;
rs_allocation gTexTransparent;
rs_allocation gTexChecker;

rs_sampler gLinearClamp;
rs_sampler gLinearWrap;

typedef struct FillTestData_s {
    int testId;
    int blend;
    int quadCount;
} FillTestData;
FillTestData *gData;

static float gDt = 0.0f;

void init() {
}

static int gRenderSurfaceW = 1280;
static int gRenderSurfaceH = 720;

static void bindProgramVertexOrtho() {
    // Default vertex shader
    rsgBindProgramVertex(gProgVertex);
    // Setup the projection matrix
    rs_matrix4x4 proj;
    rsMatrixLoadOrtho(&proj, 0, gRenderSurfaceW, gRenderSurfaceH, 0, -500, 500);
    rsgProgramVertexLoadProjectionMatrix(&proj);
}

static void displaySingletexFill(bool blend, int quadCount) {
    bindProgramVertexOrtho();
    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    rsgProgramVertexLoadModelMatrix(&matrix);

    // Fragment shader with texture
    if (!blend) {
        rsgBindProgramStore(gProgStoreBlendNone);
    } else {
        rsgBindProgramStore(gProgStoreBlendAlpha);
    }
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentTexture, 0, gTexOpaque);

    for (int i = 0; i < quadCount; i ++) {
        float startX = 5 * i, startY = 5 * i;
        float width = gRenderSurfaceW - startX, height = gRenderSurfaceH - startY;
        rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                             startX, startY + height, 0, 0, 1,
                             startX + width, startY + height, 0, 1, 1,
                             startX + width, startY, 0, 1, 0);
    }
}

static void displayMultitextureSample(bool blend, int quadCount) {
    bindProgramVertexOrtho();
    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    rsgProgramVertexLoadModelMatrix(&matrix);

    // Fragment shader with texture
    if (!blend) {
        rsgBindProgramStore(gProgStoreBlendNone);
    } else {
        rsgBindProgramStore(gProgStoreBlendAlpha);
    }
    rsgBindProgramFragment(gProgFragmentMultitex);
    rsgBindSampler(gProgFragmentMultitex, 0, gLinearClamp);
    rsgBindSampler(gProgFragmentMultitex, 1, gLinearWrap);
    rsgBindSampler(gProgFragmentMultitex, 2, gLinearClamp);
    rsgBindTexture(gProgFragmentMultitex, 0, gTexChecker);
    rsgBindTexture(gProgFragmentMultitex, 1, gTexTorus);
    rsgBindTexture(gProgFragmentMultitex, 2, gTexTransparent);

    for (int i = 0; i < quadCount; i ++) {
        float startX = 10 * i, startY = 10 * i;
        float width = gRenderSurfaceW - startX, height = gRenderSurfaceH - startY;
        rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                             startX, startY + height, 0, 0, 1,
                             startX + width, startY + height, 0, 1, 1,
                             startX + width, startY, 0, 1, 0);
    }
}


void root(const void *v_in, void *v_out, const void *usrData, uint32_t x, uint32_t y) {
    TestData *testData = (TestData*)usrData;
    gRenderSurfaceW = testData->renderSurfaceW;
    gRenderSurfaceH = testData->renderSurfaceH;
    gDt = testData->dt;

    gData = (FillTestData*)v_in;

    switch(gData->testId) {
        case 0:
            displayMultitextureSample(gData->blend == 1 ? true : false, gData->quadCount);
            break;
        case 1:
            displaySingletexFill(gData->blend == 1 ? true : false, gData->quadCount);
            break;
        default:
            rsDebug("Wrong test number", 0);
            break;
    }
}
