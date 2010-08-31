// Copyright (C) 2009 The Android Open Source Project
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

#pragma rs java_package_name(com.android.samples)

#include "rs_graphics.rsh"
#include "shader_def.rsh"

rs_program_vertex gProgVertex;
rs_program_fragment gProgFragmentColor;
rs_program_fragment gProgFragmentTexture;

rs_program_store gProgStoreBlendNoneDepth;
rs_program_store gProgStoreBlendNone;
rs_program_store gProgStoreBlendAlpha;
rs_program_store gProgStoreBlendAdd;

rs_allocation gTexOpaque;
rs_allocation gTexTorus;
rs_allocation gTexTransparent;

rs_mesh gMbyNMesh;
rs_mesh gTorusMesh;

rs_font gFontSans;
rs_font gFontSerif;
rs_font gFontSerifBold;
rs_font gFontSerifItalic;
rs_font gFontSerifBoldItalic;
rs_font gFontMono;

int gDisplayMode;

rs_sampler gLinearClamp;
rs_sampler gLinearWrap;
rs_sampler gMipLinearWrap;
rs_sampler gNearestClamp;

rs_program_raster gCullBack;
rs_program_raster gCullFront;

// Custom vertex shader compunents
VertexShaderConstants *gVSConstants;
FragentShaderConstants *gFSConstants;
// Export these out to easily set the inputs to shader
VertexShaderInputs *gVSInputs;
// Custom shaders we use for lighting
rs_program_vertex gProgVertexCustom;
rs_program_fragment gProgFragmentCustom;

#pragma rs export_var(gProgVertex, gProgFragmentColor, gProgFragmentTexture)
#pragma rs export_var(gProgStoreBlendNoneDepth, gProgStoreBlendNone, gProgStoreBlendAlpha, gProgStoreBlendAdd)
#pragma rs export_var(gTexOpaque, gTexTorus, gTexTransparent)
#pragma rs export_var(gMbyNMesh, gTorusMesh)
#pragma rs export_var(gFontSans, gFontSerif, gFontSerifBold, gFontSerifItalic, gFontSerifBoldItalic, gFontMono)
#pragma rs export_var(gLinearClamp, gLinearWrap, gMipLinearWrap, gNearestClamp)
#pragma rs export_var(gCullBack, gCullFront)
#pragma rs export_var(gVSConstants, gFSConstants, gVSInputs, gProgVertexCustom, gProgFragmentCustom)

//What we are showing
#pragma rs export_var(gDisplayMode)

void init() {
}

void displayFontSamples() {
    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    int yPos = 30;
    rsgBindFont(gFontSans);
    rsgDrawText("Sans font sample", 30, yPos);
    yPos += 30;
    rsgFontColor(0.5f, 0.9f, 0.5f, 1.0f);
    rsgBindFont(gFontSerif);
    rsgDrawText("Serif font sample", 30, yPos);
    yPos += 30;
    rsgFontColor(0.7f, 0.7f, 0.7f, 1.0f);
    rsgBindFont(gFontSerifBold);
    rsgDrawText("Serif Bold font sample", 30, yPos);
    yPos += 30;
    rsgFontColor(0.5f, 0.5f, 0.9f, 1.0f);
    rsgBindFont(gFontSerifItalic);
    rsgDrawText("Serif Italic font sample", 30, yPos);
    yPos += 30;
    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgBindFont(gFontSerifBoldItalic);
    rsgDrawText("Serif Bold Italic font sample", 30, yPos);
    yPos += 30;
    rsgBindFont(gFontMono);
    rsgDrawText("Monospace font sample", 30, yPos);
}

void bindProgramVertexOrtho() {
    // Default vertex sahder
    rsgBindProgramVertex(gProgVertex);
    // Setup the projectioni matrix
    rs_matrix4x4 proj;
    rsMatrixLoadOrtho(&proj, 0, rsgGetWidth(), rsgGetHeight(), 0, -1,1);
    rsgProgramVertexLoadProjectionMatrix(&proj);
}

