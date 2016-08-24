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

#include "unwrap_gles.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl31.h>
#include <GLES3/gl32.h>

#include <cutils/log.h>

void assertNoGlErrors(const char* apicall) {
    GLenum status = GL_NO_ERROR;
    GLenum lastError = GL_NO_ERROR;
    const char* lastErrorName = nullptr;
    while ((status = glGetError()) != GL_NO_ERROR) {
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
    LOG_ALWAYS_FATAL_IF(lastError != GL_NO_ERROR,
            "%s error! %s (0x%x)", apicall, lastErrorName, lastError);
}

#define API_ENTRY(x) wrap_##x
#define CALL_GL_API(x, ...) x(__VA_ARGS__); assertNoGlErrors(#x)
#define CALL_GL_API_RETURN(x, ...) auto ret = x(__VA_ARGS__);\
    assertNoGlErrors(#x);\
    return ret

extern "C" {
#include <gl2_api.in>
#include <gl2ext_api.in>

// libGLESv2 handles these specially, so they are not in gl2_api.in

void API_ENTRY(glGetBooleanv)(GLenum pname, GLboolean *data) {
    CALL_GL_API(glGetBooleanv, pname, data);
}
void API_ENTRY(glGetFloatv)(GLenum pname, GLfloat *data) {
    CALL_GL_API(glGetFloatv, pname, data);
}
void API_ENTRY(glGetIntegerv)(GLenum pname, GLint *data) {
    CALL_GL_API(glGetIntegerv, pname, data);
}
const GLubyte * API_ENTRY(glGetString)(GLenum name) {
    CALL_GL_API_RETURN(glGetString, name);
}
const GLubyte * API_ENTRY(glGetStringi)(GLenum name, GLuint index) {
    CALL_GL_API_RETURN(glGetStringi, name, index);
}
void API_ENTRY(glGetInteger64v)(GLenum pname, GLint64 *data) {
    CALL_GL_API(glGetInteger64v, pname, data);
}
}
