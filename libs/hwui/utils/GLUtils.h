/*
 * Copyright (C) 2014 The Android Open Source Project
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
#ifndef GLUTILS_H
#define GLUTILS_H

#include "Debug.h"

#include <cutils/log.h>

namespace android {
namespace uirenderer {


#if DEBUG_OPENGL
#define GL_CHECKPOINT(LEVEL) \
    do { if (DEBUG_OPENGL >= DEBUG_LEVEL_##LEVEL) {\
    LOG_ALWAYS_FATAL_IF(GLUtils::dumpGLErrors(),\
            "GL errors! %s:%d", __FILE__, __LINE__);\
    } } while (0)
#else
#define GL_CHECKPOINT(LEVEL)
#endif

class GLUtils {
public:
    /**
     * Print out any GL errors with ALOGE, returns true if any errors were found.
     * You probably want to use GL_CHECKPOINT(LEVEL) instead of calling this directly
     */
    static bool dumpGLErrors();

}; // class GLUtils

} /* namespace uirenderer */
} /* namespace android */

#endif /* GLUTILS_H */