void displayShaderSamples() {
    bindProgramVertexOrtho();
    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    rsgProgramVertexLoadModelMatrix(&matrix);

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNone);
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentTexture, 0, gTexOpaque);

    float startX = 0, startY = 0;
    float width = 256, height = 256;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                         startX, startY + height, 0, 0, 1,
                         startX + width, startY + height, 0, 1, 1,
                         startX + width, startY, 0, 1, 0);

    startX = 200; startY = 0;
    width = 128; height = 128;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                         startX, startY + height, 0, 0, 1,
                         startX + width, startY + height, 0, 1, 1,
                         startX + width, startY, 0, 1, 0);

    rsgBindProgramStore(gProgStoreBlendAlpha);
    rsgBindTexture(gProgFragmentTexture, 0, gTexTransparent);
    startX = 0; startY = 200;
    width = 128; height = 128;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                         startX, startY + height, 0, 0, 1,
                         startX + width, startY + height, 0, 1, 1,
                         startX + width, startY, 0, 1, 0);

    // Fragment program with simple color
    rsgBindProgramFragment(gProgFragmentColor);
    rsgProgramFragmentConstantColor(gProgFragmentColor, 0.9, 0.3, 0.3, 1);
    rsgDrawRect(200, 300, 350, 450, 0);
    rsgProgramFragmentConstantColor(gProgFragmentColor, 0.3, 0.9, 0.3, 1);
    rsgDrawRect(50, 400, 400, 600, 0);

    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgBindFont(gFontMono);
    rsgDrawText("Texture shader", 10, 50);
    rsgDrawText("Alpha-blended texture shader", 10, 280);
    rsgDrawText("Flat color shader", 100, 450);
}

void displayBlendingSamples() {
    int i;

    bindProgramVertexOrtho();
    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    rsgProgramVertexLoadModelMatrix(&matrix);

    rsgBindProgramFragment(gProgFragmentColor);

    rsgBindProgramStore(gProgStoreBlendNone);
    for(i = 0; i < 3; i ++) {
        float iPlusOne = (float)(i + 1);
        rsgProgramFragmentConstantColor(gProgFragmentColor,
                                        0.1f*iPlusOne, 0.2f*iPlusOne, 0.3f*iPlusOne, 1);
        float yPos = 150 * (float)i;
        rsgDrawRect(0, yPos, 200, yPos + 200, 0);
    }

    rsgBindProgramStore(gProgStoreBlendAlpha);
    for(i = 0; i < 3; i ++) {
        float iPlusOne = (float)(i + 1);
        rsgProgramFragmentConstantColor(gProgFragmentColor,
                                        0.2f*iPlusOne, 0.3f*iPlusOne, 0.1f*iPlusOne, 0.5);
        float yPos = 150 * (float)i;
        rsgDrawRect(150, yPos, 350, yPos + 200, 0);
    }

    rsgBindProgramStore(gProgStoreBlendAdd);
    for(i = 0; i < 3; i ++) {
        float iPlusOne = (float)(i + 1);
        rsgProgramFragmentConstantColor(gProgFragmentColor,
                                        0.3f*iPlusOne, 0.1f*iPlusOne, 0.2f*iPlusOne, 0.5);
        float yPos = 150 * (float)i;
        rsgDrawRect(300, yPos, 500, yPos + 200, 0);
    }


    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgBindFont(gFontMono);
    rsgDrawText("No Blending", 10, 50);
    rsgDrawText("Alpha Blending", 160, 150);
    rsgDrawText("Additive Blending", 320, 250);

}

void displayMeshSamples() {

    bindProgramVertexOrtho();
    rs_matrix4x4 matrix;
    rsMatrixLoadTranslate(&matrix, 128, 128, 0);
    rsgProgramVertexLoadModelMatrix(&matrix);

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNone);
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentTexture, 0, gTexOpaque);

    rsgDrawMesh(gMbyNMesh);

    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgBindFont(gFontMono);
    rsgDrawText("User gen 10 by 10 grid mesh", 10, 250);
}

