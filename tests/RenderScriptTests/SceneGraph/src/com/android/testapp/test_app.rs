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

#pragma rs java_package_name(com.android.testapp)

#include "rs_graphics.rsh"
#include "test_app.rsh"

// Making sure these get reflected
FBlurOffsets *blurExport;
VShaderInputs *iExport;
FShaderParams *fConst;
FShaderLightParams *fConts2;
VSParams *vConst2;
VObjectParams *vConst3;

rs_program_vertex gPVBackground;
rs_program_fragment gPFBackground;

rs_allocation gRobotTex;
rs_mesh gRobotMesh;

rs_program_store gPFSBackground;

float gRotate;

void init() {
    gRotate = 0.0f;
}

static int pos = 50;
static float gRotateY = 120.0f;
static float3 gLookAt = 0;
static float gZoom = 50.0f;
static void displayLoading() {
    if (rsIsObject(gRobotTex) && rsIsObject(gRobotMesh)) {
        rsgBindProgramVertex(gPVBackground);
        rs_matrix4x4 proj;
        float aspect = (float)rsgGetWidth() / (float)rsgGetHeight();
        rsMatrixLoadPerspective(&proj, 30.0f, aspect, 1.0f, 100.0f);
        rsgProgramVertexLoadProjectionMatrix(&proj);

        rsgBindProgramFragment(gPFBackground);
        rsgBindProgramStore(gPFSBackground);
        rsgBindTexture(gPFBackground, 0, gRobotTex);

        rs_matrix4x4 matrix;
        rsMatrixLoadIdentity(&matrix);
        // Position our models on the screen
        gRotateY += rsGetDt()*100;
        rsMatrixTranslate(&matrix, 0, 0, -gZoom);
        rsMatrixRotate(&matrix, 20.0f, 1.0f, 0.0f, 0.0f);
        rsMatrixRotate(&matrix, gRotateY, 0.0f, 1.0f, 0.0f);
        rsMatrixScale(&matrix, 0.2f, 0.2f, 0.2f);
        rsgProgramVertexLoadModelMatrix(&matrix);
        rsgDrawMesh(gRobotMesh);
    }

    uint width = rsgGetWidth();
    uint height = rsgGetHeight();
    int left = 0, right = 0, top = 0, bottom = 0;
    const char* text = "Initializing...";
    rsgMeasureText(text, &left, &right, &top, &bottom);
    int centeredPos = width / 2 - (right - left) / 2;
    rsgDrawText(text, centeredPos, height / 2 + height / 10);
}

int root(void) {
    rsgClearColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgClearDepth(1.0f);
    displayLoading();
    return 30;
}
