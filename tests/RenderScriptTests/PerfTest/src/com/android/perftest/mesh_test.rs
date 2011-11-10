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
#include "shader_def.rsh"
#include "subtest_def.rsh"

rs_program_vertex gProgVertex;
rs_program_fragment gProgFragmentTexture;

rs_program_store gProgStoreBlendNone;

rs_allocation gTexOpaque;

rs_mesh g10by10Mesh;
rs_mesh g100by100Mesh;
rs_mesh gWbyHMesh;

rs_sampler gLinearClamp;
static int gRenderSurfaceW;
static int gRenderSurfaceH;

static float gDt = 0;

typedef struct MeshTestData_s {
    int meshNum;
} MeshTestData;
MeshTestData *gData;

void init() {
}

static void bindProgramVertexOrtho() {
    // Default vertex shader
    rsgBindProgramVertex(gProgVertex);
    // Setup the projection matrix
    rs_matrix4x4 proj;
    rsMatrixLoadOrtho(&proj, 0, gRenderSurfaceW, gRenderSurfaceH, 0, -500, 500);
    rsgProgramVertexLoadProjectionMatrix(&proj);
}

static void displayMeshSamples(int meshNum) {

    bindProgramVertexOrtho();
    rs_matrix4x4 matrix;
    rsMatrixLoadTranslate(&matrix, gRenderSurfaceW/2, gRenderSurfaceH/2, 0);
    rsgProgramVertexLoadModelMatrix(&matrix);

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNone);
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);

    rsgBindTexture(gProgFragmentTexture, 0, gTexOpaque);

    if (meshNum == 0) {
        rsgDrawMesh(g10by10Mesh);
    } else if (meshNum == 1) {
        rsgDrawMesh(g100by100Mesh);
    } else if (meshNum == 2) {
        rsgDrawMesh(gWbyHMesh);
    }
}

void root(const void *v_in, void *v_out, const void *usrData, uint32_t x, uint32_t y) {
    TestData *testData = (TestData*)usrData;
    gRenderSurfaceW = testData->renderSurfaceW;
    gRenderSurfaceH = testData->renderSurfaceH;
    gDt = testData->dt;

    gData = (MeshTestData*)v_in;

    displayMeshSamples(gData->meshNum);
}
