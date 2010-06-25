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

#pragma rs java_package_name(com.android.modelviewer)

#include "../../../../scriptc/rs_types.rsh"
#include "../../../../scriptc/rs_math.rsh"
#include "../../../../scriptc/rs_graphics.rsh"

rs_program_vertex gPVBackground;
rs_program_fragment gPFBackground;

rs_allocation gTGrid;
rs_mesh gTestMesh;

rs_program_store gPFSBackground;

float gRotate;

rs_font gItalic;
rs_allocation gTextAlloc;

#pragma rs export_var(gPVBackground, gPFBackground, gTGrid, gTestMesh, gPFSBackground, gRotate, gItalic, gTextAlloc)

float gDT;
int64_t gLastTime;

void init() {
    gRotate = 0.0f;
}

int root(int launchID) {

    rsgClearColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgClearDepth(1.0f);

    rsgBindProgramVertex(gPVBackground);

    rsgBindProgramFragment(gPFBackground);
    rsgBindProgramStore(gPFSBackground);
    rsgBindTexture(gPFBackground, 0, gTGrid);

    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    // Position our model on the screen
    rsMatrixTranslate(&matrix, 0.0f, -0.3f, 1.2f);
    rsMatrixScale(&matrix, 0.2f, 0.2f, 0.2f);
    rsMatrixRotate(&matrix, -25.0f, 1.0f, 0.0f, 0.0f);
    rsMatrixRotate(&matrix, gRotate, 0.0f, 1.0f, 0.0f);
    rsgProgramVertexLoadModelMatrix(&matrix);

    rsgDrawSimpleMesh(gTestMesh);

    color(0.3f, 0.3f, 0.3f, 1.0f);
    rsgDrawText("Renderscript model test", 30, 695);

    rsgBindFont(gItalic);
    rsgDrawText(gTextAlloc, 30, 730);

    return 10;
}
