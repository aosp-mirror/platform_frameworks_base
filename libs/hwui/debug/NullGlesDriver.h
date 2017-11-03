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

#pragma once

#include "FatalBaseDriver.h"

namespace android {
namespace uirenderer {
namespace debug {

class NullGlesDriver : public FatalBaseDriver {
public:
    virtual sk_sp<const GrGLInterface> getSkiaInterface() override;

    virtual void glGenBuffers_(GLsizei n, GLuint* buffers) override;
    virtual void glGenFramebuffers_(GLsizei n, GLuint* framebuffers) override;
    virtual void glGenRenderbuffers_(GLsizei n, GLuint* renderbuffers) override;
    virtual void glGenTextures_(GLsizei n, GLuint* textures) override;
    virtual GLuint glCreateProgram_(void) override;
    virtual GLuint glCreateShader_(GLenum type) override;
    virtual void glGetProgramiv_(GLuint program, GLenum pname, GLint* params) override;
    virtual void glGetProgramInfoLog_(GLuint program, GLsizei bufSize, GLsizei* length,
                                      GLchar* infoLog) override;
    virtual void glGetShaderiv_(GLuint shader, GLenum pname, GLint* params) override;
    virtual void glGetShaderInfoLog_(GLuint shader, GLsizei bufSize, GLsizei* length,
                                     GLchar* infoLog) override;
    virtual void glEnable_(GLenum cap) override;
    virtual void glDisable_(GLenum cap) override;
    virtual GLboolean glIsEnabled_(GLenum cap) override;
    virtual void glGetIntegerv_(GLenum pname, GLint* data) override;
    virtual const GLubyte* glGetString_(GLenum name) override;
    virtual GLenum glCheckFramebufferStatus_(GLenum target) override;

