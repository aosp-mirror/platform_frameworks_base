/*
 * Copyright (C) 2011 The Android Open Source Project
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


#include "rsdCore.h"
#include "rsdProgramStore.h"

#include "rsContext.h"
#include "rsProgramStore.h"

#include <GLES/gl.h>
#include <GLES/glext.h>


using namespace android;
using namespace android::renderscript;

bool rsdProgramRasterInit(const Context *, const ProgramRaster *) {
    return true;
}

void rsdProgramRasterSetActive(const Context *rsc, const ProgramRaster *pr) {
    switch (pr->mHal.state.cull) {
        case RS_CULL_BACK:
            RSD_CALL_GL(glEnable, GL_CULL_FACE);
            RSD_CALL_GL(glCullFace, GL_BACK);
            break;
        case RS_CULL_FRONT:
            RSD_CALL_GL(glEnable, GL_CULL_FACE);
            RSD_CALL_GL(glCullFace, GL_FRONT);
            break;
        case RS_CULL_NONE:
            RSD_CALL_GL(glDisable, GL_CULL_FACE);
            break;
        default:
            LOGE("Invalid cull type");
            break;
    }

}

void rsdProgramRasterDestroy(const Context *, const ProgramRaster *) {
}


