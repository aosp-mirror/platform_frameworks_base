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

#pragma rs java_package_name(com.android.rs.test)

#include "rs_graphics.rsh"

float gDY;

rs_font gFont;

typedef struct ListAllocs_s {
    rs_allocation text;
    int result;
} ListAllocs;

ListAllocs *gList;

void init() {
    gDY = 0.0f;
}

int textPos = 0;

int root(void) {

    rsgClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    rsgClearDepth(1.0f);

    textPos -= (int)gDY*2;
    gDY *= 0.95;

    rsgFontColor(0.9f, 0.9f, 0.9f, 1.0f);
    rsgBindFont(gFont);

    rs_allocation listAlloc;
    listAlloc = rsGetAllocation(gList);
    int allocSize = rsAllocationGetDimX(listAlloc);

    int width = rsgGetWidth();
    int height = rsgGetHeight();

    int itemHeight = 80;
    int totalItemHeight = itemHeight * allocSize;

    /* Prevent scrolling above the top of the list */
    int firstItem = height - totalItemHeight;
    if (firstItem < 0) {
        firstItem = 0;
    }

    /* Prevent scrolling past the last line of the list */
    int lastItem = -1 * (totalItemHeight - height);
    if (lastItem > 0) {
        lastItem = 0;
    }

    if (textPos > firstItem) {
        textPos = firstItem;
    }
    else if (textPos < lastItem) {
        textPos = lastItem;
    }

    int currentYPos = itemHeight + textPos;

    for(int i = 0; i < allocSize; i ++) {
        if(currentYPos - itemHeight > height) {
            break;
        }

        if(currentYPos > 0) {
            switch(gList[i].result) {
                case 1: /* Passed */
                    rsgFontColor(0.5f, 0.9f, 0.5f, 1.0f);
                    break;
                case -1: /* Failed */
                    rsgFontColor(0.9f, 0.5f, 0.5f, 1.0f);
                    break;
                case 0: /* Still Testing */
                    rsgFontColor(0.9f, 0.9f, 0.5f, 1.0f);
                    break;
                default: /* Unknown */
                    rsgFontColor(0.9f, 0.9f, 0.9f, 1.0f);
                    break;
            }
            rsgDrawRect(0, currentYPos - 1, width, currentYPos, 0);
            rsgDrawText(gList[i].text, 30, currentYPos - 32);
        }
        currentYPos += itemHeight;
    }

    return 10;
}
