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

#define LOG_TAG "OpenGLRenderer"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <utils/Log.h>

#include "GLUtils.h"

namespace android {
namespace uirenderer {

void GLUtils::dumpGLErrors() {
    GLenum status = GL_NO_ERROR;
    while ((status = glGetError()) != GL_NO_ERROR) {
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
}

}; // namespace uirenderer
}; // namespace android
