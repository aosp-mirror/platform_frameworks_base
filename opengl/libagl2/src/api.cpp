#include "gles2context.h"

#define API_ENTRY
#define CALL_GL_API(NAME,...) LOGD("?"#NAME); assert(0);
#define CALL_GL_API_RETURN(NAME,...) LOGD("?"#NAME); assert(0); return 0;


void API_ENTRY(glBindFramebuffer)(GLenum target, GLuint framebuffer)
{
   CALL_GL_API(glBindFramebuffer, target, framebuffer);
}
void API_ENTRY(glBindRenderbuffer)(GLenum target, GLuint renderbuffer)
{
   CALL_GL_API(glBindRenderbuffer, target, renderbuffer);
}
GLenum API_ENTRY(glCheckFramebufferStatus)(GLenum target)
{
   CALL_GL_API_RETURN(glCheckFramebufferStatus, target);
}
void API_ENTRY(glColorMask)(GLboolean red, GLboolean green, GLboolean blue, GLboolean alpha)
{
   CALL_GL_API(glColorMask, red, green, blue, alpha);
}
void API_ENTRY(glDeleteFramebuffers)(GLsizei n, const GLuint* framebuffers)
{
   CALL_GL_API(glDeleteFramebuffers, n, framebuffers);
}
void API_ENTRY(glDeleteRenderbuffers)(GLsizei n, const GLuint* renderbuffers)
{
   CALL_GL_API(glDeleteRenderbuffers, n, renderbuffers);
}
void API_ENTRY(glDepthFunc)(GLenum func)
{
   CALL_GL_API(glDepthFunc, func);
}
void API_ENTRY(glDepthMask)(GLboolean flag)
{
   CALL_GL_API(glDepthMask, flag);
}
void API_ENTRY(glDepthRangef)(GLclampf zNear, GLclampf zFar)
{
   CALL_GL_API(glDepthRangef, zNear, zFar);
}
void API_ENTRY(glFramebufferRenderbuffer)(GLenum target, GLenum attachment, GLenum renderbuffertarget, GLuint renderbuffer)
{
   CALL_GL_API(glFramebufferRenderbuffer, target, attachment, renderbuffertarget, renderbuffer);
}
void API_ENTRY(glFramebufferTexture2D)(GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level)
{
   CALL_GL_API(glFramebufferTexture2D, target, attachment, textarget, texture, level);
}
void glGenerateMipmap(GLenum target)
{
   //CALL_GL_API(glGenerateMipmap, target);
   LOGD("agl2: glGenerateMipmap not implemented");
}
void API_ENTRY(glGenFramebuffers)(GLsizei n, GLuint* framebuffers)
{
   CALL_GL_API(glGenFramebuffers, n, framebuffers);
}
void API_ENTRY(glGenRenderbuffers)(GLsizei n, GLuint* renderbuffers)
{
   CALL_GL_API(glGenRenderbuffers, n, renderbuffers);
}
void API_ENTRY(glGetActiveAttrib)(GLuint program, GLuint index, GLsizei bufsize, GLsizei* length, GLint* size, GLenum* type, GLchar* name)
{
   CALL_GL_API(glGetActiveAttrib, program, index, bufsize, length, size, type, name);
}
void API_ENTRY(glGetActiveUniform)(GLuint program, GLuint index, GLsizei bufsize, GLsizei* length, GLint* size, GLenum* type, GLchar* name)
{
   CALL_GL_API(glGetActiveUniform, program, index, bufsize, length, size, type, name);
}
void API_ENTRY(glGetAttachedShaders)(GLuint program, GLsizei maxcount, GLsizei* count, GLuint* shaders)
{
   CALL_GL_API(glGetAttachedShaders, program, maxcount, count, shaders);
}
void API_ENTRY(glGetBooleanv)(GLenum pname, GLboolean* params)
{
   CALL_GL_API(glGetBooleanv, pname, params);
}
void API_ENTRY(glGetBufferParameteriv)(GLenum target, GLenum pname, GLint* params)
{
   CALL_GL_API(glGetBufferParameteriv, target, pname, params);
}
GLenum glGetError(void)
{
   puts("agl2: glGetError");
   return GL_NO_ERROR;
   //CALL_GL_API_RETURN(glGetError);
}
void API_ENTRY(glGetFloatv)(GLenum pname, GLfloat* params)
{
   CALL_GL_API(glGetFloatv, pname, params);
}
void API_ENTRY(glGetFramebufferAttachmentParameteriv)(GLenum target, GLenum attachment, GLenum pname, GLint* params)
{
   CALL_GL_API(glGetFramebufferAttachmentParameteriv, target, attachment, pname, params);
}
void API_ENTRY(glGetRenderbufferParameteriv)(GLenum target, GLenum pname, GLint* params)
{
   CALL_GL_API(glGetRenderbufferParameteriv, target, pname, params);
}
void API_ENTRY(glGetShaderPrecisionFormat)(GLenum shadertype, GLenum precisiontype, GLint* range, GLint* precision)
{
   CALL_GL_API(glGetShaderPrecisionFormat, shadertype, precisiontype, range, precision);
}
void API_ENTRY(glGetShaderSource)(GLuint shader, GLsizei bufsize, GLsizei* length, GLchar* source)
{
   CALL_GL_API(glGetShaderSource, shader, bufsize, length, source);
}
void API_ENTRY(glGetUniformfv)(GLuint program, GLint location, GLfloat* params)
{
   CALL_GL_API(glGetUniformfv, program, location, params);
}
void API_ENTRY(glGetUniformiv)(GLuint program, GLint location, GLint* params)
{
   CALL_GL_API(glGetUniformiv, program, location, params);
}
void API_ENTRY(glGetVertexAttribfv)(GLuint index, GLenum pname, GLfloat* params)
{
   CALL_GL_API(glGetVertexAttribfv, index, pname, params);
}
void API_ENTRY(glGetVertexAttribiv)(GLuint index, GLenum pname, GLint* params)
{
   CALL_GL_API(glGetVertexAttribiv, index, pname, params);
}
void API_ENTRY(glGetVertexAttribPointerv)(GLuint index, GLenum pname, GLvoid** pointer)
{
   CALL_GL_API(glGetVertexAttribPointerv, index, pname, pointer);
}
GLboolean API_ENTRY(glIsBuffer)(GLuint buffer)
{
   CALL_GL_API_RETURN(glIsBuffer, buffer);
}
GLboolean API_ENTRY(glIsEnabled)(GLenum cap)
{
   CALL_GL_API_RETURN(glIsEnabled, cap);
}
GLboolean API_ENTRY(glIsFramebuffer)(GLuint framebuffer)
{
   CALL_GL_API_RETURN(glIsFramebuffer, framebuffer);
}
GLboolean API_ENTRY(glIsProgram)(GLuint program)
{
   CALL_GL_API_RETURN(glIsProgram, program);
}
GLboolean API_ENTRY(glIsRenderbuffer)(GLuint renderbuffer)
{
   CALL_GL_API_RETURN(glIsRenderbuffer, renderbuffer);
}
GLboolean API_ENTRY(glIsShader)(GLuint shader)
{
   CALL_GL_API_RETURN(glIsShader, shader);
}
void API_ENTRY(glLineWidth)(GLfloat width)
{
   CALL_GL_API(glLineWidth, width);
}
void API_ENTRY(glPolygonOffset)(GLfloat factor, GLfloat units)
{
   CALL_GL_API(glPolygonOffset, factor, units);
}
void API_ENTRY(glReadPixels)(GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, GLvoid* pixels)
{
   CALL_GL_API(glReadPixels, x, y, width, height, format, type, pixels);
}
void API_ENTRY(glReleaseShaderCompiler)(void)
{
   CALL_GL_API(glReleaseShaderCompiler);
}
void API_ENTRY(glRenderbufferStorage)(GLenum target, GLenum internalformat, GLsizei width, GLsizei height)
{
   CALL_GL_API(glRenderbufferStorage, target, internalformat, width, height);
}
void API_ENTRY(glSampleCoverage)(GLclampf value, GLboolean invert)
{
   CALL_GL_API(glSampleCoverage, value, invert);
}
void API_ENTRY(glShaderBinary)(GLsizei n, const GLuint* shaders, GLenum binaryformat, const GLvoid* binary, GLsizei length)
{
   CALL_GL_API(glShaderBinary, n, shaders, binaryformat, binary, length);
}
void API_ENTRY(glStencilFunc)(GLenum func, GLint ref, GLuint mask)
{
   CALL_GL_API(glStencilFunc, func, ref, mask);
}
void API_ENTRY(glStencilFuncSeparate)(GLenum face, GLenum func, GLint ref, GLuint mask)
{
   CALL_GL_API(glStencilFuncSeparate, face, func, ref, mask);
}
void API_ENTRY(glStencilMask)(GLuint mask)
{
   CALL_GL_API(glStencilMask, mask);
}
void API_ENTRY(glStencilMaskSeparate)(GLenum face, GLuint mask)
{
   CALL_GL_API(glStencilMaskSeparate, face, mask);
}
void API_ENTRY(glStencilOp)(GLenum fail, GLenum zfail, GLenum zpass)
{
   CALL_GL_API(glStencilOp, fail, zfail, zpass);
}
void API_ENTRY(glStencilOpSeparate)(GLenum face, GLenum fail, GLenum zfail, GLenum zpass)
{
   CALL_GL_API(glStencilOpSeparate, face, fail, zfail, zpass);
}
void API_ENTRY(glUniform1fv)(GLint location, GLsizei count, const GLfloat* v)
{
   CALL_GL_API(glUniform1fv, location, count, v);
}
void API_ENTRY(glUniform1iv)(GLint location, GLsizei count, const GLint* v)
{
   CALL_GL_API(glUniform1iv, location, count, v);
}
void API_ENTRY(glUniform2fv)(GLint location, GLsizei count, const GLfloat* v)
{
   CALL_GL_API(glUniform2fv, location, count, v);
}
void API_ENTRY(glUniform2i)(GLint location, GLint x, GLint y)
{
   CALL_GL_API(glUniform2i, location, x, y);
}
void API_ENTRY(glUniform2iv)(GLint location, GLsizei count, const GLint* v)
{
   CALL_GL_API(glUniform2iv, location, count, v);
}
void API_ENTRY(glUniform3f)(GLint location, GLfloat x, GLfloat y, GLfloat z)
{
   CALL_GL_API(glUniform3f, location, x, y, z);
}
void API_ENTRY(glUniform3fv)(GLint location, GLsizei count, const GLfloat* v)
{
   CALL_GL_API(glUniform3fv, location, count, v);
}
void API_ENTRY(glUniform3i)(GLint location, GLint x, GLint y, GLint z)
{
   CALL_GL_API(glUniform3i, location, x, y, z);
}
void API_ENTRY(glUniform3iv)(GLint location, GLsizei count, const GLint* v)
{
   CALL_GL_API(glUniform3iv, location, count, v);
}
void API_ENTRY(glUniform4fv)(GLint location, GLsizei count, const GLfloat* v)
{
   CALL_GL_API(glUniform4fv, location, count, v);
}
void API_ENTRY(glUniform4i)(GLint location, GLint x, GLint y, GLint z, GLint w)
{
   CALL_GL_API(glUniform4i, location, x, y, z, w);
}
void API_ENTRY(glUniform4iv)(GLint location, GLsizei count, const GLint* v)
{
   CALL_GL_API(glUniform4iv, location, count, v);
}
void API_ENTRY(glUniformMatrix2fv)(GLint location, GLsizei count, GLboolean transpose, const GLfloat* value)
{
   CALL_GL_API(glUniformMatrix2fv, location, count, transpose, value);
}
void API_ENTRY(glUniformMatrix3fv)(GLint location, GLsizei count, GLboolean transpose, const GLfloat* value)
{
   CALL_GL_API(glUniformMatrix3fv, location, count, transpose, value);
}
void API_ENTRY(glValidateProgram)(GLuint program)
{
   CALL_GL_API(glValidateProgram, program);
}
