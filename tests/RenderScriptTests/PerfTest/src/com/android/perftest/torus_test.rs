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
#include "shader_def.rsh"

rs_program_vertex gProgVertex;
rs_program_fragment gProgFragmentColor;
rs_program_fragment gProgFragmentTexture;

rs_program_store gProgStoreBlendNoneDepth;
rs_mesh gTorusMesh;

rs_program_raster gCullBack;
rs_program_raster gCullFront;

// Custom vertex shader compunents
VertexShaderConstants *gVSConstants;
FragentShaderConstants *gFSConstants;
VertexShaderConstants3 *gVSConstPixel;
FragentShaderConstants3 *gFSConstPixel;

// Custom shaders we use for lighting
rs_program_vertex gProgVertexCustom;
rs_program_fragment gProgFragmentCustom;

rs_sampler gLinearClamp;
rs_allocation gTexTorus;

rs_program_vertex gProgVertexPixelLight;
rs_program_vertex gProgVertexPixelLightMove;
rs_program_fragment gProgFragmentPixelLight;

typedef struct TorusTestData_s {
    int testId;
    int user1;
    int user2;
} TorusTestData;
TorusTestData *gData;

static float gDt = 0.0f;

static int gRenderSurfaceW;
static int gRenderSurfaceH;


static float gTorusRotation = 0;
static void updateModelMatrix(rs_matrix4x4 *matrix, void *buffer) {
    if (buffer == 0) {
        rsgProgramVertexLoadModelMatrix(matrix);
    } else {
        rsgAllocationSyncAll(rsGetAllocation(buffer));
    }
}

static void drawToruses(int numMeshes, rs_matrix4x4 *matrix, void *buffer) {

    if (numMeshes == 1) {
        rsMatrixLoadTranslate(matrix, 0.0f, 0.0f, -7.5f);
        rsMatrixRotate(matrix, gTorusRotation, 1.0f, 0.0f, 0.0f);
        updateModelMatrix(matrix, buffer);
        rsgDrawMesh(gTorusMesh);
        return;
    }

    if (numMeshes == 2) {
        rsMatrixLoadTranslate(matrix, -1.6f, 0.0f, -7.5f);
        rsMatrixRotate(matrix, gTorusRotation, 1.0f, 0.0f, 0.0f);
        updateModelMatrix(matrix, buffer);
        rsgDrawMesh(gTorusMesh);

        rsMatrixLoadTranslate(matrix, 1.6f, 0.0f, -7.5f);
        rsMatrixRotate(matrix, gTorusRotation, 1.0f, 0.0f, 0.0f);
        updateModelMatrix(matrix, buffer);
        rsgDrawMesh(gTorusMesh);
        return;
    }

    float startX = -5.0f;
    float startY = -1.5f;
    float startZ = -15.0f;
    float dist = 3.2f;

    for (int h = 0; h < 4; h ++) {
        for (int v = 0; v < 2; v ++) {
            // Position our model on the screen
            rsMatrixLoadTranslate(matrix, startX + dist * h, startY + dist * v, startZ);
            rsMatrixRotate(matrix, gTorusRotation, 1.0f, 0.0f, 0.0f);
            updateModelMatrix(matrix, buffer);
            rsgDrawMesh(gTorusMesh);
        }
    }
}


// Quick hack to get some geometry numbers
static void displaySimpleGeoSamples(bool useTexture, int numMeshes) {
    rsgBindProgramVertex(gProgVertex);
    rsgBindProgramRaster(gCullBack);
    // Setup the projection matrix with 30 degree field of view
    rs_matrix4x4 proj;
    float aspect = (float)gRenderSurfaceW / (float)gRenderSurfaceH;
    rsMatrixLoadPerspective(&proj, 30.0f, aspect, 0.1f, 100.0f);
    rsgProgramVertexLoadProjectionMatrix(&proj);

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNoneDepth);
    if (useTexture) {
        rsgBindProgramFragment(gProgFragmentTexture);
    } else {
        rsgBindProgramFragment(gProgFragmentColor);
        rsgProgramFragmentConstantColor(gProgFragmentColor, 0.1, 0.7, 0.1, 1);
    }
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentTexture, 0, gTexTorus);

    // Apply a rotation to our mesh
    gTorusRotation += 50.0f * gDt;
    if (gTorusRotation > 360.0f) {
        gTorusRotation -= 360.0f;
    }

    rs_matrix4x4 matrix;
    drawToruses(numMeshes, &matrix, 0);
}

float gLight0Rotation = 0;
float gLight1Rotation = 0;