    virtual void glActiveTexture_(GLenum texture) override {}
    virtual void glAttachShader_(GLuint program, GLuint shader) override {}
    virtual void glBindAttribLocation_(GLuint program, GLuint index, const GLchar* name) override {}
    virtual void glBindBuffer_(GLenum target, GLuint buffer) override {}
    virtual void glBindFramebuffer_(GLenum target, GLuint framebuffer) override {}
    virtual void glBindRenderbuffer_(GLenum target, GLuint renderbuffer) override {}
    virtual void glBindTexture_(GLenum target, GLuint texture) override {}
    virtual void glBlendColor_(GLfloat red, GLfloat green, GLfloat blue, GLfloat alpha) override {}
    virtual void glBlendEquation_(GLenum mode) override {}
    virtual void glBlendEquationSeparate_(GLenum modeRGB, GLenum modeAlpha) override {}
    virtual void glBlendFunc_(GLenum sfactor, GLenum dfactor) override {}
    virtual void glBlendFuncSeparate_(GLenum sfactorRGB, GLenum dfactorRGB, GLenum sfactorAlpha,
                                      GLenum dfactorAlpha) override {}
    virtual void glBufferData_(GLenum target, GLsizeiptr size, const void* data,
                               GLenum usage) override {}
    virtual void glBufferSubData_(GLenum target, GLintptr offset, GLsizeiptr size,
                                  const void* data) override {}
    virtual void glClear_(GLbitfield mask) override {}
    virtual void glClearColor_(GLfloat red, GLfloat green, GLfloat blue, GLfloat alpha) override {}
    virtual void glClearDepthf_(GLfloat d) override {}
    virtual void glClearStencil_(GLint s) override {}
    virtual void glColorMask_(GLboolean red, GLboolean green, GLboolean blue,
                              GLboolean alpha) override {}
    virtual void glCompileShader_(GLuint shader) override {}
    virtual void glCompressedTexImage2D_(GLenum target, GLint level, GLenum internalformat,
                                         GLsizei width, GLsizei height, GLint border,
                                         GLsizei imageSize, const void* data) override {}
    virtual void glCompressedTexSubImage2D_(GLenum target, GLint level, GLint xoffset,
                                            GLint yoffset, GLsizei width, GLsizei height,
                                            GLenum format, GLsizei imageSize,
                                            const void* data) override {}
    virtual void glCopyTexImage2D_(GLenum target, GLint level, GLenum internalformat, GLint x,
                                   GLint y, GLsizei width, GLsizei height, GLint border) override {}
    virtual void glCopyTexSubImage2D_(GLenum target, GLint level, GLint xoffset, GLint yoffset,
                                      GLint x, GLint y, GLsizei width, GLsizei height) override {}
    virtual void glCullFace_(GLenum mode) override {}
    virtual void glDeleteBuffers_(GLsizei n, const GLuint* buffers) override {}
    virtual void glDeleteFramebuffers_(GLsizei n, const GLuint* framebuffers) override {}
    virtual void glDeleteProgram_(GLuint program) override {}
    virtual void glDeleteRenderbuffers_(GLsizei n, const GLuint* renderbuffers) override {}
    virtual void glDeleteShader_(GLuint shader) override {}
    virtual void glDeleteTextures_(GLsizei n, const GLuint* textures) override {}
    virtual void glDepthFunc_(GLenum func) override {}
    virtual void glDepthMask_(GLboolean flag) override {}
    virtual void glDepthRangef_(GLfloat n, GLfloat f) override {}
    virtual void glDetachShader_(GLuint program, GLuint shader) override {}
    virtual void glDisableVertexAttribArray_(GLuint index) override {}
    virtual void glDrawArrays_(GLenum mode, GLint first, GLsizei count) override {}
    virtual void glDrawElements_(GLenum mode, GLsizei count, GLenum type,
                                 const void* indices) override {}
    virtual void glEnableVertexAttribArray_(GLuint index) override {}
    virtual void glFinish_(void) override {}
    virtual void glFlush_(void) override {}
    virtual void glFramebufferRenderbuffer_(GLenum target, GLenum attachment,
                                            GLenum renderbuffertarget,
                                            GLuint renderbuffer) override {}
    virtual void glFramebufferTexture2D_(GLenum target, GLenum attachment, GLenum textarget,
                                         GLuint texture, GLint level) override {}
    virtual void glFrontFace_(GLenum mode) override {}
    virtual void glGenerateMipmap_(GLenum target) override {}
    virtual GLint glGetAttribLocation_(GLuint program, const GLchar* name) override { return 1; }
    virtual GLenum glGetError_(void) override { return GL_NO_ERROR; }
    virtual GLint glGetUniformLocation_(GLuint program, const GLchar* name) override { return 2; }
    virtual void glHint_(GLenum target, GLenum mode) override {}
    virtual void glLineWidth_(GLfloat width) override {}
    virtual void glLinkProgram_(GLuint program) override {}
    virtual void glPixelStorei_(GLenum pname, GLint param) override {}
    virtual void glPolygonOffset_(GLfloat factor, GLfloat units) override {}
    virtual void glReadPixels_(GLint x, GLint y, GLsizei width, GLsizei height, GLenum format,
                               GLenum type, void* pixels) override {}
    virtual void glReleaseShaderCompiler_(void) override {}
    virtual void glRenderbufferStorage_(GLenum target, GLenum internalformat, GLsizei width,
                                        GLsizei height) override {}
    virtual void glSampleCoverage_(GLfloat value, GLboolean invert) override {}
    virtual void glScissor_(GLint x, GLint y, GLsizei width, GLsizei height) override {}
    virtual void glShaderBinary_(GLsizei count, const GLuint* shaders, GLenum binaryformat,
                                 const void* binary, GLsizei length) override {}
    virtual void glShaderSource_(GLuint shader, GLsizei count, const GLchar* const* string,
                                 const GLint* length) override {}
    virtual void glStencilFunc_(GLenum func, GLint ref, GLuint mask) override {}
    virtual void glStencilFuncSeparate_(GLenum face, GLenum func, GLint ref, GLuint mask) override {
    }
    virtual void glStencilMask_(GLuint mask) override {}
    virtual void glStencilMaskSeparate_(GLenum face, GLuint mask) override {}
    virtual void glStencilOp_(GLenum fail, GLenum zfail, GLenum zpass) override {}
    virtual void glStencilOpSeparate_(GLenum face, GLenum sfail, GLenum dpfail,
                                      GLenum dppass) override {}
    virtual void glTexImage2D_(GLenum target, GLint level, GLint internalformat, GLsizei width,
                               GLsizei height, GLint border, GLenum format, GLenum type,
                               const void* pixels) override {}
    virtual void glTexParameterf_(GLenum target, GLenum pname, GLfloat param) override {}
    virtual void glTexParameterfv_(GLenum target, GLenum pname, const GLfloat* params) override {}
    virtual void glTexParameteri_(GLenum target, GLenum pname, GLint param) override {}
    virtual void glTexParameteriv_(GLenum target, GLenum pname, const GLint* params) override {}
    virtual void glTexSubImage2D_(GLenum target, GLint level, GLint xoffset, GLint yoffset,
                                  GLsizei width, GLsizei height, GLenum format, GLenum type,
                                  const void* pixels) override {}
    virtual void glUniform1f_(GLint location, GLfloat v0) override {}
    virtual void glUniform1fv_(GLint location, GLsizei count, const GLfloat* value) override {}
    virtual void glUniform1i_(GLint location, GLint v0) override {}
    virtual void glUniform1iv_(GLint location, GLsizei count, const GLint* value) override {}
    virtual void glUniform2f_(GLint location, GLfloat v0, GLfloat v1) override {}
    virtual void glUniform2fv_(GLint location, GLsizei count, const GLfloat* value) override {}
    virtual void glUniform2i_(GLint location, GLint v0, GLint v1) override {}
    virtual void glUniform2iv_(GLint location, GLsizei count, const GLint* value) override {}
    virtual void glUniform3f_(GLint location, GLfloat v0, GLfloat v1, GLfloat v2) override {}
    virtual void glUniform3fv_(GLint location, GLsizei count, const GLfloat* value) override {}
    virtual void glUniform3i_(GLint location, GLint v0, GLint v1, GLint v2) override {}
    virtual void glUniform3iv_(GLint location, GLsizei count, const GLint* value) override {}
    virtual void glUniform4f_(GLint location, GLfloat v0, GLfloat v1, GLfloat v2,
                              GLfloat v3) override {}
    virtual void glUniform4fv_(GLint location, GLsizei count, const GLfloat* value) override {}
    virtual void glUniform4i_(GLint location, GLint v0, GLint v1, GLint v2, GLint v3) override {}
    virtual void glUniform4iv_(GLint location, GLsizei count, const GLint* value) override {}
    virtual void glUniformMatrix2fv_(GLint location, GLsizei count, GLboolean transpose,
                                     const GLfloat* value) override {}
    virtual void glUniformMatrix3fv_(GLint location, GLsizei count, GLboolean transpose,
                                     const GLfloat* value) override {}
    virtual void glUniformMatrix4fv_(GLint location, GLsizei count, GLboolean transpose,
                                     const GLfloat* value) override {}
    virtual void glUseProgram_(GLuint program) override {}
    virtual void glValidateProgram_(GLuint program) override {}
    virtual void glVertexAttrib1f_(GLuint index, GLfloat x) override {}
    virtual void glVertexAttrib1fv_(GLuint index, const GLfloat* v) override {}
    virtual void glVertexAttrib2f_(GLuint index, GLfloat x, GLfloat y) override {}
    virtual void glVertexAttrib2fv_(GLuint index, const GLfloat* v) override {}
    virtual void glVertexAttrib3f_(GLuint index, GLfloat x, GLfloat y, GLfloat z) override {}
    virtual void glVertexAttrib3fv_(GLuint index, const GLfloat* v) override {}
    virtual void glVertexAttrib4f_(GLuint index, GLfloat x, GLfloat y, GLfloat z,
                                   GLfloat w) override {}
    virtual void glVertexAttrib4fv_(GLuint index, const GLfloat* v) override {}
    virtual void glVertexAttribPointer_(GLuint index, GLint size, GLenum type, GLboolean normalized,
                                        GLsizei stride, const void* pointer) override {}
    virtual void glViewport_(GLint x, GLint y, GLsizei width, GLsizei height) override {}

    // gles2 ext
    virtual void glInsertEventMarkerEXT_(GLsizei length, const GLchar* marker) override {}
    virtual void glPushGroupMarkerEXT_(GLsizei length, const GLchar* marker) override {}
    virtual void glPopGroupMarkerEXT_(void) override {}
    virtual void glDiscardFramebufferEXT_(GLenum target, GLsizei numAttachments,
                                          const GLenum* attachments) override {}
    virtual void glEGLImageTargetTexture2DOES_(GLenum target, GLeglImageOES image) override {}

    // GLES3
    virtual void* glMapBufferRange_(GLenum target, GLintptr offset, GLsizeiptr length,
                                    GLbitfield access) override {
        return 0;
    }

    virtual GLboolean glUnmapBuffer_(GLenum target) override { return GL_FALSE; }
};

}  // namespace debug
}  // namespace uirenderer
}  // namespace android