void displayTextureSamplers() {

    bindProgramVertexOrtho();
    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    rsgProgramVertexLoadModelMatrix(&matrix);

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNone);
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindTexture(gProgFragmentTexture, 0, gTexOpaque);

    // Linear clamp
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);
    float startX = 0, startY = 0;
    float width = 300, height = 300;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                         startX, startY + height, 0, 0, 1.1,
                         startX + width, startY + height, 0, 1.1, 1.1,
                         startX + width, startY, 0, 1.1, 0);

    // Linear Wrap
    rsgBindSampler(gProgFragmentTexture, 0, gLinearWrap);
    startX = 0; startY = 300;
    width = 300; height = 300;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                         startX, startY + height, 0, 0, 1.1,
                         startX + width, startY + height, 0, 1.1, 1.1,
                         startX + width, startY, 0, 1.1, 0);

    // Nearest
    rsgBindSampler(gProgFragmentTexture, 0, gNearestClamp);
    startX = 300; startY = 0;
    width = 300; height = 300;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                         startX, startY + height, 0, 0, 1.1,
                         startX + width, startY + height, 0, 1.1, 1.1,
                         startX + width, startY, 0, 1.1, 0);

    rsgBindSampler(gProgFragmentTexture, 0, gMipLinearWrap);
    startX = 300; startY = 300;
    width = 300; height = 300;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                         startX, startY + height, 0, 0, 1.5,
                         startX + width, startY + height, 0, 1.5, 1.5,
                         startX + width, startY, 0, 1.5, 0);


    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgBindFont(gFontMono);
    rsgDrawText("Filtering: linear clamp", 10, 290);
    rsgDrawText("Filtering: linear wrap", 10, 590);
    rsgDrawText("Filtering: nearest clamp", 310, 290);
    rsgDrawText("Filtering: miplinear wrap", 310, 590);

}

float gTorusRotation = 0;

void displayCullingSamples() {
    rsgBindProgramVertex(gProgVertex);
    // Setup the projectioni matrix with 60 degree field of view
    rs_matrix4x4 proj;
    float aspect = (float)rsgGetWidth() / (float)rsgGetHeight();
    rsMatrixLoadPerspective(&proj, 30.0f, aspect, 0.1f, 100.0f);
    rsgProgramVertexLoadProjectionMatrix(&proj);

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNoneDepth);
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentTexture, 0, gTexTorus);

    // Aplly a rotation to our mesh
    gTorusRotation += 50.0f * rsGetDt();
    if(gTorusRotation > 360.0f) {
        gTorusRotation -= 360.0f;
    }

    rs_matrix4x4 matrix;
    // Position our model on the screen
    rsMatrixLoadTranslate(&matrix, -2.0f, 0.0f, -10.0f);
    rsMatrixRotate(&matrix, gTorusRotation, 1.0f, 0.0f, 0.0f);
    rsgProgramVertexLoadModelMatrix(&matrix);
    // Use front face culling
    rsgBindProgramRaster(gCullFront);
    rsgDrawMesh(gTorusMesh);

    rsMatrixLoadTranslate(&matrix, 2.0f, 0.0f, -10.0f);
    rsMatrixRotate(&matrix, gTorusRotation, 1.0f, 0.0f, 0.0f);
    rsgProgramVertexLoadModelMatrix(&matrix);
    // Use back face culling
    rsgBindProgramRaster(gCullBack);
    rsgDrawMesh(gTorusMesh);

    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgBindFont(gFontMono);
    rsgDrawText("Displaying mesh front/back face culling", 10, rsgGetHeight() - 10);
}

float gLight0Rotation = 0;
float gLight1Rotation = 0;

