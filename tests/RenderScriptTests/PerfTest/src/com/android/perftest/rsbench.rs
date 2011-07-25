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

#pragma rs java_package_name(com.android.perftest)

#include "rs_graphics.rsh"
#include "shader_def.rsh"
#include "subtest_def.rsh"

/* Message sent from script to renderscript */
const int RS_MSG_TEST_DONE = 100;
const int RS_MSG_RESULTS_READY = 101;

const int gMaxModes = 31;
int gMaxLoops;

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

// Allocation to send test names back to java
char *gStringBuffer = 0;
// Allocation to write the results into
static float gResultBuffer[gMaxModes];

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
rs_allocation gTexChecker;
rs_allocation gTexGlobe;

typedef struct ListAllocs_s {
    rs_allocation item;
} ListAllocs;

ListAllocs *gTexList100;
ListAllocs *gSampleTextList100;
ListAllocs *gListViewText;

rs_mesh g10by10Mesh;
rs_mesh g100by100Mesh;
rs_mesh gWbyHMesh;
rs_mesh gSingleMesh;

rs_font gFontSans;
rs_font gFontSerif;

int gDisplayMode;

rs_sampler gLinearClamp;
rs_sampler gLinearWrap;
rs_sampler gMipLinearWrap;
rs_sampler gNearestClamp;

rs_program_raster gCullBack;
rs_program_raster gCullFront;
rs_program_raster gCullNone;

// Export these out to easily set the inputs to shader
VertexShaderInputs *gVSInputs;

rs_program_fragment gProgFragmentMultitex;

rs_allocation gRenderBufferColor;
rs_allocation gRenderBufferDepth;

static float gDt = 0;

void init() {
}

static int gRenderSurfaceW;
static int gRenderSurfaceH;

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

static void setupOffscreenTarget() {
    rsgBindColorTarget(gRenderBufferColor, 0);
    rsgBindDepthTarget(gRenderBufferDepth);
}

rs_script gFontScript;
rs_script gTorusScript;
rs_allocation gDummyAlloc;

static void displayFontSamples(int fillNum) {
    TestData testData;
    testData.renderSurfaceW = gRenderSurfaceW;
    testData.renderSurfaceH = gRenderSurfaceH;
    testData.user = fillNum;
    rsForEach(gFontScript, gDummyAlloc, gDummyAlloc, &testData, sizeof(testData));
}

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

// Quick hack to get some geometry numbers
static void displaySimpleGeoSamples(bool useTexture, int numMeshes) {
    TestData testData;
    testData.renderSurfaceW = gRenderSurfaceW;
    testData.renderSurfaceH = gRenderSurfaceH;
    testData.dt = gDt;
    testData.user = 0;
    testData.user1 = useTexture ? 1 : 0;
    testData.user2 = numMeshes;
    rsForEach(gTorusScript, gDummyAlloc, gDummyAlloc, &testData, sizeof(testData));
}

static void displayCustomShaderSamples(int numMeshes) {
    TestData testData;
    testData.renderSurfaceW = gRenderSurfaceW;
    testData.renderSurfaceH = gRenderSurfaceH;
    testData.dt = gDt;
    testData.user = 1;
    testData.user1 = numMeshes;
    rsForEach(gTorusScript, gDummyAlloc, gDummyAlloc, &testData, sizeof(testData));
}

