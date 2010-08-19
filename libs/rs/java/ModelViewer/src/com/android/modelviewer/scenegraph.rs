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

#include "rs_graphics.rsh"
#include "transform_def.rsh"

rs_program_vertex gPVBackground;
rs_program_fragment gPFBackground;

rs_allocation gTGrid;
rs_mesh gTestMesh;

rs_program_store gPFSBackground;

float gRotate;

rs_font gItalic;
rs_allocation gTextAlloc;

rs_script gTransformRS;

SgTransform *gGroup;
SgTransform *gRobot1;
int gRobot1Index;
SgTransform *gRobot2;
int gRobot2Index;

SgTransform *gRootNode;

#pragma rs export_var(gPVBackground, gPFBackground, gTGrid, gTestMesh, gPFSBackground, gRotate, gItalic, gTextAlloc, gTransformRS, gGroup, gRobot1, gRobot1Index, gRobot2, gRobot2Index, gRootNode)

float gDT;
int64_t gLastTime;

void init() {
    gRotate = 0.0f;
}

int root(int launchID) {

    gGroup->transforms1.w += 0.5f;
    gGroup->isDirty = 1;

    SgTransform *robot1Ptr = gRobot1 + gRobot1Index;

    robot1Ptr->transforms1.w -= 1.5f;
    robot1Ptr->isDirty = 1;

    SgTransform *robot2Ptr = gRobot2 + gRobot2Index;
    robot2Ptr->transforms1.w += 2.5f;
    robot2Ptr->isDirty = 1;

    rsForEach(gTransformRS, gRootNode->children, gRootNode->children, 0);

    rsgClearColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgClearDepth(1.0f);

    rsgBindProgramVertex(gPVBackground);

    rsgBindProgramFragment(gPFBackground);
    rsgBindProgramStore(gPFSBackground);
    rsgBindTexture(gPFBackground, 0, gTGrid);

    rsgProgramVertexLoadModelMatrix((rs_matrix4x4 *)&robot1Ptr->globalMat_Row0);
    rsgDrawMesh(gTestMesh);

    rsgProgramVertexLoadModelMatrix((rs_matrix4x4 *)&robot2Ptr->globalMat_Row0);
    rsgDrawMesh(gTestMesh);

    color(0.3f, 0.3f, 0.3f, 1.0f);
    rsgDrawText("Renderscript transform test", 30, 695);

    rsgBindFont(gItalic);
    rsgDrawText(gTextAlloc, 30, 730);

    return 10;
}
