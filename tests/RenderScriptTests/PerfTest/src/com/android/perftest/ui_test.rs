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

// Parameters for galaxy live wallpaper
rs_allocation gTSpace;
rs_allocation gTLight1;
rs_allocation gTFlares;
rs_mesh gParticlesMesh;

rs_program_fragment gPFBackground;
rs_program_fragment gPFStars;
rs_program_vertex gPVStars;
rs_program_vertex gPVBkProj;
rs_program_store gPSLights;

float gXOffset = 0.5f;

#define ELLIPSE_RATIO 0.892f
#define PI 3.1415f
#define TWO_PI 6.283f
#define ELLIPSE_TWIST 0.023333333f

static float angle = 50.f;
static int gOldWidth;
static int gOldHeight;
static int gWidth;
static int gHeight;
static float gSpeed[12000];
static int gGalaxyRadius = 300;
static rs_allocation gParticlesBuffer;

typedef struct __attribute__((packed, aligned(4))) Particle {
    uchar4 color;
    float3 position;
} Particle_t;
Particle_t *Particles;

typedef struct VpConsts {
    rs_matrix4x4 Proj;
    rs_matrix4x4 MVP;
} VpConsts_t;
VpConsts_t *vpConstants;
// End of parameters for galaxy live wallpaper

rs_program_vertex gProgVertex;
rs_program_fragment gProgFragmentColor;
rs_program_fragment gProgFragmentTexture;

rs_program_store gProgStoreBlendAlpha;

rs_allocation gTexOpaque;
rs_allocation gTexTorus;
rs_allocation gTexGlobe;

typedef struct ListAllocs_s {
    rs_allocation item;
} ListAllocs;

ListAllocs *gTexList100;
ListAllocs *gSampleTextList100;
ListAllocs *gListViewText;

rs_mesh gSingleMesh;

rs_font gFontSans;

rs_sampler gLinearClamp;

typedef struct UiTestData_s {
    int testId;
    int user1;
    int user2;
    int user3;
} UiTestData;
UiTestData *gData;

static float gDt = 0;


void init() {
}

static int gRenderSurfaceW;
static int gRenderSurfaceH;

static void bindProgramVertexOrtho() {
    // Default vertex shader
    rsgBindProgramVertex(gProgVertex);
    // Setup the projection matrix
    rs_matrix4x4 proj;
    rsMatrixLoadOrtho(&proj, 0, gRenderSurfaceW, gRenderSurfaceH, 0, -500, 500);
    rsgProgramVertexLoadProjectionMatrix(&proj);
}

/**
  * Methods to draw the galaxy live wall paper
  */
static float mapf(float minStart, float minStop, float maxStart, float maxStop, float value) {
    return maxStart + (maxStart - maxStop) * ((value - minStart) / (minStop - minStart));
}

/**
 * Helper function to generate the stars.
 */
static float randomGauss() {
    float x1;
    float x2;
    float w = 2.f;

    while (w >= 1.0f) {
        x1 = rsRand(2.0f) - 1.0f;
        x2 = rsRand(2.0f) - 1.0f;
        w = x1 * x1 + x2 * x2;
    }

    w = sqrt(-2.0f * log(w) / w);
    return x1 * w;
}

/**
 * Generates the properties for a given star.
 */
static void createParticle(Particle_t *part, int idx, float scale) {
    float d = fabs(randomGauss()) * gGalaxyRadius * 0.5f + rsRand(64.0f);
    float id = d / gGalaxyRadius;
    float z = randomGauss() * 0.4f * (1.0f - id);
    float p = -d * ELLIPSE_TWIST;

    if (d < gGalaxyRadius * 0.33f) {
        part->color.x = (uchar) (220 + id * 35);
        part->color.y = 220;
        part->color.z = 220;
    } else {
        part->color.x = 180;
        part->color.y = 180;
        part->color.z = (uchar) clamp(140.f + id * 115.f, 140.f, 255.f);
    }
    // Stash point size * 10 in Alpha
    part->color.w = (uchar) (rsRand(1.2f, 2.1f) * 60);

    if (d > gGalaxyRadius * 0.15f) {
        z *= 0.6f * (1.0f - id);
    } else {
        z *= 0.72f;
    }

    // Map to the projection coordinates (viewport.x = -1.0 -> 1.0)
    d = mapf(-4.0f, gGalaxyRadius + 4.0f, 0.0f, scale, d);

    part->position.x = rsRand(TWO_PI);
    part->position.y = d;
    gSpeed[idx] = rsRand(0.0015f, 0.0025f) * (0.5f + (scale / d)) * 0.8f;

    part->position.z = z / 5.0f;
}

/**
 * Initialize all the starts, called from Java
 */
void initParticles() {
    Particle_t *part = Particles;
    float scale = gGalaxyRadius / (gWidth * 0.5f);
    int count = rsAllocationGetDimX(gParticlesBuffer);
    for (int i = 0; i < count; i ++) {
        createParticle(part, i, scale);
        part++;
    }
}

