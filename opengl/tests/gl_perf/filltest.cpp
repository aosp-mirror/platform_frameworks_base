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

static void doSingleTest(uint32_t w, uint32_t h,
                         bool useVarColor,
                         int texCount,
                         bool modulateFirstTex,
                         int extraMath,
                         int tex0, int tex1) {
    char *pgmTxt = genShader(useVarColor, texCount, modulateFirstTex, extraMath);
    int pgm = createProgram(gVertexShader, pgmTxt);
    if (!pgm) {
        printf("error running test\n");
        return;
    }
    int loc = glGetUniformLocation(pgm, "u_tex0");
    if (loc >= 0) glUniform1i(loc, 0);
    loc = glGetUniformLocation(pgm, "u_tex1");
    if (loc >= 0) glUniform1i(loc, 1);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, tex1);
    glActiveTexture(GL_TEXTURE0);

    char str2[1024];

    glBlendFunc(GL_ONE, GL_ONE);
    glDisable(GL_BLEND);
    //sprintf(str2, "%i, %i, %i, %i, %i, 0",
            //useVarColor, texCount, modulateFirstTex, extraMath, tex0);
    //doLoop(true, pgm, w, h, str2);
    //doLoop(false, pgm, w, h, str2);

    glEnable(GL_BLEND);
    sprintf(str2, "%i, %i, %i, %i, %i, 1",
            useVarColor, texCount, modulateFirstTex, extraMath, tex0);
    doLoop(true, pgm, w, h, str2);
    doLoop(false, pgm, w, h, str2);
}

bool doTest(uint32_t w, uint32_t h) {
    setupVA();
    genTextures();

    printf("\nvarColor, texCount, modulate, extraMath, texSize, blend, Mpps, DC60\n");

    for (int texCount = 0; texCount < 2; texCount++) {
        for (int extraMath = 0; extraMath < 5; extraMath++) {

            doSingleTest(w, h, false, texCount, false, extraMath, 1, 1);
            doSingleTest(w, h, true, texCount, false, extraMath, 1, 1);
            if (texCount) {
                doSingleTest(w, h, false, texCount, true, extraMath, 1, 1);
                doSingleTest(w, h, true, texCount, true, extraMath, 1, 1);

                doSingleTest(w, h, false, texCount, false, extraMath, 2, 2);
                doSingleTest(w, h, true, texCount, false, extraMath, 2, 2);
                doSingleTest(w, h, false, texCount, true, extraMath, 2, 2);
                doSingleTest(w, h, true, texCount, true, extraMath, 2, 2);
            }
        }
    }

    exit(0);
    return true;
}
