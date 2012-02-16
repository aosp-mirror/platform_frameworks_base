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

#ifndef RSD_PROGRAM_VERTEX_H
#define RSD_PROGRAM_VERTEX_H

#include <rs_hal.h>

bool rsdProgramVertexInit(const android::renderscript::Context *rsc,
                          const android::renderscript::ProgramVertex *,
                          const char* shader, size_t shaderLen,
                          const char** textureNames, size_t textureNamesCount,
                          const size_t *textureNamesLength);
void rsdProgramVertexSetActive(const android::renderscript::Context *rsc,
                               const android::renderscript::ProgramVertex *);
void rsdProgramVertexDestroy(const android::renderscript::Context *rsc,
                             const android::renderscript::ProgramVertex *);


#endif //RSD_PROGRAM_VERTEX_H
