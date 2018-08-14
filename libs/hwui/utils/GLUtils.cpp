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

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <utils/Log.h>

#include "GLUtils.h"

#if DEBUG_OPENGL >= DEBUG_LEVEL_HIGH && !defined(HWUI_GLES_WRAP_ENABLED)
#error Setting DEBUG_OPENGL to HIGH requires setting HWUI_ENABLE_OPENGL_VALIDATION to true in the Android.mk!
#endif

namespace android {
namespace uirenderer {

bool GLUtils::dumpGLErrors() {
#if DEBUG_OPENGL >= DEBUG_LEVEL_HIGH
    // If DEBUG_LEVEL_HIGH is set then every GLES call is already wrapped
    // and asserts that there was no error. So this can just return success.
    return false;
#else
    bool errorObserved = false;
    GLenum status = GL_NO_ERROR;
    while ((status = glGetError()) != GL_NO_ERROR) {
        errorObserved = true;
        switch (status) {
            case GL_INVALID_ENUM:
                ALOGE("GL error:  GL_INVALID_ENUM");
                break;
            case GL_INVALID_VALUE:
                ALOGE("GL error:  GL_INVALID_VALUE");
                break;
            case GL_INVALID_OPERATION:
                ALOGE("GL error:  GL_INVALID_OPERATION");
                break;
            case GL_OUT_OF_MEMORY:
                ALOGE("GL error:  Out of memory!");
                break;
            default:
                ALOGE("GL error: 0x%x", status);
        }
    }
    return errorObserved;
#endif
}

};  // namespace uirenderer
};  // namespace android
