/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "GlesErrorCheckWrapper.h"

#include <log/log.h>

namespace android {
namespace uirenderer {
namespace debug {

void GlesErrorCheckWrapper::assertNoErrors(const char* apicall) {
    GLenum status = GL_NO_ERROR;
    GLenum lastError = GL_NO_ERROR;
    const char* lastErrorName = nullptr;
    while ((status = mBase.glGetError_()) != GL_NO_ERROR) {
        lastError = status;
        switch (status) {
            case GL_INVALID_ENUM:
                ALOGE("GL error:  GL_INVALID_ENUM");
                lastErrorName = "GL_INVALID_ENUM";
                break;
            case GL_INVALID_VALUE:
                ALOGE("GL error:  GL_INVALID_VALUE");
                lastErrorName = "GL_INVALID_VALUE";
                break;
            case GL_INVALID_OPERATION:
                ALOGE("GL error:  GL_INVALID_OPERATION");
                lastErrorName = "GL_INVALID_OPERATION";
                break;
            case GL_OUT_OF_MEMORY:
                ALOGE("GL error:  Out of memory!");
                lastErrorName = "GL_OUT_OF_MEMORY";
                break;
            default:
                ALOGE("GL error: 0x%x", status);
                lastErrorName = "UNKNOWN";
        }
    }
    LOG_ALWAYS_FATAL_IF(lastError != GL_NO_ERROR, "%s error! %s (0x%x)", apicall, lastErrorName,
                        lastError);
}

#define API_ENTRY(x) GlesErrorCheckWrapper::x##_
#define CALL_GL_API(x, ...)  \
    mBase.x##_(__VA_ARGS__); \
    assertNoErrors(#x)

#define CALL_GL_API_RETURN(x, ...)      \
    auto ret = mBase.x##_(__VA_ARGS__); \
    assertNoErrors(#x);                 \
    return ret

#include "gles_stubs.in"

#undef API_ENTRY
#undef CALL_GL_API
#undef CALL_GL_API_RETURN

}  // namespace debug
}  // namespace uirenderer
}  // namespace android
