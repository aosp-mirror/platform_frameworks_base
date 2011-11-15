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

rs_font gFontSans;
rs_font gFontSerif;

typedef struct TextTestData_s {
    int fillNum;
} TextTestData;
TextTestData *gData;

void init() {
}

static int gRenderSurfaceW = 1280;
static int gRenderSurfaceH = 720;

static const char *sampleText = "This is a sample of small text for performace";
// Offsets for multiple layer of text
static int textOffsets[] = { 0,  0, -5, -5, 5,  5, -8, -8, 8,  8};
static float textColors[] = {1.0f, 1.0f, 1.0f, 1.0f,
                             0.5f, 0.7f, 0.5f, 1.0f,
                             0.7f, 0.5f, 0.5f, 1.0f,
                             0.5f, 0.5f, 0.7f, 1.0f,
                             0.5f, 0.6f, 0.7f, 1.0f,
};

static void displayFontSamples(int fillNum) {

    rs_font fonts[5];
    fonts[0] = gFontSans;
    fonts[1] = gFontSerif;
    fonts[2] = gFontSans;
    fonts[3] = gFontSerif;
    fonts[4] = gFontSans;

    uint width = gRenderSurfaceW;
    uint height = gRenderSurfaceH;
    int left = 0, right = 0, top = 0, bottom = 0;
    rsgMeasureText(sampleText, &left, &right, &top, &bottom);

    int textHeight = top - bottom;
    int textWidth = right - left;
    int numVerticalLines = height / textHeight;
    int yPos = top;

    int xOffset = 0, yOffset = 0;
    for(int fillI = 0; fillI < fillNum; fillI ++) {
        rsgBindFont(fonts[fillI]);
        xOffset = textOffsets[fillI * 2];
        yOffset = textOffsets[fillI * 2 + 1];
        float *colPtr = textColors + fillI * 4;
        rsgFontColor(colPtr[0], colPtr[1], colPtr[2], colPtr[3]);
        for (int h = 0; h < 4; h ++) {
            yPos = top + yOffset;
            for (int v = 0; v < numVerticalLines; v ++) {
                rsgDrawText(sampleText, xOffset + textWidth * h, yPos);
                yPos += textHeight;
            }
        }
    }
}

void root(const void *v_in, void *v_out, const void *usrData, uint32_t x, uint32_t y) {
    TestData *testData = (TestData*)usrData;
    gRenderSurfaceW = testData->renderSurfaceW;
    gRenderSurfaceH = testData->renderSurfaceH;

    gData = (TextTestData*)v_in;

    displayFontSamples(gData->fillNum);
}