void setupCustomShaderLights() {
    float4 light0Pos = {-5.0f, 5.0f, -10.0f, 1.0f};
    float4 light1Pos = {2.0f, 5.0f, 15.0f, 1.0f};
    float3 light0DiffCol = {0.9f, 0.7f, 0.7f};
    float3 light0SpecCol = {0.9f, 0.8f, 0.8f};
    float3 light1DiffCol = {0.7f, 0.7f, 0.9f};
    float3 light1SpecCol = {0.8f, 0.8f, 0.9f};

    gLight0Rotation += 150.0f * rsGetDt();
    if(gLight0Rotation > 360.0f) {
        gLight0Rotation -= 360.0f;
    }
    gLight1Rotation -= 50.0f * rsGetDt();
    if(gLight1Rotation > 360.0f) {
        gLight1Rotation -= 360.0f;
    }

    rs_matrix4x4 l0Mat;
    rsMatrixLoadRotate(&l0Mat, gLight0Rotation, 1.0f, 0.0f, 0.0f);
    light0Pos = rsMatrixMultiply(&l0Mat, light0Pos);
    rs_matrix4x4 l1Mat;
    rsMatrixLoadRotate(&l1Mat, gLight1Rotation, 0.0f, 0.0f, 1.0f);
    light1Pos = rsMatrixMultiply(&l1Mat, light1Pos);

    // Set light 0 properties
    gVSConstants->light0_Posision.x = light0Pos.x;
    gVSConstants->light0_Posision.y = light0Pos.y;
    gVSConstants->light0_Posision.z = light0Pos.z;
    gVSConstants->light0_Diffuse = 1.0f;
    gVSConstants->light0_Specular = 0.5f;
    gVSConstants->light0_CosinePower = 70.0f;
    // Set light 1 properties
    gVSConstants->light1_Posision.x = light1Pos.x;
    gVSConstants->light1_Posision.y = light1Pos.y;
    gVSConstants->light1_Posision.z = light1Pos.z;
    gVSConstants->light1_Diffuse = 1.0f;
    gVSConstants->light1_Specular = 0.7f;
    gVSConstants->light1_CosinePower = 50.0f;

    // Update fragmetn shader constants
    // Set light 0 colors
    gFSConstants->light0_DiffuseColor = light0DiffCol;
    gFSConstants->light0_SpecularColor = light0SpecCol;
    // Set light 1 colors
    gFSConstants->light1_DiffuseColor = light1DiffCol;
    gFSConstants->light1_SpecularColor = light1SpecCol;
}

void displayCustomShaderSamples() {

    // Update vertex shader constants
    // Load model matrix
    // Aplly a rotation to our mesh
    gTorusRotation += 50.0f * rsGetDt();
    if(gTorusRotation > 360.0f) {
        gTorusRotation -= 360.0f;
    }

    // Position our model on the screen
    rsMatrixLoadTranslate(&gVSConstants->model, 0.0f, 0.0f, -10.0f);
    rsMatrixRotate(&gVSConstants->model, gTorusRotation, 1.0f, 0.0f, 0.0f);
    setupCustomShaderLights();

    rsgBindProgramVertex(gProgVertexCustom);
    // Setup the projectioni matrix with 60 degree field of view
    rs_matrix4x4 proj;
    float aspect = (float)rsgGetWidth() / (float)rsgGetHeight();
    rsMatrixLoadPerspective(&proj, 30.0f, aspect, 0.1f, 100.0f);
    rsgProgramVertexLoadProjectionMatrix(&proj);

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNoneDepth);
    rsgBindProgramFragment(gProgFragmentCustom);
    rsgBindSampler(gProgFragmentCustom, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentCustom, 0, gTexTorus);

    // Use back face culling
    rsgBindProgramRaster(gCullBack);
    rsgDrawMesh(gTorusMesh);

    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgBindFont(gFontMono);
    rsgDrawText("Custom shader sample", 10, rsgGetHeight() - 10);
}

int root(int launchID) {

    rsgClearColor(0.2f, 0.2f, 0.2f, 0.0f);
    rsgClearDepth(1.0f);

    switch(gDisplayMode) {
    case 0:
        displayFontSamples();
        break;
    case 1:
        displayShaderSamples();
        break;
    case 2:
        displayBlendingSamples();
        break;
    case 3:
        displayMeshSamples();
        break;
    case 4:
        displayTextureSamplers();
        break;
    case 5:
        displayCullingSamples();
        break;
    case 6:
        displayCustomShaderSamples();
        break;
    }

    return 10;
}
