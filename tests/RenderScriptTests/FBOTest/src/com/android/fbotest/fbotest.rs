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

#pragma rs java_package_name(com.android.fbotest)

#include "rs_graphics.rsh"

rs_program_vertex gPVBackground;
rs_program_fragment gPFBackground;

rs_allocation gTGrid;

rs_program_store gPFSBackground;

rs_font gItalic;
rs_allocation gTextAlloc;

rs_allocation gOffscreen;
rs_allocation gOffscreenDepth;

typedef struct MeshInfo {
    rs_mesh mMesh;
    int mNumIndexSets;
    float3 bBoxMin;
    float3 bBoxMax;
} MeshInfo_t;

MeshInfo_t *gMeshes;

static float3 gLookAt;

static float gRotateX;
static float gRotateY;
static float gZoom;

static float gLastX;
static float gLastY;

void onActionDown(float x, float y) {
    gLastX = x;
    gLastY = y;
}

void onActionScale(float scale) {

    gZoom *= 1.0f / scale;
    gZoom = max(0.1f, min(gZoom, 500.0f));
}

void onActionMove(float x, float y) {
    float dx = gLastX - x;
    float dy = gLastY - y;

    if (fabs(dy) <= 2.0f) {
        dy = 0.0f;
    }
    if (fabs(dx) <= 2.0f) {
        dx = 0.0f;
    }

    gRotateY -= dx;
    if (gRotateY > 360) {
        gRotateY -= 360;
    }
    if (gRotateY < 0) {
        gRotateY += 360;
    }

    gRotateX -= dy;
    gRotateX = min(gRotateX, 80.0f);
    gRotateX = max(gRotateX, -80.0f);

    gLastX = x;
    gLastY = y;
}

void init() {
    gRotateX = 0.0f;
    gRotateY = 0.0f;
    gZoom = 50.0f;
    gLookAt = 0.0f;
}

void updateMeshInfo() {
    rs_allocation allMeshes = rsGetAllocation(gMeshes);
    int size = rsAllocationGetDimX(allMeshes);
    gLookAt = 0.0f;
    float minX, minY, minZ, maxX, maxY, maxZ;
    for (int i = 0; i < size; i++) {
        MeshInfo_t *info = (MeshInfo_t*)rsGetElementAt(allMeshes, i);
        rsgMeshComputeBoundingBox(info->mMesh,
                                  &minX, &minY, &minZ,
                                  &maxX, &maxY, &maxZ);
        info->bBoxMin = (float3){minX, minY, minZ};
        info->bBoxMax = (float3){maxX, maxY, maxZ};
        gLookAt += (info->bBoxMin + info->bBoxMax)*0.5f;
    }
    gLookAt = gLookAt / (float)size;
}

static void renderAllMeshes() {
    rs_allocation allMeshes = rsGetAllocation(gMeshes);
    int size = rsAllocationGetDimX(allMeshes);
    gLookAt = 0.0f;
    float minX, minY, minZ, maxX, maxY, maxZ;
    for (int i = 0; i < size; i++) {
        MeshInfo_t *info = (MeshInfo_t*)rsGetElementAt(allMeshes, i);
        rsgDrawMesh(info->mMesh);
    }
}

static void drawDescription() {
    uint width = rsgGetWidth();
    uint height = rsgGetHeight();
    int left = 0, right = 0, top = 0, bottom = 0;

    rsgBindFont(gItalic);

    rsgMeasureText(gTextAlloc, &left, &right, &top, &bottom);
    rsgDrawText(gTextAlloc, 2 -left, height - 2 + bottom);
}

static void renderOffscreen(bool useDepth) {

    rsgBindColorTarget(gOffscreen, 0);
    if (useDepth) {
        rsgBindDepthTarget(gOffscreenDepth);
        rsgClearDepth(1.0f);
    } else {
        rsgClearDepthTarget();
    }
    rsgClearColor(0.8f, 0.8f, 0.8f, 1.0f);

    rsgBindProgramVertex(gPVBackground);
    rs_matrix4x4 proj;
    float aspect = (float)rsAllocationGetDimX(gOffscreen) / (float)rsAllocationGetDimY(gOffscreen);
    rsMatrixLoadPerspective(&proj, 30.0f, aspect, 1.0f, 100.0f);
    rsgProgramVertexLoadProjectionMatrix(&proj);

    rsgBindProgramFragment(gPFBackground);
    rsgBindProgramStore(gPFSBackground);
    rsgBindTexture(gPFBackground, 0, gTGrid);

    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    // Position our models on the screen
    rsMatrixTranslate(&matrix, gLookAt.x, gLookAt.y, gLookAt.z - gZoom);
    rsMatrixRotate(&matrix, gRotateX, 1.0f, 0.0f, 0.0f);
    rsMatrixRotate(&matrix, gRotateY, 0.0f, 1.0f, 0.0f);
    rsgProgramVertexLoadModelMatrix(&matrix);

    renderAllMeshes();

    // Render into the frambuffer
    rsgClearAllRenderTargets();
}

static void drawOffscreenResult(int posX, int posY) {
    // display the result
    rs_matrix4x4 proj, matrix;
    rsMatrixLoadOrtho(&proj, 0, rsgGetWidth(), rsgGetHeight(), 0, -500, 500);
    rsgProgramVertexLoadProjectionMatrix(&proj);
    rsMatrixLoadIdentity(&matrix);
    rsgProgramVertexLoadModelMatrix(&matrix);
    rsgBindTexture(gPFBackground, 0, gOffscreen);
    float startX = posX, startY = posY;
    float width = 256, height = 256;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 1,
                         startX, startY + height, 0, 0, 0,
                         startX + width, startY + height, 0, 1, 0,
                         startX + width, startY, 0, 1, 1);
}

int root(void) {

    rsgClearColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgClearDepth(1.0f);

    renderOffscreen(true);
    drawOffscreenResult(0, 0);

    renderOffscreen(false);
    drawOffscreenResult(0, 256);

    rsgBindProgramVertex(gPVBackground);
    rs_matrix4x4 proj;
    float aspect = (float)rsgGetWidth() / (float)rsgGetHeight();
    rsMatrixLoadPerspective(&proj, 30.0f, aspect, 1.0f, 100.0f);
    rsgProgramVertexLoadProjectionMatrix(&proj);

    rsgBindProgramFragment(gPFBackground);
    rsgBindProgramStore(gPFSBackground);
    rsgBindTexture(gPFBackground, 0, gTGrid);

    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    // Position our models on the screen
    rsMatrixTranslate(&matrix, gLookAt.x, gLookAt.y, gLookAt.z - gZoom);
    rsMatrixRotate(&matrix, gRotateX, 1.0f, 0.0f, 0.0f);
    rsMatrixRotate(&matrix, gRotateY, 0.0f, 1.0f, 0.0f);
    rsgProgramVertexLoadModelMatrix(&matrix);

    renderAllMeshes();

    drawDescription();

    return 0;
}
