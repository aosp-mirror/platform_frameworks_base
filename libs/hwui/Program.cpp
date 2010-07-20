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

#include "shaders/drawColor.vert"
#include "shaders/drawColor.frag"

#include "shaders/drawTexture.vert"
#include "shaders/drawTexture.frag"

#include "shaders/drawLinearGradient.vert"
#include "shaders/drawLinearGradient.frag"

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
        glDeleteProgram(id);
    }

    mUse = false;
}

Program::~Program() {
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);
    glDeleteProgram(id);
}

void Program::use() {
    glUseProgram(id);
    mUse = true;
}

void Program::remove() {
    mUse = false;
}

int Program::addAttrib(const char* name) {
    int slot = glGetAttribLocation(id, name);
    attributes.add(name, slot);
    return slot;
}

int Program::getAttrib(const char* name) {
    return attributes.valueFor(name);
}

int Program::addUniform(const char* name) {
    int slot = glGetUniformLocation(id, name);
    uniforms.add(name, slot);
    return slot;
}

int Program::getUniform(const char* name) {
    return uniforms.valueFor(name);
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

///////////////////////////////////////////////////////////////////////////////
// Draw color
///////////////////////////////////////////////////////////////////////////////

DrawColorProgram::DrawColorProgram():
        Program(gDrawColorVertexShader, gDrawColorFragmentShader) {
    getAttribsAndUniforms();
}

DrawColorProgram::DrawColorProgram(const char* vertex, const char* fragment):
        Program(vertex, fragment) {
    getAttribsAndUniforms();
}

void DrawColorProgram::getAttribsAndUniforms() {
    position = addAttrib("position");
    color = addUniform("color");
    transform = addUniform("transform");
}

void DrawColorProgram::set(const mat4& projectionMatrix, const mat4& modelViewMatrix,
        const mat4& transformMatrix) {
    mat4 t(projectionMatrix);
    t.multiply(transformMatrix);
    t.multiply(modelViewMatrix);

    glUniformMatrix4fv(transform, 1, GL_FALSE, &t.data[0]);
}

void DrawColorProgram::use() {
    Program::use();
    glEnableVertexAttribArray(position);
}

void DrawColorProgram::remove() {
    Program::remove();
    glDisableVertexAttribArray(position);
}

///////////////////////////////////////////////////////////////////////////////
// Draw texture
///////////////////////////////////////////////////////////////////////////////

DrawTextureProgram::DrawTextureProgram():
        DrawColorProgram(gDrawTextureVertexShader, gDrawTextureFragmentShader) {
    texCoords = addAttrib("texCoords");
    sampler = addUniform("sampler");
}

void DrawTextureProgram::use() {
    DrawColorProgram::use();
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(sampler, 0);
    glEnableVertexAttribArray(texCoords);
}

void DrawTextureProgram::remove() {
    DrawColorProgram::remove();
    glDisableVertexAttribArray(texCoords);
}

///////////////////////////////////////////////////////////////////////////////
// Draw linear gradient
///////////////////////////////////////////////////////////////////////////////

DrawLinearGradientProgram::DrawLinearGradientProgram():
        DrawColorProgram(gDrawLinearGradientVertexShader, gDrawLinearGradientFragmentShader) {
    gradient = addUniform("gradient");
    gradientLength = addUniform("gradientLength");
    sampler = addUniform("sampler");
    start = addUniform("start");
    screenSpace = addUniform("screenSpace");
}

void DrawLinearGradientProgram::use() {
    DrawColorProgram::use();
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(sampler, 0);
}

void DrawLinearGradientProgram::remove() {
    DrawColorProgram::remove();
}

}; // namespace uirenderer
}; // namespace android
