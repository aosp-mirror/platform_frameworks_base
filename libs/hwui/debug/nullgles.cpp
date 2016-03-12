/*
 * Copyright(C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0(the "License");
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

#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#include <stdlib.h>
#include <string.h>

struct {
    GLboolean scissorEnabled;
} gState;

void glGenCommon(GLsizei n, GLuint *buffers) {
    static GLuint nextId = 0;
    int i;
    for(i = 0; i < n; i++) {
        buffers[i] = ++nextId;
    }
}

void glGenBuffers(GLsizei n, GLuint *buffers) {
    glGenCommon(n, buffers);
}

void glGenFramebuffers(GLsizei n, GLuint *framebuffers) {
    glGenCommon(n, framebuffers);
}

void glGenRenderbuffers(GLsizei n, GLuint *renderbuffers) {
    glGenCommon(n, renderbuffers);
}

void glGenTextures(GLsizei n, GLuint *textures) {
    glGenCommon(n, textures);
}

GLuint glCreateProgram(void) {
    static GLuint nextProgram = 0;
    return ++nextProgram;
}

GLuint glCreateShader(GLenum type) {
    static GLuint nextShader = 0;
    return ++nextShader;
}

void glGetProgramiv(GLuint program, GLenum pname, GLint *params) {
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

void glGetProgramInfoLog(GLuint program, GLsizei bufSize, GLsizei *length, GLchar *infoLog) {
    *length = snprintf(infoLog, bufSize, "success");
    if (*length >= bufSize) {
        *length = bufSize - 1;
    }
}

void glGetShaderiv(GLuint shader, GLenum pname, GLint *params) {
    switch (pname) {
    case GL_COMPILE_STATUS:
    case GL_DELETE_STATUS:
        *params = GL_TRUE;
    }
}

void glGetShaderInfoLog(GLuint shader, GLsizei bufSize, GLsizei *length, GLchar *infoLog) {
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

void glEnable(GLenum cap) {
    setBooleanState(cap, GL_TRUE);
}

void glDisable(GLenum cap) {
    setBooleanState(cap, GL_FALSE);
}

GLboolean glIsEnabled(GLenum cap) {
    switch (cap) {
    case GL_SCISSOR_TEST:
        return gState.scissorEnabled;
    default:
        return GL_FALSE;
    }
}

void glGetIntegerv(GLenum pname, GLint *data) {
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

GLenum glCheckFramebufferStatus(GLenum target) {
    switch (target) {
    case GL_FRAMEBUFFER:
        return GL_FRAMEBUFFER_COMPLETE;
    default:
        return 0; // error case
    }
}

const char* getString(GLenum name) {
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

const GLubyte* glGetString(GLenum name) {
    return (GLubyte*) getString(name);
}

void glActiveTexture(GLenum texture) {}
void glAttachShader(GLuint program, GLuint shader) {}
void glBindAttribLocation(GLuint program, GLuint index, const GLchar *name) {}
void glBindBuffer(GLenum target, GLuint buffer) {}
void glBindFramebuffer(GLenum target, GLuint framebuffer) {}
void glBindRenderbuffer(GLenum target, GLuint renderbuffer) {}
void glBindTexture(GLenum target, GLuint texture) {}
void glBlendColor(GLfloat red, GLfloat green, GLfloat blue, GLfloat alpha) {}
void glBlendEquation(GLenum mode) {}
void glBlendEquationSeparate(GLenum modeRGB, GLenum modeAlpha) {}
void glBlendFunc(GLenum sfactor, GLenum dfactor) {}
void glBlendFuncSeparate(GLenum sfactorRGB, GLenum dfactorRGB, GLenum sfactorAlpha, GLenum dfactorAlpha) {}
void glBufferData(GLenum target, GLsizeiptr size, const void *data, GLenum usage) {}
void glBufferSubData(GLenum target, GLintptr offset, GLsizeiptr size, const void *data) {}
void glClear(GLbitfield mask) {}
void glClearColor(GLfloat red, GLfloat green, GLfloat blue, GLfloat alpha) {}
void glClearDepthf(GLfloat d) {}
void glClearStencil(GLint s) {}
void glColorMask(GLboolean red, GLboolean green, GLboolean blue, GLboolean alpha) {}
void glCompileShader(GLuint shader) {}
void glCompressedTexImage2D(GLenum target, GLint level, GLenum internalformat, GLsizei width, GLsizei height, GLint border, GLsizei imageSize, const void *data) {}
void glCompressedTexSubImage2D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLsizei imageSize, const void *data) {}
void glCopyTexImage2D(GLenum target, GLint level, GLenum internalformat, GLint x, GLint y, GLsizei width, GLsizei height, GLint border) {}
void glCopyTexSubImage2D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint x, GLint y, GLsizei width, GLsizei height) {}
void glCullFace(GLenum mode) {}
void glDeleteBuffers(GLsizei n, const GLuint *buffers) {}
void glDeleteFramebuffers(GLsizei n, const GLuint *framebuffers) {}
void glDeleteProgram(GLuint program) {}
void glDeleteRenderbuffers(GLsizei n, const GLuint *renderbuffers) {}
void glDeleteShader(GLuint shader) {}
void glDeleteTextures(GLsizei n, const GLuint *textures) {}
void glDepthFunc(GLenum func) {}
void glDepthMask(GLboolean flag) {}
void glDepthRangef(GLfloat n, GLfloat f) {}
void glDetachShader(GLuint program, GLuint shader) {}
void glDisableVertexAttribArray(GLuint index) {}
void glDrawArrays(GLenum mode, GLint first, GLsizei count) {}
void glDrawElements(GLenum mode, GLsizei count, GLenum type, const void *indices) {}
void glEnableVertexAttribArray(GLuint index) {}
void glFinish(void) {}
void glFlush(void) {}
void glFramebufferRenderbuffer(GLenum target, GLenum attachment, GLenum renderbuffertarget, GLuint renderbuffer) {}
void glFramebufferTexture2D(GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level) {}
void glFrontFace(GLenum mode) {}
void glGenerateMipmap(GLenum target) {}
GLint glGetAttribLocation(GLuint program, const GLchar *name) { return 1; }
GLenum glGetError(void) { return GL_NO_ERROR; }
GLint glGetUniformLocation(GLuint program, const GLchar *name) { return 2; }
void glHint(GLenum target, GLenum mode) {}
void glLineWidth(GLfloat width) {}
void glLinkProgram(GLuint program) {}
void glPixelStorei(GLenum pname, GLint param) {}
void glPolygonOffset(GLfloat factor, GLfloat units) {}
void glReadPixels(GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, void *pixels) {}
void glReleaseShaderCompiler(void) {}
void glRenderbufferStorage(GLenum target, GLenum internalformat, GLsizei width, GLsizei height) {}
void glSampleCoverage(GLfloat value, GLboolean invert) {}
void glScissor(GLint x, GLint y, GLsizei width, GLsizei height) {}
void glShaderBinary(GLsizei count, const GLuint *shaders, GLenum binaryformat, const void *binary, GLsizei length) {}
void glShaderSource(GLuint shader, GLsizei count, const GLchar *const*string, const GLint *length) {}
void glStencilFunc(GLenum func, GLint ref, GLuint mask) {}
void glStencilFuncSeparate(GLenum face, GLenum func, GLint ref, GLuint mask) {}
void glStencilMask(GLuint mask) {}
void glStencilMaskSeparate(GLenum face, GLuint mask) {}
void glStencilOp(GLenum fail, GLenum zfail, GLenum zpass) {}
void glStencilOpSeparate(GLenum face, GLenum sfail, GLenum dpfail, GLenum dppass) {}
void glTexImage2D(GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLint border, GLenum format, GLenum type, const void *pixels) {}
void glTexParameterf(GLenum target, GLenum pname, GLfloat param) {}
void glTexParameterfv(GLenum target, GLenum pname, const GLfloat *params) {}
void glTexParameteri(GLenum target, GLenum pname, GLint param) {}
void glTexParameteriv(GLenum target, GLenum pname, const GLint *params) {}
void glTexSubImage2D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const void *pixels) {}
void glUniform1f(GLint location, GLfloat v0) {}
void glUniform1fv(GLint location, GLsizei count, const GLfloat *value) {}
void glUniform1i(GLint location, GLint v0) {}
void glUniform1iv(GLint location, GLsizei count, const GLint *value) {}
void glUniform2f(GLint location, GLfloat v0, GLfloat v1) {}
void glUniform2fv(GLint location, GLsizei count, const GLfloat *value) {}
void glUniform2i(GLint location, GLint v0, GLint v1) {}
void glUniform2iv(GLint location, GLsizei count, const GLint *value) {}
void glUniform3f(GLint location, GLfloat v0, GLfloat v1, GLfloat v2) {}
void glUniform3fv(GLint location, GLsizei count, const GLfloat *value) {}
void glUniform3i(GLint location, GLint v0, GLint v1, GLint v2) {}
void glUniform3iv(GLint location, GLsizei count, const GLint *value) {}
void glUniform4f(GLint location, GLfloat v0, GLfloat v1, GLfloat v2, GLfloat v3) {}
void glUniform4fv(GLint location, GLsizei count, const GLfloat *value) {}
void glUniform4i(GLint location, GLint v0, GLint v1, GLint v2, GLint v3) {}
void glUniform4iv(GLint location, GLsizei count, const GLint *value) {}
void glUniformMatrix2fv(GLint location, GLsizei count, GLboolean transpose, const GLfloat *value) {}
void glUniformMatrix3fv(GLint location, GLsizei count, GLboolean transpose, const GLfloat *value) {}
void glUniformMatrix4fv(GLint location, GLsizei count, GLboolean transpose, const GLfloat *value) {}
void glUseProgram(GLuint program) {}
void glValidateProgram(GLuint program) {}
void glVertexAttrib1f(GLuint index, GLfloat x) {}
void glVertexAttrib1fv(GLuint index, const GLfloat *v) {}
void glVertexAttrib2f(GLuint index, GLfloat x, GLfloat y) {}
void glVertexAttrib2fv(GLuint index, const GLfloat *v) {}
void glVertexAttrib3f(GLuint index, GLfloat x, GLfloat y, GLfloat z) {}
void glVertexAttrib3fv(GLuint index, const GLfloat *v) {}
void glVertexAttrib4f(GLuint index, GLfloat x, GLfloat y, GLfloat z, GLfloat w) {}
void glVertexAttrib4fv(GLuint index, const GLfloat *v) {}
void glVertexAttribPointer(GLuint index, GLint size, GLenum type, GLboolean normalized, GLsizei stride, const void *pointer) {}
void glViewport(GLint x, GLint y, GLsizei width, GLsizei height) {}


// gles2 ext
void glInsertEventMarkerEXT(GLsizei length, const GLchar *marker) {}
void glPushGroupMarkerEXT(GLsizei length, const GLchar *marker) {}
void glPopGroupMarkerEXT(void) {}
void glDiscardFramebufferEXT(GLenum target, GLsizei numAttachments, const GLenum *attachments) {}
void glEGLImageTargetTexture2DOES(GLenum target, GLeglImageOES image) {}

// GLES3
void* glMapBufferRange(GLenum target, GLintptr offset, GLsizeiptr length, GLbitfield access) {
    return 0;
}

GLboolean glUnmapBuffer(GLenum target) {
    return GL_FALSE;
}
