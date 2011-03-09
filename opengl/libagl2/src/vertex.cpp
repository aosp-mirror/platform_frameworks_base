#include "gles2context.h"

//#undef LOGD
//#define LOGD(...)

void GLES2Context::InitializeVertices()
{
   vert.vbos = std::map<GLuint, VBO *>(); // the entire struct has been zeroed in constructor
   vert.free = 1;
   vert.vbo = NULL;
   vert.indices = NULL;
   for (unsigned i = 0; i < GGL_MAXVERTEXATTRIBS; i++)
      vert.defaultAttribs[i] = Vector4(0,0,0,1);
}

void GLES2Context::UninitializeVertices()
{
   for (std::map<GLuint, VBO *>::iterator it = vert.vbos.begin(); it != vert.vbos.end(); it++) {
      if (!it->second)
         continue;
      free(it->second->data);
      free(it->second);
   }
}

static inline void FetchElement(const GLES2Context * ctx, const unsigned index,
                                const unsigned maxAttrib, VertexInput * elem)
{
   for (unsigned i = 0; i < maxAttrib; i++) {
      {
         unsigned size = 0;
         if (ctx->vert.attribs[i].enabled) {
            const char * ptr = (const char *)ctx->vert.attribs[i].ptr;
            ptr += ctx->vert.attribs[i].stride * index;
            memcpy(elem->attributes + i, ptr, ctx->vert.attribs[i].size * sizeof(float));
            size = ctx->vert.attribs[i].size;
//            LOGD("agl2: FetchElement %d attribs size=%d %.2f,%.2f,%.2f,%.2f", i, size, elem->attributes[i].x,
//                 elem->attributes[i].y, elem->attributes[i].z, elem->attributes[i].w);
         } else {
//            LOGD("agl2: FetchElement %d default %.2f,%.2f,%.2f,%.2f", i, ctx->vert.defaultAttribs[i].x,
//                 ctx->vert.defaultAttribs[i].y, ctx->vert.defaultAttribs[i].z, ctx->vert.defaultAttribs[i].w);
         }

         switch (size) {
         case 0: // fall through
            elem->attributes[i].x = ctx->vert.defaultAttribs[i].x;
         case 1: // fall through
            elem->attributes[i].y = ctx->vert.defaultAttribs[i].y;
         case 2: // fall through
            elem->attributes[i].z = ctx->vert.defaultAttribs[i].z;
         case 3: // fall through
            elem->attributes[i].w = ctx->vert.defaultAttribs[i].w;
         case 4:
            break;
         default:
            assert(0);
            break;
         }
//         LOGD("agl2: FetchElement %d size=%d %.2f,%.2f,%.2f,%.2f", i, size, elem->attributes[i].x,
//              elem->attributes[i].y, elem->attributes[i].z, elem->attributes[i].w);
      }
   }
}

template<typename IndexT> static void DrawElementsTriangles(const GLES2Context * ctx,
      const unsigned count, const IndexT * indices, const unsigned maxAttrib)
{
   VertexInput v[3];
   if (ctx->vert.indices)
      indices = (IndexT *)((char *)ctx->vert.indices->data + (long)indices);
   for (unsigned i = 0; i < count; i += 3) {
      for (unsigned j = 0; j < 3; j++)
         FetchElement(ctx, indices[i + j], maxAttrib, v + j);
      ctx->iface->DrawTriangle(ctx->iface, v, v + 1, v + 2);
   }
}

static void DrawArraysTriangles(const GLES2Context * ctx, const unsigned first,
                                const unsigned count, const unsigned maxAttrib)
{
//   LOGD("agl: DrawArraysTriangles=%p", DrawArraysTriangles);
   VertexInput v[3];
   for (unsigned i = 2; i < count; i+=3) {
      // TODO: fix order
      FetchElement(ctx, first + i - 2, maxAttrib, v + 0);
      FetchElement(ctx, first + i - 1, maxAttrib, v + 1);
      FetchElement(ctx, first + i - 0, maxAttrib, v + 2);
      ctx->iface->DrawTriangle(ctx->iface, v + 0, v + 1, v + 2);
   }
//   LOGD("agl: DrawArraysTriangles end");
}

