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

void init() {
    gRotate = 0.0f;
}

int root(void) {

    gGroup->transforms[1].w += 0.5f;
    gGroup->isDirty = 1;

    SgTransform *robot1Ptr = gRobot1 + gRobot1Index;

    robot1Ptr->transforms[1].w -= 1.5f;
    robot1Ptr->isDirty = 1;

    SgTransform *robot2Ptr = gRobot2 + gRobot2Index;
    robot2Ptr->transforms[1].w += 2.5f;
    robot2Ptr->isDirty = 1;

    rsForEach(gTransformRS, gRootNode->children, gRootNode->children);

    rsgClearColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgClearDepth(1.0f);

    rsgBindProgramVertex(gPVBackground);
    rs_matrix4x4 proj;
    float aspect = (float)rsgGetWidth() / (float)rsgGetHeight();
    rsMatrixLoadPerspective(&proj, 30.0f, aspect, 0.1f, 100.0f);
    rsgProgramVertexLoadProjectionMatrix(&proj);

    rsgBindProgramFragment(gPFBackground);
    rsgBindProgramStore(gPFSBackground);
    rsgBindTexture(gPFBackground, 0, gTGrid);

    rsgProgramVertexLoadModelMatrix(&robot1Ptr->globalMat);
    rsgDrawMesh(gTestMesh);

    rsgProgramVertexLoadModelMatrix(&robot2Ptr->globalMat);
    rsgDrawMesh(gTestMesh);

    //color(0.3f, 0.3f, 0.3f, 1.0f);
    rsgDrawText("Renderscript transform test", 30, 695);

    rsgBindFont(gItalic);
    rsgDrawText(gTextAlloc, 30, 730);

    return 10;
}