static void displayPixelLightSamples(int numMeshes, bool heavyVertex) {
    TestData testData;
    testData.renderSurfaceW = gRenderSurfaceW;
    testData.renderSurfaceH = gRenderSurfaceH;
    testData.dt = gDt;
    testData.user = 2;
    testData.user1 = numMeshes;
    testData.user2 = heavyVertex ? 1 : 0;
    rsForEach(gTorusScript, gDummyAlloc, gDummyAlloc, &testData, sizeof(testData));
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

static bool checkInit() {

    static int countdown = 5;

    // Perform all the uploads so we only measure rendered time
    if(countdown > 1) {
        displayFontSamples(5);
        displaySingletexFill(true, 3);
        displayMeshSamples(0);
        displayMeshSamples(1);
        displayMeshSamples(2);
        displayMultitextureSample(true, 5);
        displayPixelLightSamples(1, false);
        displayPixelLightSamples(1, true);
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
static int runningLoops = 0;
static bool sendMsgFlag = false;

static const char *testNames[] = {
    "Fill screen with text 1 time",
    "Fill screen with text 3 times",
    "Fill screen with text 5 times",
    "Geo test 25.6k flat color",
    "Geo test 51.2k flat color",
    "Geo test 204.8k small tries flat color",
    "Geo test 25.6k single texture",
    "Geo test 51.2k single texture",
    "Geo test 204.8k small tries single texture",
    "Full screen mesh 10 by 10",
    "Full screen mesh 100 by 100",
    "Full screen mesh W / 4 by H / 4",
    "Geo test 25.6k geo heavy vertex",
    "Geo test 51.2k geo heavy vertex",
    "Geo test 204.8k geo raster load heavy vertex",
    "Fill screen 10x singletexture",
    "Fill screen 10x 3tex multitexture",
    "Fill screen 10x blended singletexture",
    "Fill screen 10x blended 3tex multitexture",
    "Geo test 25.6k heavy fragment",
    "Geo test 51.2k heavy fragment",
    "Geo test 204.8k small tries heavy fragment",
    "Geo test 25.6k heavy fragment heavy vertex",
    "Geo test 51.2k heavy fragment heavy vertex",
    "Geo test 204.8k small tries heavy fragment heavy vertex",
    "UI test with icon display 10 by 10",
    "UI test with icon display 100 by 100",
    "UI test with image and text display 3 pages",
    "UI test with image and text display 5 pages",
    "UI test with list view",
    "UI test with live wallpaper",
};

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


void getTestName(int testIndex) {
    int bufferLen = rsAllocationGetDimX(rsGetAllocation(gStringBuffer));
    if (testIndex >= gMaxModes) {
        return;
    }
    uint charIndex = 0;
    while (testNames[testIndex][charIndex] != '\0' && charIndex < bufferLen) {
        gStringBuffer[charIndex] = testNames[testIndex][charIndex];
        charIndex ++;
    }
    gStringBuffer[charIndex] = '\0';
}

static void runTest(int index) {
    switch (index) {
    case 0:
        displayFontSamples(1);
        break;
    case 1:
        displayFontSamples(3);
        break;
    case 2:
        displayFontSamples(5);
        break;
    case 3:
        displaySimpleGeoSamples(false, 1);
        break;
    case 4:
        displaySimpleGeoSamples(false, 2);
        break;
    case 5:
        displaySimpleGeoSamples(false, 8);
        break;
    case 6:
        displaySimpleGeoSamples(true, 1);
        break;
    case 7:
        displaySimpleGeoSamples(true, 2);
        break;
    case 8:
        displaySimpleGeoSamples(true, 8);
        break;
    case 9:
        displayMeshSamples(0);
        break;
    case 10:
        displayMeshSamples(1);
        break;
    case 11:
        displayMeshSamples(2);
        break;
    case 12:
        displayCustomShaderSamples(1);
        break;
    case 13:
        displayCustomShaderSamples(2);
        break;
    case 14:
        displayCustomShaderSamples(10);
        break;
    case 15:
        displaySingletexFill(false, 10);
        break;
    case 16:
        displayMultitextureSample(false, 10);
        break;
    case 17:
        displaySingletexFill(true, 10);
        break;
    case 18:
        displayMultitextureSample(true, 10);
        break;
    case 19:
        displayPixelLightSamples(1, false);
        break;
    case 20:
        displayPixelLightSamples(2, false);
        break;
    case 21:
        displayPixelLightSamples(8, false);
        break;
    case 22:
        displayPixelLightSamples(1, true);
        break;
    case 23:
        displayPixelLightSamples(2, true);
        break;
    case 24:
        displayPixelLightSamples(8, true);
        break;
    case 25:
        displayIcons(0);
        break;
    case 26:
        displayIcons(1);
        break;
    case 27:
        displayImageWithText(7, 5, 0);
        break;
    case 28:
        displayImageWithText(7, 5, 1);
        break;
    case 29:
        displayListView();
        break;
    case 30:
        displayLiveWallPaper(7, 5);
        break;
    }
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

        runTest(benchMode);
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
    rsDebug(testNames[benchMode], fps);
    gResultBuffer[benchMode] = fps;
    drawOffscreenResult(0, 0,
                        gRenderSurfaceW / 2,
                        gRenderSurfaceH / 2);
    const char* text = testNames[benchMode];
    int left = 0, right = 0, top = 0, bottom = 0;
    uint width = rsgGetWidth();
    uint height = rsgGetHeight();
    rsgFontColor(0.9f, 0.9f, 0.95f, 1.0f);
    rsgBindFont(gFontSerif);
    rsgMeasureText(text, &left, &right, &top, &bottom);
    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgDrawText(text, 2 -left, height - 2 + bottom);

    benchMode ++;

    if (benchMode == gMaxModes) {
        rsSendToClientBlocking(RS_MSG_RESULTS_READY, gResultBuffer, gMaxModes*sizeof(float));
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

    rsgFinish();
    runTest(benchMode);
}

int root(void) {
    gRenderSurfaceW = rsgGetWidth();
    gRenderSurfaceH = rsgGetHeight();
    rsgClearColor(0.2f, 0.2f, 0.2f, 1.0f);
    rsgClearDepth(1.0f);
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