static void setupCustomShaderLights() {
    float4 light0Pos = {-5.0f, 5.0f, -10.0f, 1.0f};
    float4 light1Pos = {2.0f, 5.0f, 15.0f, 1.0f};
    float4 light0DiffCol = {0.9f, 0.7f, 0.7f, 1.0f};
    float4 light0SpecCol = {0.9f, 0.6f, 0.6f, 1.0f};
    float4 light1DiffCol = {0.5f, 0.5f, 0.9f, 1.0f};
    float4 light1SpecCol = {0.5f, 0.5f, 0.9f, 1.0f};

    gLight0Rotation += 50.0f * gDt;
    if (gLight0Rotation > 360.0f) {
        gLight0Rotation -= 360.0f;
    }
    gLight1Rotation -= 50.0f * gDt;
    if (gLight1Rotation > 360.0f) {
        gLight1Rotation -= 360.0f;
    }

    rs_matrix4x4 l0Mat;
    rsMatrixLoadRotate(&l0Mat, gLight0Rotation, 1.0f, 0.0f, 0.0f);
    light0Pos = rsMatrixMultiply(&l0Mat, light0Pos);
    rs_matrix4x4 l1Mat;
    rsMatrixLoadRotate(&l1Mat, gLight1Rotation, 0.0f, 0.0f, 1.0f);
    light1Pos = rsMatrixMultiply(&l1Mat, light1Pos);

    // Set light 0 properties
    gVSConstants->light0_Posision = light0Pos;
    gVSConstants->light0_Diffuse = 1.0f;
    gVSConstants->light0_Specular = 0.5f;
    gVSConstants->light0_CosinePower = 10.0f;
    // Set light 1 properties
    gVSConstants->light1_Posision = light1Pos;
    gVSConstants->light1_Diffuse = 1.0f;
    gVSConstants->light1_Specular = 0.7f;
    gVSConstants->light1_CosinePower = 25.0f;
    rsgAllocationSyncAll(rsGetAllocation(gVSConstants));

    // Update fragment shader constants
    // Set light 0 colors
    gFSConstants->light0_DiffuseColor = light0DiffCol;
    gFSConstants->light0_SpecularColor = light0SpecCol;
    // Set light 1 colors
    gFSConstants->light1_DiffuseColor = light1DiffCol;
    gFSConstants->light1_SpecularColor = light1SpecCol;
    rsgAllocationSyncAll(rsGetAllocation(gFSConstants));

    // Set light 0 properties for per pixel lighting
    gFSConstPixel->light0_Posision = light0Pos;
    gFSConstPixel->light0_Diffuse = 1.0f;
    gFSConstPixel->light0_Specular = 0.5f;
    gFSConstPixel->light0_CosinePower = 10.0f;
    gFSConstPixel->light0_DiffuseColor = light0DiffCol;
    gFSConstPixel->light0_SpecularColor = light0SpecCol;
    // Set light 1 properties
    gFSConstPixel->light1_Posision = light1Pos;
    gFSConstPixel->light1_Diffuse = 1.0f;
    gFSConstPixel->light1_Specular = 0.7f;
    gFSConstPixel->light1_CosinePower = 25.0f;
    gFSConstPixel->light1_DiffuseColor = light1DiffCol;
    gFSConstPixel->light1_SpecularColor = light1SpecCol;
    rsgAllocationSyncAll(rsGetAllocation(gFSConstPixel));
}

static void displayCustomShaderSamples(int numMeshes) {

    // Update vertex shader constants
    // Load model matrix
    // Apply a rotation to our mesh
    gTorusRotation += 50.0f * gDt;
    if (gTorusRotation > 360.0f) {
        gTorusRotation -= 360.0f;
    }

    // Setup the projection matrix
    float aspect = (float)gRenderSurfaceW / (float)gRenderSurfaceH;
    rsMatrixLoadPerspective(&gVSConstants->proj, 30.0f, aspect, 0.1f, 100.0f);
    setupCustomShaderLights();

    rsgBindProgramVertex(gProgVertexCustom);

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNoneDepth);
    rsgBindProgramFragment(gProgFragmentCustom);
    rsgBindSampler(gProgFragmentCustom, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentCustom, 0, gTexTorus);

    // Use back face culling
    rsgBindProgramRaster(gCullBack);

    drawToruses(numMeshes, &gVSConstants->model, gVSConstants);
}

static void displayPixelLightSamples(int numMeshes, bool heavyVertex) {

    // Update vertex shader constants
    // Load model matrix
    // Apply a rotation to our mesh
    gTorusRotation += 30.0f * gDt;
    if (gTorusRotation > 360.0f) {
        gTorusRotation -= 360.0f;
    }

    gVSConstPixel->time = rsUptimeMillis()*0.005;

    // Setup the projection matrix
    float aspect = (float)gRenderSurfaceW / (float)gRenderSurfaceH;
    rsMatrixLoadPerspective(&gVSConstPixel->proj, 30.0f, aspect, 0.1f, 100.0f);
    setupCustomShaderLights();

    if (heavyVertex) {
        rsgBindProgramVertex(gProgVertexPixelLightMove);
    } else {
        rsgBindProgramVertex(gProgVertexPixelLight);
    }

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNoneDepth);
    rsgBindProgramFragment(gProgFragmentPixelLight);
    rsgBindSampler(gProgFragmentPixelLight, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentPixelLight, 0, gTexTorus);

    // Use back face culling
    rsgBindProgramRaster(gCullBack);

    drawToruses(numMeshes, &gVSConstPixel->model, gVSConstPixel);
}


void root(const void *v_in, void *v_out, const void *usrData, uint32_t x, uint32_t y) {
    TestData *testData = (TestData*)usrData;
    gRenderSurfaceW = testData->renderSurfaceW;
    gRenderSurfaceH = testData->renderSurfaceH;
    gDt = testData->dt;

    gData = (TorusTestData*)v_in;

    switch(gData->testId) {
        case 0:
            displaySimpleGeoSamples(gData->user1 == 1 ? true : false, gData->user2);
            break;
        case 1:
            displayCustomShaderSamples(gData->user1);
            break;
        case 2:
            displayPixelLightSamples(gData->user1, gData->user2 == 1 ? true : false);
            break;
        default:
            rsDebug("Wrong test number", gData->testId);
            break;
    }
}
