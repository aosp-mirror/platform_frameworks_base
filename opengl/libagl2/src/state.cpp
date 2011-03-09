#include "gles2context.h"

GLES2Context::GLES2Context()
{
   memset(this, 0, sizeof *this);

   assert((void *)&rasterizer == &rasterizer.interface);
   InitializeGGLState(&rasterizer.interface);
   iface = &rasterizer.interface;
   printf("gl->rasterizer.PickScanLine(%p) = %p \n", &rasterizer.PickScanLine, rasterizer.PickScanLine);
   assert(rasterizer.PickRaster);
   assert(rasterizer.PickScanLine);

   InitializeTextures();
   InitializeVertices();
}

GLES2Context::~GLES2Context()
{
   UninitializeTextures();
   UninitializeVertices();
   UninitializeGGLState(&rasterizer.interface);
}

void glBlendColor(GLclampf red, GLclampf green, GLclampf blue, GLclampf alpha)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->BlendColor(ctx->iface, red, green, blue, alpha);
}

void glBlendEquation( GLenum mode )
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->BlendEquationSeparate(ctx->iface, mode, mode);
}

void glBlendEquationSeparate(GLenum modeRGB, GLenum modeAlpha)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->BlendEquationSeparate(ctx->iface, modeRGB, modeAlpha);
}

void glBlendFunc(GLenum sfactor, GLenum dfactor)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->BlendFuncSeparate(ctx->iface, sfactor, dfactor, sfactor, dfactor);
}

void glBlendFuncSeparate(GLenum srcRGB, GLenum dstRGB, GLenum srcAlpha, GLenum dstAlpha)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->BlendFuncSeparate(ctx->iface, srcRGB, dstRGB, srcAlpha, dstAlpha);
}

void glClear(GLbitfield mask)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->Clear(ctx->iface, mask);
}

void glClearColor(GLclampf red, GLclampf green, GLclampf blue, GLclampf alpha)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->ClearColor(ctx->iface, red, green, blue, alpha);
}

void glClearDepthf(GLclampf depth)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->ClearDepthf(ctx->iface, depth);
}

void glClearStencil(GLint s)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->ClearStencil(ctx->iface, s);
}

void glCullFace(GLenum mode)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->CullFace(ctx->iface, mode);
}

void glDisable(GLenum cap)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->EnableDisable(ctx->iface, cap, false);
}

void glEnable(GLenum cap)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->EnableDisable(ctx->iface, cap, true);
}

void glFinish(void)
{
   // do nothing
}

void glFrontFace(GLenum mode)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->iface->FrontFace(ctx->iface, mode);
}

void glFlush(void)
{
   // do nothing
}

void glHint(GLenum target, GLenum mode)
{
   // do nothing
}

void glScissor(GLint x, GLint y, GLsizei width, GLsizei height)
{
//   LOGD("agl2: glScissor not implemented x=%d y=%d width=%d height=%d", x, y, width, height);
   //CALL_GL_API(glScissor, x, y, width, height);
}

void glViewport(GLint x, GLint y, GLsizei width, GLsizei height)
{
   GLES2_GET_CONST_CONTEXT(ctx);
//   LOGD("agl2: glViewport x=%d y=%d width=%d height=%d", x, y, width, height);
   ctx->iface->Viewport(ctx->iface, x, y, width, height);
}
