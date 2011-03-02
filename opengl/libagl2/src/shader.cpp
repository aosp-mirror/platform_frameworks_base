#include "gles2context.h"

//#undef LOGD
//#define LOGD(...)

static inline GLuint s2n(gl_shader * s)
{
   return (GLuint)s ^ 0xaf3c532d;
}

static inline gl_shader * n2s(GLuint n)
{
   return (gl_shader *)(n ^ 0xaf3c532d);
}

static inline GLuint p2n(gl_shader_program * p)
{
   return (GLuint)p ^ 0x04dc18f9;
}

static inline gl_shader_program * n2p(GLuint n)
{
   return (gl_shader_program *)(n ^ 0x04dc18f9);
}

void glAttachShader(GLuint program, GLuint shader)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->ShaderAttach(ctx->iface, n2p(program), n2s(shader));
}

void glBindAttribLocation(GLuint program, GLuint index, const GLchar* name)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->ShaderAttributeBind(n2p(program), index, name);
//   assert(0);
}

GLuint glCreateShader(GLenum type)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   return s2n(ctx->iface->ShaderCreate(ctx->iface, type));
}

GLuint glCreateProgram(void)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   return  p2n(ctx->iface->ShaderProgramCreate(ctx->iface));
}

void glCompileShader(GLuint shader)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->ShaderCompile(ctx->iface, n2s(shader), NULL, NULL);
}

void glDeleteProgram(GLuint program)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->ShaderProgramDelete(ctx->iface, n2p(program));
}

void glDeleteShader(GLuint shader)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->ShaderDelete(ctx->iface, n2s(shader));
}

void glDetachShader(GLuint program, GLuint shader)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->ShaderDetach(ctx->iface, n2p(program), n2s(shader));
}

GLint glGetAttribLocation(GLuint program, const GLchar* name)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   GLint location = ctx->iface->ShaderAttributeLocation(n2p(program), name);
//   LOGD("\n*\n*\n* agl2: glGetAttribLocation program=%u name=%s location=%d \n*\n*",
//        program, name, location);
   return location;
}

void glGetProgramiv(GLuint program, GLenum pname, GLint* params)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->ShaderProgramGetiv(n2p(program), pname, params);
   LOGD("agl2: glGetProgramiv 0x%.4X=%d \n", pname, *params);
}

void glGetProgramInfoLog(GLuint program, GLsizei bufsize, GLsizei* length, GLchar* infolog)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->ShaderProgramGetInfoLog(n2p(program), bufsize, length, infolog);
}

void glGetShaderiv(GLuint shader, GLenum pname, GLint* params)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->ShaderGetiv(n2s(shader), pname, params);
   LOGD("agl2: glGetShaderiv 0x%.4X=%d \n", pname, *params);
}

void glGetShaderInfoLog(GLuint shader, GLsizei bufsize, GLsizei* length, GLchar* infolog)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->ShaderGetInfoLog(n2s(shader), bufsize, length, infolog);
}

int glGetUniformLocation(GLuint program, const GLchar* name)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   return ctx->iface->ShaderUniformLocation(n2p(program), name);
}

void glLinkProgram(GLuint program)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   GLboolean linked = ctx->iface->ShaderProgramLink(n2p(program), NULL);
   assert(linked);
}

void glShaderSource(GLuint shader, GLsizei count, const GLchar** string, const GLint* length)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->ShaderSource(n2s(shader), count, string, length);
}

void glUniform1f(GLint location, GLfloat x)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   int sampler = ctx->iface->ShaderUniform(ctx->rasterizer.CurrentProgram, location, 1, &x, GL_FLOAT);
   assert(0 > sampler); // should be assigning to sampler
}

void glUniform1i(GLint location, GLint x)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   const float params[1] = {x};
   int sampler = ctx->iface->ShaderUniform(ctx->rasterizer.CurrentProgram, location, 1, params, GL_INT);
   if (0 <= sampler) {
//      LOGD("\n*\n* agl2: glUniform1i updated sampler=%d tmu=%d location=%d\n*", sampler, x, location);
      assert(0 <= x && GGL_MAXCOMBINEDTEXTUREIMAGEUNITS > x);
//      LOGD("tmu%u: format=0x%.2X w=%u h=%u levels=%p", x, ctx->tex.tmus[x]->format, 
//         ctx->tex.tmus[x]->width, ctx->tex.tmus[x]->height, ctx->tex.tmus[x]->format);
      ctx->tex.sampler2tmu[sampler] = x;
      ctx->tex.UpdateSampler(ctx->iface, x);
   }
}

void glUniform2f(GLint location, GLfloat x, GLfloat y)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   const float params[4] = {x, y};
   ctx->iface->ShaderUniform(ctx->rasterizer.CurrentProgram, location, 1, params, GL_FLOAT_VEC2);
}

void glUniform4f(GLint location, GLfloat x, GLfloat y, GLfloat z, GLfloat w)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   const float params[4] = {x, y, z, w};
//   LOGD("agl2: glUniform4f location=%d %f,%f,%f,%f", location, x, y, z, w);
   ctx->iface->ShaderUniform(ctx->rasterizer.CurrentProgram, location, 1, params, GL_FLOAT_VEC4);
}

void glUniformMatrix4fv(GLint location, GLsizei count, GLboolean transpose, const GLfloat* value)
{
   GLES2_GET_CONST_CONTEXT(ctx);
//   const gl_shader_program * program = ctx->rasterizer.CurrentProgram;
//   if (strstr(program->Shaders[MESA_SHADER_FRAGMENT]->Source, ").a;")) {
//   LOGD("agl2: glUniformMatrix4fv location=%d count=%d transpose=%d", location, count, transpose);
//   for (unsigned i = 0; i < 4; i++)
//      LOGD("agl2: glUniformMatrix4fv %.2f \t %.2f \t %.2f \t %.2f", value[i * 4 + 0],
//           value[i * 4 + 1], value[i * 4 + 2], value[i * 4 + 3]);
//   }
   ctx->iface->ShaderUniformMatrix(ctx->rasterizer.CurrentProgram, 4, 4, location, count, transpose, value);
//   while (true)
//      ;
//   assert(0);
}

void glUseProgram(GLuint program)
{
   GLES2_GET_CONST_CONTEXT(ctx);
//   LOGD("\n*\n*\n* agl2: glUseProgram %d \n*\n*\n*", program);
   ctx->iface->ShaderUse(ctx->iface, n2p(program));
   ctx->iface->ShaderUniformGetSamplers(n2p(program), ctx->tex.sampler2tmu);
   for (unsigned i = 0; i < GGL_MAXCOMBINEDTEXTUREIMAGEUNITS; i++)
      if (0 <= ctx->tex.sampler2tmu[i])
         ctx->iface->SetSampler(ctx->iface, i, ctx->tex.tmus[ctx->tex.sampler2tmu[i]]);
}
