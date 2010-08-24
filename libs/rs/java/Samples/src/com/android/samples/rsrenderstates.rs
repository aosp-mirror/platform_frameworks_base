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

rs_program_vertex gProgVertex;
rs_program_fragment gProgFragmentColor;
rs_program_fragment gProgFragmentTexture;

rs_program_store gProgStoreBlendNone;
rs_program_store gProgStoreBlendAlpha;
rs_program_store gProgStoreBlendAdd;

rs_allocation gTexOpaque;
rs_allocation gTexTransparent;

rs_mesh gMbyNMesh;

rs_font gFontSans;
rs_font gFontSerif;
rs_font gFontSerifBold;
rs_font gFontSerifItalic;
rs_font gFontSerifBoldItalic;
rs_font gFontMono;

int gDisplayMode;

#pragma rs export_var(gProgVertex, gProgFragmentColor, gProgFragmentTexture)
#pragma rs export_var(gProgStoreBlendNone, gProgStoreBlendAlpha, gProgStoreBlendAdd)
#pragma rs export_var(gTexOpaque, gTexTransparent)
#pragma rs export_var(gMbyNMesh)
#pragma rs export_var(gFontSans, gFontSerif, gFontSerifBold, gFontSerifItalic, gFontSerifBoldItalic, gFontMono)

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

void displayShaderSamples() {
    // Default vertex sahder
    rsgBindProgramVertex(gProgVertex);
    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    rsgProgramVertexLoadModelMatrix(&matrix);

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNone);
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindTexture(gProgFragmentTexture, 0, gTexOpaque);

    rsgDrawQuadTexCoords(0, 0,     0, 0, 0,
                         0, 256,   0, 0, 1,
                         256, 256, 0, 1, 1,
                         256, 0,   0, 1, 0);

    rsgDrawQuadTexCoords(200, 0,     0, 0, 0,
                         200, 128,   0, 0, 1,
                         328, 128,   0, 1, 1,
                         328, 0,     0, 1, 0);

    rsgBindProgramStore(gProgStoreBlendAlpha);
    rsgBindTexture(gProgFragmentTexture, 0, gTexTransparent);
    rsgDrawQuadTexCoords(0, 200,   0, 0, 0,
                         0, 328,   0, 0, 1,
                         128, 328, 0, 1, 1,
                         128, 200, 0, 1, 0);

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

    rsgBindProgramVertex(gProgVertex);
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
}

int root(int launchID) {

    rsgClearColor(0.0f, 0.0f, 0.0f, 0.0f);
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
    }

    return 10;
}