template<typename IndexT> static void DrawElementsTriangleStrip(const GLES2Context * ctx,
      const unsigned count, const IndexT * indices, const unsigned maxAttrib)
{
   VertexInput v[3];
   if (ctx->vert.indices)
      indices = (IndexT *)((char *)ctx->vert.indices->data + (long)indices);
      
//   LOGD("agl2: DrawElementsTriangleStrip");
//   for (unsigned i = 0; i < count; i++)
//      LOGD("indices[%d] = %d", i, indices[i]);

   FetchElement(ctx, indices[0], maxAttrib, v + 0);
   FetchElement(ctx, indices[1], maxAttrib, v + 1);
   for (unsigned i = 2; i < count; i ++) {
      FetchElement(ctx, indices[i], maxAttrib, v + i % 3);
      ctx->iface->DrawTriangle(ctx->iface, v + (i - 2) % 3, v + (i - 1) % 3 , v + (i + 0) % 3);
   }

//   for (unsigned i = 2; i < count; i++) {
//      FetchElement(ctx, indices[i - 2], maxAttrib, v + 0);
//      FetchElement(ctx, indices[i - 1], maxAttrib, v + 1);
//      FetchElement(ctx, indices[i - 0], maxAttrib, v + 2);
//      ctx->iface->DrawTriangle(ctx->iface, v + 0, v + 1, v + 2);
//   }
}

static void DrawArraysTriangleStrip(const GLES2Context * ctx, const unsigned first,
                                    const unsigned count, const unsigned maxAttrib)
{
   VertexInput v[3];
   FetchElement(ctx, first, maxAttrib, v + 0);
   FetchElement(ctx, first + 1, maxAttrib, v + 1);
   for (unsigned i = 2; i < count; i++) {
      // TODO: fix order
      FetchElement(ctx, first + i, maxAttrib, v + i % 3);
      ctx->iface->DrawTriangle(ctx->iface, v + (i - 2) % 3, v + (i - 1) % 3 , v + (i + 0) % 3);
   }
}

void glBindBuffer(GLenum target, GLuint buffer)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   VBO * vbo = NULL;
   if (0 != buffer) {
      std::map<GLuint, VBO *>::iterator it = ctx->vert.vbos.find(buffer);
      if (it != ctx->vert.vbos.end()) {
         vbo = it->second;
         if (!vbo)
            vbo = (VBO *)calloc(1, sizeof(VBO));
         it->second = vbo;
      } else
         assert(0);
   }
   if (GL_ARRAY_BUFFER == target)
      ctx->vert.vbo = vbo;
   else if (GL_ELEMENT_ARRAY_BUFFER == target)
      ctx->vert.indices = vbo;
   else
      assert(0);
   assert(vbo || buffer == 0);
//   LOGD("\n*\n glBindBuffer 0x%.4X=%d ", target, buffer);
}

void glBufferData(GLenum target, GLsizeiptr size, const GLvoid* data, GLenum usage)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   if (GL_ARRAY_BUFFER == target) {
      assert(ctx->vert.vbo);
      ctx->vert.vbo->data = realloc(ctx->vert.vbo->data, size);
      ctx->vert.vbo->size = size;
      ctx->vert.vbo->usage = usage;
      if (data)
         memcpy(ctx->vert.vbo->data, data, size);
   } else if (GL_ELEMENT_ARRAY_BUFFER == target) {
      assert(ctx->vert.indices);
      ctx->vert.indices->data = realloc(ctx->vert.indices->data, size);
      ctx->vert.indices->size = size;
      ctx->vert.indices->usage = usage;
      if (data)
         memcpy(ctx->vert.indices->data, data, size);
   } else
      assert(0);
//   LOGD("\n*\n glBufferData target=0x%.4X size=%u data=%p usage=0x%.4X \n",
//        target, size, data, usage);
}

