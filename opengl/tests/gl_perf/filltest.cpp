/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <sched.h>
#include <sys/resource.h>
#include <string.h>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <utils/Timers.h>
#include <EGL/egl.h>
#include <utils/Log.h>


using namespace android;


#include "fill_common.cpp"


bool doTest(uint32_t w, uint32_t h) {
    gWidth = w;
    gHeight = h;
    setupVA();
    genTextures();

    printf("\nvarColor, texCount, modulate, extraMath, texSize, blend, Mpps, DC60\n");

    for (uint32_t num = 0; num < gFragmentTestCount; num++) {
        doSingleTest(num, 2);
        if (gFragmentTests[num]->texCount) {
            doSingleTest(num, 1);
        }
    }

    exit(0);
    return true;
}
