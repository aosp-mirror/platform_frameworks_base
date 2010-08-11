/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include "Program.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Shaders
///////////////////////////////////////////////////////////////////////////////

#define SHADER_SOURCE(name, source) const char* name = #source

///////////////////////////////////////////////////////////////////////////////
// Base program
///////////////////////////////////////////////////////////////////////////////

Program::Program(const char* vertex, const char* fragment) {
    vertexShader = buildShader(vertex, GL_VERTEX_SHADER);
    fragmentShader = buildShader(fragment, GL_FRAGMENT_SHADER);

    id = glCreateProgram();
    glAttachShader(id, vertexShader);
    glAttachShader(id, fragmentShader);
    glLinkProgram(id);

    GLint status;
    glGetProgramiv(id, GL_LINK_STATUS, &status);
    if (status != GL_TRUE) {
        GLint infoLen = 0;
        glGetProgramiv(id, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* log = (char*) malloc(sizeof(char) * infoLen);
            glGetProgramInfoLog(id, infoLen, 0, log);
            LOGE("Error while linking shaders: %s", log);
            delete log;
        }
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        glDeleteProgram(id);
    }

    mUse = false;

    position = addAttrib("position");
    color = addUniform("color");
    transform = addUniform("transform");
}

Program::~Program() {
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);
    glDeleteProgram(id);
}

int Program::addAttrib(const char* name) {
    int slot = glGetAttribLocation(id, name);
    attributes.add(name, slot);
    return slot;
}

int Program::getAttrib(const char* name) {
    ssize_t index = attributes.indexOfKey(name);
    if (index >= 0) {
        return attributes.valueAt(index);
    }
    return addAttrib(name);
}

int Program::addUniform(const char* name) {
    int slot = glGetUniformLocation(id, name);
    uniforms.add(name, slot);
    return slot;
}

int Program::getUniform(const char* name) {
    ssize_t index = uniforms.indexOfKey(name);
    if (index >= 0) {
        return uniforms.valueAt(index);
    }
    return addUniform(name);
}

GLuint Program::buildShader(const char* source, GLenum type) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, 0);
    glCompileShader(shader);

    GLint status;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status != GL_TRUE) {
        // Some drivers return wrong values for GL_INFO_LOG_LENGTH
        // use a fixed size instead
        GLchar log[512];
        glGetShaderInfoLog(shader, sizeof(log), 0, &log[0]);
        LOGE("Error while compiling shader: %s", log);
        glDeleteShader(shader);
    }

    return shader;
}

void Program::set(const mat4& projectionMatrix, const mat4& modelViewMatrix,
        const mat4& transformMatrix) {
    mat4 t(projectionMatrix);
    t.multiply(transformMatrix);
    t.multiply(modelViewMatrix);

    glUniformMatrix4fv(transform, 1, GL_FALSE, &t.data[0]);
}

void Program::use() {
    glUseProgram(id);
    mUse = true;

    glEnableVertexAttribArray(position);
}

void Program::remove() {
    mUse = false;

    glDisableVertexAttribArray(position);
}

}; // namespace uirenderer
}; // namespace android