void glBufferSubData(GLenum target, GLintptr offset, GLsizeiptr size, const GLvoid* data)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   if (GL_ARRAY_BUFFER == target)
   {
      assert(ctx->vert.vbo);
      assert(0 <= offset);
      assert(0 <= size);
      assert(offset + size <= ctx->vert.vbo->size);
      memcpy((char *)ctx->vert.vbo->data + offset, data, size);
   }
   else
      assert(0);
}

void glDeleteBuffers(GLsizei n, const GLuint* buffers)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   for (unsigned i = 0; i < n; i++) {
      std::map<GLuint, VBO*>::iterator it = ctx->vert.vbos.find(buffers[i]);
      if (it == ctx->vert.vbos.end())
         continue;
      ctx->vert.free = min(ctx->vert.free, buffers[i]);
      if (it->second == ctx->vert.vbo)
         ctx->vert.vbo = NULL;
      else if (it->second == ctx->vert.indices)
         ctx->vert.indices = NULL;
      if (it->second) {
         free(it->second->data);
         free(it->second);
      }
   }
}

void glDisableVertexAttribArray(GLuint index)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   assert(GGL_MAXVERTEXATTRIBS > index);
   ctx->vert.attribs[index].enabled = false;
}

void glDrawArrays(GLenum mode, GLint first, GLsizei count)
{
   GLES2_GET_CONST_CONTEXT(ctx);
//   LOGD("agl2: glDrawArrays=%p", glDrawArrays);
   assert(ctx->rasterizer.CurrentProgram);
   assert(0 <= first);
   int maxAttrib = -1;
   ctx->iface->ShaderProgramGetiv(ctx->rasterizer.CurrentProgram, GL_ACTIVE_ATTRIBUTES, &maxAttrib);
   assert(0 <= maxAttrib && GGL_MAXVERTEXATTRIBS >= maxAttrib);
   switch (mode) {
   case GL_TRIANGLE_STRIP:
      DrawArraysTriangleStrip(ctx, first, count, maxAttrib);
      break;
   case GL_TRIANGLES:
      DrawArraysTriangles(ctx, first, count, maxAttrib);
      break;
   default:
      LOGE("agl2: glDrawArrays unsupported mode: 0x%.4X \n", mode);
      assert(0);
      break;
   }
//   LOGD("agl2: glDrawArrays end");
}

void glDrawElements(GLenum mode, GLsizei count, GLenum type, const GLvoid* indices)
{
   GLES2_GET_CONST_CONTEXT(ctx);
//   LOGD("agl2: glDrawElements=%p mode=0x%.4X count=%d type=0x%.4X indices=%p",
//        glDrawElements, mode, count, type, indices);
   if (!ctx->rasterizer.CurrentProgram)
      return;

   int maxAttrib = -1;
   ctx->iface->ShaderProgramGetiv(ctx->rasterizer.CurrentProgram, GL_ACTIVE_ATTRIBUTES, &maxAttrib);
   assert(0 <= maxAttrib && GGL_MAXVERTEXATTRIBS >= maxAttrib);
//   LOGD("agl2: glDrawElements mode=0x%.4X type=0x%.4X count=%d program=%p indices=%p \n",
//        mode, type, count, ctx->rasterizer.CurrentProgram, indices);
   switch (mode) {
   case GL_TRIANGLES:
      if (GL_UNSIGNED_SHORT == type)
         DrawElementsTriangles<unsigned short>(ctx, count, (unsigned short *)indices, maxAttrib);
      else
         assert(0);
      break;
   case GL_TRIANGLE_STRIP:
      if (GL_UNSIGNED_SHORT == type)
         DrawElementsTriangleStrip<unsigned short>(ctx, count, (unsigned short *)indices, maxAttrib);
      else
         assert(0);
      break;
   default:
      assert(0);
   }
//   LOGD("agl2: glDrawElements end");
}