static void drawSpace() {
    rsgBindProgramFragment(gPFBackground);
    rsgBindTexture(gPFBackground, 0, gTSpace);
    rsgDrawQuadTexCoords(
            0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
            gWidth, 0.0f, 0.0f, 2.0f, 1.0f,
            gWidth, gHeight, 0.0f, 2.0f, 0.0f,
            0.0f, gHeight, 0.0f, 0.0f, 0.0f);
}

static void drawLights() {
    rsgBindProgramVertex(gPVBkProj);
    rsgBindProgramFragment(gPFBackground);
    rsgBindTexture(gPFBackground, 0, gTLight1);

    float scale = 512.0f / gWidth;
    float x = -scale - scale * 0.05f;
    float y = -scale;

    scale *= 2.0f;

    rsgDrawQuad(x, y, 0.0f,
             x + scale * 1.1f, y, 0.0f,
             x + scale * 1.1f, y + scale, 0.0f,
             x, y + scale, 0.0f);
}

static void drawParticles(float offset) {
    float a = offset * angle;
    float absoluteAngle = fabs(a);

    rs_matrix4x4 matrix;
    rsMatrixLoadTranslate(&matrix, 0.0f, 0.0f, 10.0f - 6.0f * absoluteAngle / 50.0f);
    if (gHeight > gWidth) {
        rsMatrixScale(&matrix, 6.6f, 6.0f, 1.0f);
    } else {
        rsMatrixScale(&matrix, 12.6f, 12.0f, 1.0f);
    }
    rsMatrixRotate(&matrix, absoluteAngle, 1.0f, 0.0f, 0.0f);
    rsMatrixRotate(&matrix, a, 0.0f, 0.4f, 0.1f);
    rsMatrixLoad(&vpConstants->MVP, &vpConstants->Proj);
    rsMatrixMultiply(&vpConstants->MVP, &matrix);
    rsgAllocationSyncAll(rsGetAllocation(vpConstants));

    rsgBindProgramVertex(gPVStars);
    rsgBindProgramFragment(gPFStars);
    rsgBindProgramStore(gPSLights);
    rsgBindTexture(gPFStars, 0, gTFlares);

    Particle_t *vtx = Particles;
    int count = rsAllocationGetDimX(gParticlesBuffer);
    for (int i = 0; i < count; i++) {
        vtx->position.x = vtx->position.x + gSpeed[i];
        vtx++;
    }

    rsgDrawMesh(gParticlesMesh);
}
/* end of methods for drawing galaxy */

// Display sample images in a mesh with different texture
static void displayIcons(int meshMode) {
    bindProgramVertexOrtho();

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendAlpha);
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentTexture, 0, gTexTorus);
    rsgDrawQuadTexCoords(
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, gRenderSurfaceH, 0.0f, 0.0f, 1.0f,
            gRenderSurfaceW, gRenderSurfaceH, 0.0f, 1.0f, 1.0f,
            gRenderSurfaceW, 0.0f, 0.0f, 1.0f, 0.0f);

    int meshCount = (int)pow(10.0f, (float)(meshMode + 1));

    float wSize = gRenderSurfaceW/(float)meshCount;
    float hSize = gRenderSurfaceH/(float)meshCount;
    rs_matrix4x4 matrix;
    rsMatrixLoadScale(&matrix, wSize, hSize, 1.0);

    float yPos = 0;
    float yPad = hSize / 2;
    float xPad = wSize / 2;
    for (int y = 0; y < meshCount; y++) {
        yPos = y * hSize + yPad;
        float xPos = 0;
        for (int x = 0; x < meshCount; x++) {
            xPos = x * wSize + xPad;
            rs_matrix4x4 transMatrix;
            rsMatrixLoadTranslate(&transMatrix, xPos, yPos, 0);
            rsMatrixMultiply(&transMatrix, &matrix);
            rsgProgramVertexLoadModelMatrix(&transMatrix);
            int i = (x + y * meshCount) % 100;
            rsgBindTexture(gProgFragmentTexture, 0, gTexList100[i].item);
            rsgDrawMesh(gSingleMesh);
        }
    }
}

