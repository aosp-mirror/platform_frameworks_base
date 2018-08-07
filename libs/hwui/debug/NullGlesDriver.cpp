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

#include <debug/NullGlesDriver.h>

namespace android {
namespace uirenderer {
namespace debug {

sk_sp<const GrGLInterface> NullGlesDriver::getSkiaInterface() {
    sk_sp<const GrGLInterface> skiaInterface(GrGLCreateNullInterface());
    return skiaInterface;
}

struct {
    GLboolean scissorEnabled;
} gState;

static void nullglGenCommon(GLsizei n, GLuint* buffers) {
    static GLuint nextId = 0;
    int i;
    for (i = 0; i < n; i++) {
        buffers[i] = ++nextId;
    }
}

void NullGlesDriver::glGenBuffers_(GLsizei n, GLuint* buffers) {
    nullglGenCommon(n, buffers);
}

void NullGlesDriver::glGenFramebuffers_(GLsizei n, GLuint* framebuffers) {
    nullglGenCommon(n, framebuffers);
}

void NullGlesDriver::glGenRenderbuffers_(GLsizei n, GLuint* renderbuffers) {
    nullglGenCommon(n, renderbuffers);
}

void NullGlesDriver::glGenTextures_(GLsizei n, GLuint* textures) {
    nullglGenCommon(n, textures);
}

GLuint NullGlesDriver::glCreateProgram_(void) {
    static GLuint nextProgram = 0;
    return ++nextProgram;
}

GLuint NullGlesDriver::glCreateShader_(GLenum type) {
    static GLuint nextShader = 0;
    return ++nextShader;
}

void NullGlesDriver::glGetProgramiv_(GLuint program, GLenum pname, GLint* params) {
    switch (pname) {
        case GL_DELETE_STATUS:
        case GL_LINK_STATUS:
        case GL_VALIDATE_STATUS:
            *params = GL_TRUE;
            break;
        case GL_INFO_LOG_LENGTH:
            *params = 16;
            break;
    }
}

void NullGlesDriver::glGetProgramInfoLog_(GLuint program, GLsizei bufSize, GLsizei* length,
                                          GLchar* infoLog) {
    *length = snprintf(infoLog, bufSize, "success");
    if (*length >= bufSize) {
        *length = bufSize - 1;
    }
}

void NullGlesDriver::glGetShaderiv_(GLuint shader, GLenum pname, GLint* params) {
    switch (pname) {
        case GL_COMPILE_STATUS:
        case GL_DELETE_STATUS:
            *params = GL_TRUE;
    }
}

void NullGlesDriver::glGetShaderInfoLog_(GLuint shader, GLsizei bufSize, GLsizei* length,
                                         GLchar* infoLog) {
    *length = snprintf(infoLog, bufSize, "success");
    if (*length >= bufSize) {
        *length = bufSize - 1;
    }
}

void setBooleanState(GLenum cap, GLboolean value) {
    switch (cap) {
        case GL_SCISSOR_TEST:
            gState.scissorEnabled = value;
            break;
    }
}

void NullGlesDriver::glEnable_(GLenum cap) {
    setBooleanState(cap, GL_TRUE);
}

void NullGlesDriver::glDisable_(GLenum cap) {
    setBooleanState(cap, GL_FALSE);
}

GLboolean NullGlesDriver::glIsEnabled_(GLenum cap) {
    switch (cap) {
        case GL_SCISSOR_TEST:
            return gState.scissorEnabled;
        default:
            return GL_FALSE;
    }
}

void NullGlesDriver::glGetIntegerv_(GLenum pname, GLint* data) {
    switch (pname) {
        case GL_MAX_TEXTURE_SIZE:
            *data = 2048;
            break;
        case GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS:
            *data = 4;
            break;
        default:
            *data = 0;
    }
}

GLenum NullGlesDriver::glCheckFramebufferStatus_(GLenum target) {
    switch (target) {
        case GL_FRAMEBUFFER:
            return GL_FRAMEBUFFER_COMPLETE;
        default:
            return 0;  // error case
    }
}

static const char* getString(GLenum name) {
    switch (name) {
        case GL_VENDOR:
            return "android";
        case GL_RENDERER:
            return "null";
        case GL_VERSION:
            return "OpenGL ES 2.0 rev1";
        case GL_SHADING_LANGUAGE_VERSION:
            return "OpenGL ES GLSL ES 2.0 rev1";
        case GL_EXTENSIONS:
        default:
            return "";
    }
}

const GLubyte* NullGlesDriver::glGetString_(GLenum name) {
    return (GLubyte*)getString(name);
}

}  // namespace debug
}  // namespace uirenderer
}  // namespace android