void glEnableVertexAttribArray(GLuint index)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   ctx->vert.attribs[index].enabled = true;
//   LOGD("agl2: glEnableVertexAttribArray %d \n", index);
}

void glGenBuffers(GLsizei n, GLuint* buffers)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   for (unsigned i = 0; i < n; i++) {
      buffers[i] = 0;
      for (ctx->vert.free; ctx->vert.free < 0xffffffffu; ctx->vert.free++) {
         if (ctx->vert.vbos.find(ctx->vert.free) == ctx->vert.vbos.end()) {
            ctx->vert.vbos[ctx->vert.free] = NULL;
            buffers[i] = ctx->vert.free;
//            LOGD("glGenBuffers %d \n", buffers[i]);
            ctx->vert.free++;
            break;
         }
      }
      assert(buffers[i]);
   }
}

void glVertexAttribPointer(GLuint index, GLint size, GLenum type, GLboolean normalized,
                           GLsizei stride, const GLvoid* ptr)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   assert(GL_FLOAT == type);
   assert(0 < size && 4 >= size);
   ctx->vert.attribs[index].size = size;
   ctx->vert.attribs[index].type = type;
   ctx->vert.attribs[index].normalized = normalized;
   if (0 == stride)
      ctx->vert.attribs[index].stride = size * sizeof(float);
   else if (stride > 0)
      ctx->vert.attribs[index].stride = stride;
   else
      assert(0);
//   LOGD("\n*\n*\n* agl2: glVertexAttribPointer program=%u index=%d size=%d stride=%d ptr=%p \n*\n*",
//        unsigned(ctx->rasterizer.CurrentProgram) ^ 0x04dc18f9, index, size, stride, ptr);
   if (ctx->vert.vbo)
      ctx->vert.attribs[index].ptr = (char *)ctx->vert.vbo->data + (long)ptr;
   else
      ctx->vert.attribs[index].ptr = ptr;
//   const float * attrib = (const float *)ctx->vert.attribs[index].ptr;
//   for (unsigned i = 0; i < 3; i++)
//      if (3 == size)
//         LOGD("%.2f %.2f %.2f", attrib[i * 3 + 0], attrib[i * 3 + 1], attrib[i * 3 + 2]);
//      else if (2 == size)
//         LOGD("%.2f %.2f", attrib[i * 3 + 0], attrib[i * 3 + 1]);
   
}

void glVertexAttrib1f(GLuint indx, GLfloat x)
{
   glVertexAttrib4f(indx, x,0,0,1);
}

void glVertexAttrib1fv(GLuint indx, const GLfloat* values)
{
   glVertexAttrib4f(indx, values[0],0,0,1);
}

void glVertexAttrib2f(GLuint indx, GLfloat x, GLfloat y)
{
   glVertexAttrib4f(indx, x,y,0,1);
}

void glVertexAttrib2fv(GLuint indx, const GLfloat* values)
{
   glVertexAttrib4f(indx, values[0],values[1],0,1);
}

void glVertexAttrib3f(GLuint indx, GLfloat x, GLfloat y, GLfloat z)
{
   glVertexAttrib4f(indx, x,y,z,1);
}

void glVertexAttrib3fv(GLuint indx, const GLfloat* values)
{
   glVertexAttrib4f(indx, values[0],values[1],values[2],1);
}

void glVertexAttrib4f(GLuint indx, GLfloat x, GLfloat y, GLfloat z, GLfloat w)
{
   assert(GGL_MAXVERTEXATTRIBS > indx);
   GLES2_GET_CONST_CONTEXT(ctx);
//   LOGD("\n*\n*\n agl2: glVertexAttrib4f %d %.2f,%.2f,%.2f,%.2f \n*\n*", indx, x, y, z, w);
   ctx->vert.defaultAttribs[indx] = Vector4(x,y,z,w);
   assert(0);
}

void glVertexAttrib4fv(GLuint indx, const GLfloat* values)
{
   glVertexAttrib4f(indx, values[0], values[1], values[2], values[3]);
}