// Draw meshes in a single page with top left corner coordinates (xStart, yStart)
static void drawMeshInPage(float xStart, float yStart, int wResolution, int hResolution) {
    // Draw wResolution * hResolution meshes in one page
    float wMargin = 100.0f;
    float hMargin = 100.0f;
    float xPad = 50.0f;
    float yPad = 20.0f;
    float size = 100.0f;  // size of images

    // font info
    rs_font font = gFontSans;
    rsgBindFont(font);
    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);

    // Measure text size
    int left = 0, right = 0, top = 0, bottom = 0;
    rsgMeasureText(gSampleTextList100[0].item, &left, &right, &top, &bottom);
    float textHeight = (float)(top - bottom);
    float textWidth = (float)(right - left);

    rs_matrix4x4 matrix;
    rsMatrixLoadScale(&matrix, size, size, 1.0);

    for (int y = 0; y < hResolution; y++) {
        float yPos = yStart + hMargin + y * size + y * yPad;
        for (int x = 0; x < wResolution; x++) {
            float xPos = xStart + wMargin + x * size + x * xPad;

            rs_matrix4x4 transMatrix;
            rsMatrixLoadTranslate(&transMatrix, xPos + size/2, yPos + size/2, 0);
            rsMatrixMultiply(&transMatrix, &matrix);  // scale the mesh
            rsgProgramVertexLoadModelMatrix(&transMatrix);

            int i = (y * wResolution + x) % 100;
            rsgBindTexture(gProgFragmentTexture, 0, gTexList100[i].item);
            rsgDrawMesh(gSingleMesh);
            rsgDrawText(gSampleTextList100[i].item, xPos, yPos + size + yPad/2 + textHeight);
        }
    }
}

// Display both images and text as shown in launcher and homepage
// meshMode will decide how many pages we draw
// meshMode = 0: draw 3 pages of meshes
// meshMode = 1: draw 5 pages of meshes
static void displayImageWithText(int wResolution, int hResolution, int meshMode) {
    bindProgramVertexOrtho();

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendAlpha);
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);

    drawMeshInPage(0, 0, wResolution, hResolution);
    drawMeshInPage(-1.0f*gRenderSurfaceW, 0, wResolution, hResolution);
    drawMeshInPage(1.0f*gRenderSurfaceW, 0, wResolution, hResolution);
    if (meshMode == 1) {
        // draw another two pages of meshes
        drawMeshInPage(-2.0f*gRenderSurfaceW, 0, wResolution, hResolution);
        drawMeshInPage(2.0f*gRenderSurfaceW, 0, wResolution, hResolution);
    }
}

// Display a list of text as the list view
static void displayListView() {
    // set text color
    rsgFontColor(0.9f, 0.9f, 0.9f, 1.0f);
    rsgBindFont(gFontSans);

    // get the size of the list
    rs_allocation textAlloc;
    textAlloc = rsGetAllocation(gListViewText);
    int allocSize = rsAllocationGetDimX(textAlloc);

    int listItemHeight = 80;
    int yOffset = listItemHeight;

    // set the color for the list divider
    rsgBindProgramFragment(gProgFragmentColor);
    rsgProgramFragmentConstantColor(gProgFragmentColor, 1.0, 1.0, 1.0, 1);

    // draw the list with divider
    for (int i = 0; i < allocSize; i++) {
        if (yOffset - listItemHeight > gRenderSurfaceH) {
            break;
        }
        rsgDrawRect(0, yOffset - 1, gRenderSurfaceW, yOffset, 0);
        rsgDrawText(gListViewText[i].item, 20, yOffset - 10);
        yOffset += listItemHeight;
    }
}

static void drawGalaxy() {
    rsgClearColor(0.f, 0.f, 0.f, 1.f);
    gParticlesBuffer = rsGetAllocation(Particles);
    rsgBindProgramFragment(gPFBackground);

    gWidth = rsgGetWidth();
    gHeight = rsgGetHeight();
    if ((gWidth != gOldWidth) || (gHeight != gOldHeight)) {
        initParticles();
        gOldWidth = gWidth;
        gOldHeight = gHeight;
    }

    float offset = mix(-1.0f, 1.0f, gXOffset);
    drawSpace();
    drawParticles(offset);
    drawLights();
}

// Display images and text with live wallpaper in the background
static void displayLiveWallPaper(int wResolution, int hResolution) {
    bindProgramVertexOrtho();

    drawGalaxy();

    rsgBindProgramVertex(gProgVertex);
    rsgBindProgramStore(gProgStoreBlendAlpha);
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);

    drawMeshInPage(0, 0, wResolution, hResolution);
    drawMeshInPage(-1.0f*gRenderSurfaceW, 0, wResolution, hResolution);
    drawMeshInPage(1.0f*gRenderSurfaceW, 0, wResolution, hResolution);
    drawMeshInPage(-2.0f*gRenderSurfaceW, 0, wResolution, hResolution);
    drawMeshInPage(2.0f*gRenderSurfaceW, 0, wResolution, hResolution);
}

void root(const void *v_in, void *v_out, const void *usrData, uint32_t x, uint32_t y) {
    TestData *testData = (TestData*)usrData;
    gRenderSurfaceW = testData->renderSurfaceW;
    gRenderSurfaceH = testData->renderSurfaceH;
    gDt = testData->dt;

    gData = (UiTestData*)v_in;

    switch(gData->testId) {
        case 0:
            displayIcons(gData->user1);
            break;
        case 1:
            displayImageWithText(gData->user1, gData->user2, gData->user3);
            break;
        case 2:
            displayListView();
            break;
        case 3:
            displayLiveWallPaper(gData->user1, gData->user2);
            break;
        default:
            rsDebug("Wrong test number", 0);
            break;
    }
}
