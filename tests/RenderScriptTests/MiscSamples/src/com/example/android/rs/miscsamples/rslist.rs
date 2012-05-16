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

#pragma rs java_package_name(com.example.android.rs.miscsamples)

#include "rs_graphics.rsh"

float gDY;

rs_font gItalic;

typedef struct ListAllocs_s {
    rs_allocation text;
} ListAllocs;

ListAllocs *gList;

void init() {
    gDY = 0.0f;
}

int textPos = 0;

int root(void) {

    rsgClearColor(0.0f, 0.0f, 0.0f, 0.0f);

    textPos -= (int)gDY*2;
    gDY *= 0.95;

    rsgFontColor(0.9f, 0.9f, 0.9f, 1.0f);
    rsgBindFont(gItalic);

    rs_allocation listAlloc;
    listAlloc = rsGetAllocation(gList);
    int allocSize = rsAllocationGetDimX(listAlloc);

    int width = rsgGetWidth();
    int height = rsgGetHeight();

    int itemHeight = 80;
    int currentYPos = itemHeight + textPos;

    for (int i = 0; i < allocSize; i ++) {
        if (currentYPos - itemHeight > height) {
            break;
        }

        if (currentYPos > 0) {
            rsgDrawRect(0, currentYPos - 1, width, currentYPos, 0);
            rsgDrawText(gList[i].text, 30, currentYPos - 32);
        }
        currentYPos += itemHeight;
    }

    return 10;
}
