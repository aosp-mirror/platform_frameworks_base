#include "gles2context.h"

//#undef LOGD
//#define LOGD(...) 

#define API_ENTRY
#define CALL_GL_API(NAME,...) LOGD("?"#NAME); assert(0);
#define CALL_GL_API_RETURN(NAME,...) LOGD("?"#NAME); assert(0); return 0;

static inline GGLTexture * AllocTexture()
{
   GGLTexture * tex = (GGLTexture *)calloc(1, sizeof(GGLTexture));
   tex->minFilter = GGLTexture::GGL_LINEAR; // should be NEAREST_ MIPMAP_LINEAR
   tex->magFilter = GGLTexture::GGL_LINEAR;
   return tex;
}

void GLES2Context::InitializeTextures()
{
   tex.textures = std::map<GLuint, GGLTexture *>(); // the entire struct has been zeroed in constructor
   tex.tex2D = AllocTexture();
   tex.textures[GL_TEXTURE_2D] = tex.tex2D;
   tex.texCube = AllocTexture();
   tex.textures[GL_TEXTURE_CUBE_MAP] = tex.texCube;
   for (unsigned i = 0; i < GGL_MAXCOMBINEDTEXTUREIMAGEUNITS; i++) {
      tex.tmus[i] = NULL;
      tex.sampler2tmu[i] = NULL;
   }

   tex.active = 0;

   tex.free = max(GL_TEXTURE_2D, GL_TEXTURE_CUBE_MAP) + 1;

   tex.tex2D->format = GGL_PIXEL_FORMAT_RGBA_8888;
   tex.tex2D->type = GL_TEXTURE_2D;
   tex.tex2D->levelCount = 1;
   tex.tex2D->wrapS = tex.tex2D->wrapT = GGLTexture::GGL_REPEAT;
   tex.tex2D->minFilter = tex.tex2D->magFilter = GGLTexture::GGL_NEAREST;
   tex.tex2D->width = tex.tex2D->height = 1;
   tex.tex2D->levels = malloc(4);
   *(unsigned *)tex.tex2D->levels = 0xff000000;


   tex.texCube->format = GGL_PIXEL_FORMAT_RGBA_8888;
   tex.texCube->type = GL_TEXTURE_CUBE_MAP;
   tex.texCube->levelCount = 1;
   tex.texCube->wrapS = tex.texCube->wrapT = GGLTexture::GGL_REPEAT;
   tex.texCube->minFilter = tex.texCube->magFilter = GGLTexture::GGL_NEAREST;
   tex.texCube->width = tex.texCube->height = 1;
   tex.texCube->levels = malloc(4 * 6);
   static unsigned texels [6] = {0xff0000ff, 0xff00ff00, 0xffff0000,
                                 0xff00ffff, 0xffffff00, 0xffff00ff
                                };
   memcpy(tex.texCube->levels, texels, sizeof texels);

   //texture.levelCount = GenerateMipmaps(texture.levels, texture.width, texture.height);

   //    static unsigned texels [6] = {0xff0000ff, 0xff00ff00, 0xffff0000,
   //    0xff00ffff, 0xffffff00, 0xffff00ff};
   //    memcpy(texture.levels[0], texels, sizeof texels);
   //    texture.format = GGL_PIXEL_FORMAT_RGBA_8888;
   //    texture.width = texture.height = 1;
   //texture.height /= 6;
   //texture.type = GL_TEXTURE_CUBE_MAP;
   
   tex.unpack = 4;
}

void GLES2Context::TextureState::UpdateSampler(GGLInterface * iface, unsigned tmu)
{
   for (unsigned i = 0; i < GGL_MAXCOMBINEDTEXTUREIMAGEUNITS; i++)
      if (tmu == sampler2tmu[i])
         iface->SetSampler(iface, i, tmus[tmu]);
}

void GLES2Context::UninitializeTextures()
{
   for (std::map<GLuint, GGLTexture *>::iterator it = tex.textures.begin(); it != tex.textures.end(); it++) {
      if (!it->second)
         continue;
      free(it->second->levels);
      free(it->second);
   }
}

static inline void GetFormatAndBytesPerPixel(const GLenum format, unsigned * bytesPerPixel,
      GGLPixelFormat * texFormat)
{
   switch (format) {
   case GL_ALPHA:
      *texFormat = GGL_PIXEL_FORMAT_A_8;
      *bytesPerPixel = 1;
      break;
   case GL_LUMINANCE:
      *texFormat = GGL_PIXEL_FORMAT_L_8;
      *bytesPerPixel = 1;
      break;
   case GL_LUMINANCE_ALPHA:
      *texFormat = GGL_PIXEL_FORMAT_LA_88;
      *bytesPerPixel = 2;
      break;
   case GL_RGB:
      *texFormat = GGL_PIXEL_FORMAT_RGB_888;
      *bytesPerPixel = 3;
      break;
   case GL_RGBA:
      *texFormat = GGL_PIXEL_FORMAT_RGBA_8888;
      *bytesPerPixel = 4;
      break;

      // internal formats to avoid conversion
   case GL_UNSIGNED_SHORT_5_6_5:
      *texFormat = GGL_PIXEL_FORMAT_RGB_565;
      *bytesPerPixel = 2;
      break;

   default:
      assert(0);
      return;
   }
}

static inline void CopyTexture(char * dst, const char * src, const unsigned bytesPerPixel,
                               const unsigned sx, const unsigned sy,  const unsigned sw,
                               const unsigned dx, const unsigned dy, const unsigned dw,
                               const unsigned w, const unsigned h)
{
   const unsigned bpp = bytesPerPixel;
   if (dw == sw && dw == w && sx == 0 && dx == 0)
      memcpy(dst + dy * dw * bpp, src + sy * sw * bpp, w * h * bpp);
   else
      for (unsigned y = 0; y < h; y++)
         memcpy(dst + ((dy + y) * dw + dx) * bpp, src + ((sy + y) * sw + sx) * bpp, w * bpp); 
}

void glActiveTexture(GLenum texture)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   unsigned index = texture - GL_TEXTURE0;
   assert(NELEM(ctx->tex.tmus) > index);
//   LOGD("agl2: glActiveTexture %u", index);
   ctx->tex.active = index;
}

void glBindTexture(GLenum target, GLuint texture)
{
   GLES2_GET_CONST_CONTEXT(ctx);
//   LOGD("agl2: glBindTexture target=0x%.4X texture=%u active=%u", target, texture, ctx->tex.active);
   std::map<GLuint, GGLTexture *>::iterator it = ctx->tex.textures.find(texture);
   GGLTexture * tex = NULL;
   if (it != ctx->tex.textures.end()) {
      tex = it->second;
      if (!tex) {
         tex = AllocTexture();
         tex->type = target;
         it->second = tex;
//         LOGD("agl2: glBindTexture allocTexture");
      }
//      else
//         LOGD("agl2: glBindTexture bind existing texture");
      assert(target == tex->type);
   } else if (0 == texture) {
      if (GL_TEXTURE_2D == target)
      {
         tex = ctx->tex.tex2D;
//         LOGD("agl2: glBindTexture bind default tex2D");
      }
      else if (GL_TEXTURE_CUBE_MAP == target)
      {
         tex = ctx->tex.texCube;
//         LOGD("agl2: glBindTexture bind default texCube");
      }
      else
         assert(0);
   } else {
      if (texture <= ctx->tex.free)
         ctx->tex.free = texture + 1;
      tex = AllocTexture();
      tex->type = target;
      ctx->tex.textures[texture] = tex;
//      LOGD("agl2: glBindTexture new texture=%u", texture);
   }
   ctx->tex.tmus[ctx->tex.active] = tex;
//   LOGD("agl2: glBindTexture format=0x%.2X w=%u h=%u levels=%p", tex->format,
//      tex->width, tex->height, tex->levels);
   ctx->tex.UpdateSampler(ctx->iface, ctx->tex.active);
}

void API_ENTRY(glCompressedTexImage2D)(GLenum target, GLint level, GLenum internalformat, GLsizei width, GLsizei height, GLint border, GLsizei imageSize, const GLvoid* data)
{
   CALL_GL_API(glCompressedTexImage2D, target, level, internalformat, width, height, border, imageSize, data);
}

void API_ENTRY(glCompressedTexSubImage2D)(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLsizei imageSize, const GLvoid* data)
{
   CALL_GL_API(glCompressedTexSubImage2D, target, level, xoffset, yoffset, width, height, format, imageSize, data);
}

void glCopyTexImage2D(GLenum target, GLint level, GLenum internalformat,
                      GLint x, GLint y, GLsizei width, GLsizei height, GLint border)
{
   GLES2_GET_CONST_CONTEXT(ctx);
//   LOGD("agl2: glCopyTexImage2D target=0x%.4X internalformat=0x%.4X", target, internalformat);
//   LOGD("x=%d y=%d width=%d height=%d border=%d level=%d ", x, y, width, height, border, level);
   assert(0 == border);
   assert(0 == level);
   unsigned bytesPerPixel = 0;
   GGLPixelFormat texFormat = GGL_PIXEL_FORMAT_UNKNOWN;
   GetFormatAndBytesPerPixel(internalformat, &bytesPerPixel, &texFormat);

   assert(texFormat == ctx->rasterizer.frameSurface.format);
//   LOGD("texFormat=0x%.2X bytesPerPixel=%d \n", texFormat, bytesPerPixel);
   unsigned offset = 0, size = width * height * bytesPerPixel, totalSize = size;

   assert(ctx->tex.tmus[ctx->tex.active]);
   assert(y + height <= ctx->rasterizer.frameSurface.height);
   assert(x + width <= ctx->rasterizer.frameSurface.width);
   GGLTexture & tex = *ctx->tex.tmus[ctx->tex.active];
   tex.width = width;
   tex.height = height;
   tex.levelCount = 1;
   tex.format = texFormat;
   switch (target) {
   case GL_TEXTURE_2D:
      tex.levels = realloc(tex.levels, totalSize);
      CopyTexture((char *)tex.levels, (const char *)ctx->rasterizer.frameSurface.data, bytesPerPixel,
                  x, y, ctx->rasterizer.frameSurface.width, 0, 0, width, width, height);
      break;
   default:
      assert(0);
      return;
   }
   ctx->tex.UpdateSampler(ctx->iface, ctx->tex.active);
}

void glCopyTexSubImage2D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint x, GLint y, GLsizei width, GLsizei height)
{
   // x, y are src offset
   // xoffset and yoffset are dst offset
   GLES2_GET_CONST_CONTEXT(ctx);
//   LOGD("agl2: glCopyTexSubImage2D target=0x%.4X level=%d", target, level);
//   LOGD("xoffset=%d yoffset=%d x=%d y=%d width=%d height=%d", xoffset, yoffset, x, y, width, height);
   assert(0 == level);

   unsigned bytesPerPixel = 4;
   unsigned offset = 0, size = width * height * bytesPerPixel, totalSize = size;

   assert(ctx->tex.tmus[ctx->tex.active]);
   GGLTexture & tex = *ctx->tex.tmus[ctx->tex.active];

   assert(tex.format == ctx->rasterizer.frameSurface.format);
   assert(GGL_PIXEL_FORMAT_RGBA_8888 == tex.format);

   const unsigned srcWidth = ctx->rasterizer.frameSurface.width;
   const unsigned srcHeight = ctx->rasterizer.frameSurface.height;

   assert(x >= 0 && y >= 0);
   assert(xoffset >= 0 && yoffset >= 0);
   assert(x + width <= srcWidth);
   assert(y + height <= srcHeight);
   assert(xoffset + width <= tex.width);
   assert(yoffset + height <= tex.height);

   switch (target) {
   case GL_TEXTURE_2D:
      CopyTexture((char *)tex.levels, (const char *)ctx->rasterizer.frameSurface.data, bytesPerPixel,
                  x, y, srcWidth, xoffset, yoffset, tex.width, width, height);
      break;
   default:
      assert(0);
      return;
   }
   ctx->tex.UpdateSampler(ctx->iface, ctx->tex.active);
}

void glDeleteTextures(GLsizei n, const GLuint* textures)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   for (unsigned i = 0; i < n; i++) {
      std::map<GLuint, GGLTexture *>::iterator it = ctx->tex.textures.find(textures[i]);
      if (it == ctx->tex.textures.end())
         continue;
      ctx->tex.free = min(ctx->tex.free, textures[i]);
      for (unsigned i = 0; i <  GGL_MAXCOMBINEDTEXTUREIMAGEUNITS; i++)
         if (ctx->tex.tmus[i] == it->second) {
            if (GL_TEXTURE_2D == it->second->type)
               ctx->tex.tmus[i] = ctx->tex.tex2D;
            else if (GL_TEXTURE_CUBE_MAP == it->second->type)
               ctx->tex.tmus[i] = ctx->tex.texCube;
            else
               assert(0);
            ctx->tex.UpdateSampler(ctx->iface, i);
         }
      if (it->second) {
         free(it->second->levels);
         free(it->second);
      }
      ctx->tex.textures.erase(it);
   }
}

void glGenTextures(GLsizei n, GLuint* textures)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   for (unsigned i = 0; i < n; i++) {
      textures[i] = 0;
      for (ctx->tex.free; ctx->tex.free < 0xffffffffu; ctx->tex.free++)
         if (ctx->tex.textures.find(ctx->tex.free) == ctx->tex.textures.end()) {
            ctx->tex.textures[ctx->tex.free] = NULL;
            textures[i] = ctx->tex.free;
            ctx->tex.free++;
            break;
         }
      assert(textures[i]);
   }
}

void API_ENTRY(glGetTexParameterfv)(GLenum target, GLenum pname, GLfloat* params)
{
   CALL_GL_API(glGetTexParameterfv, target, pname, params);
}
void API_ENTRY(glGetTexParameteriv)(GLenum target, GLenum pname, GLint* params)
{
   CALL_GL_API(glGetTexParameteriv, target, pname, params);
}

GLboolean glIsTexture(GLuint texture)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   if (ctx->tex.textures.find(texture) == ctx->tex.textures.end())
      return GL_FALSE;
   else
      return GL_TRUE;
}

void glPixelStorei(GLenum pname, GLint param)
{
   GLES2_GET_CONST_CONTEXT(ctx);
   assert(GL_UNPACK_ALIGNMENT == pname);
   assert(1 == param || 2 == param || 4 == param || 8 == param);
//   LOGD("\n*\n* agl2: glPixelStorei not implemented pname=0x%.4X param=%d \n*", pname, param);
   ctx->tex.unpack = param;
//   CALL_GL_API(glPixelStorei, pname, param);
}
void glTexImage2D(GLenum target, GLint level, GLint internalformat, GLsizei width,
                  GLsizei height, GLint border, GLenum format, GLenum type, const GLvoid* pixels)
{
   GLES2_GET_CONST_CONTEXT(ctx);
//   LOGD("agl2: glTexImage2D internalformat=0x%.4X format=0x%.4X type=0x%.4X \n", internalformat, format, type);
//   LOGD("width=%d height=%d border=%d level=%d pixels=%p \n", width, height, border, level, pixels);
   switch (type) {
   case GL_UNSIGNED_BYTE:
      break;
   case GL_UNSIGNED_SHORT_5_6_5:
      internalformat = format = GL_UNSIGNED_SHORT_5_6_5;
      assert(4 == ctx->tex.unpack);
      break;
   default:
      assert(0);
   }
   assert(internalformat == format);
   assert(0 == border);
   if (0 != level) {
      LOGD("agl2: glTexImage2D level=%d", level);
      return;
   }
   unsigned bytesPerPixel = 0;
   GGLPixelFormat texFormat = GGL_PIXEL_FORMAT_UNKNOWN;
   GetFormatAndBytesPerPixel(format, &bytesPerPixel, &texFormat);

   assert(texFormat && bytesPerPixel);
//   LOGD("texFormat=0x%.2X bytesPerPixel=%d active=%u", texFormat, bytesPerPixel, ctx->tex.active);
   unsigned offset = 0, size = width * height * bytesPerPixel, totalSize = size;

   assert(ctx->tex.tmus[ctx->tex.active]);

   GGLTexture & tex = *ctx->tex.tmus[ctx->tex.active];
   tex.width = width;
   tex.height = height;
   tex.levelCount = 1;
   tex.format = texFormat;

   switch (target) {
   case GL_TEXTURE_2D:
      assert(GL_TEXTURE_2D == ctx->tex.tmus[ctx->tex.active]->type);
      offset = 0;
      break;
      break;
   case GL_TEXTURE_CUBE_MAP_POSITIVE_X:
   case GL_TEXTURE_CUBE_MAP_NEGATIVE_X:
   case GL_TEXTURE_CUBE_MAP_POSITIVE_Y:
   case GL_TEXTURE_CUBE_MAP_NEGATIVE_Y:
   case GL_TEXTURE_CUBE_MAP_POSITIVE_Z:
   case GL_TEXTURE_CUBE_MAP_NEGATIVE_Z:
      assert(GL_TEXTURE_CUBE_MAP == ctx->tex.tmus[ctx->tex.active]->type);
      assert(width == height);
      offset = (target - GL_TEXTURE_CUBE_MAP_POSITIVE_X) * size;
      totalSize = 6 * size;
      break;
   default:
      assert(0);
      return;
   }

   tex.levels = realloc(tex.levels, totalSize);
   if (pixels)
      CopyTexture((char *)tex.levels, (const char *)pixels, bytesPerPixel, 0, 0, width, 0, 0, width, width, height);
   ctx->tex.UpdateSampler(ctx->iface, ctx->tex.active);
}

void glTexParameterf(GLenum target, GLenum pname, GLfloat param)
{
//   LOGD("agl2: glTexParameterf target=0x%.4X pname=0x%.4X param=%f", target, pname, param);
   glTexParameteri(target, pname, param);
}
void API_ENTRY(glTexParameterfv)(GLenum target, GLenum pname, const GLfloat* params)
{
   CALL_GL_API(glTexParameterfv, target, pname, params);
}
void glTexParameteri(GLenum target, GLenum pname, GLint param)
{
   GLES2_GET_CONST_CONTEXT(ctx);
//   LOGD("alg2: glTexParameteri target=0x%.0X pname=0x%.4X param=0x%.4X",
//        target, pname, param);
   assert(ctx->tex.tmus[ctx->tex.active]);
   assert(target == ctx->tex.tmus[ctx->tex.active]->type);
   GGLTexture & tex = *ctx->tex.tmus[ctx->tex.active];
   switch (pname) {
   case GL_TEXTURE_WRAP_S:
   case GL_TEXTURE_WRAP_T:
      GGLTexture::GGLTextureWrap wrap;
      switch (param) {
      case GL_REPEAT:
         wrap = GGLTexture::GGL_REPEAT;
         break;
      case GL_CLAMP_TO_EDGE:
         wrap = GGLTexture::GGL_CLAMP_TO_EDGE;
         break;
      case GL_MIRRORED_REPEAT:
         wrap = GGLTexture::GGL_MIRRORED_REPEAT;
         break;
      default:
         assert(0);
         return;
      }
      if (GL_TEXTURE_WRAP_S == pname)
         tex.wrapS = wrap;
      else
         tex.wrapT = wrap;
      break;
   case GL_TEXTURE_MIN_FILTER:
      switch (param) {
      case GL_NEAREST:
         tex.minFilter = GGLTexture::GGL_NEAREST;
         break;
      case GL_LINEAR:
         tex.minFilter = GGLTexture::GGL_LINEAR;
         break;
      case GL_NEAREST_MIPMAP_NEAREST:
//         tex.minFilter = GGLTexture::GGL_NEAREST_MIPMAP_NEAREST;
         break;
      case GL_NEAREST_MIPMAP_LINEAR:
//         tex.minFilter = GGLTexture::GGL_NEAREST_MIPMAP_LINEAR;
         break;
      case GL_LINEAR_MIPMAP_NEAREST:
//         tex.minFilter = GGLTexture::GGL_LINEAR_MIPMAP_NEAREST;
         break;
      case GL_LINEAR_MIPMAP_LINEAR:
//         tex.minFilter = GGLTexture::GGL_LINEAR_MIPMAP_LINEAR;
         break;
      default:
         assert(0);
         return;
      }
      break;
   case GL_TEXTURE_MAG_FILTER:
      switch (param) {
      case GL_NEAREST:
         tex.minFilter = GGLTexture::GGL_NEAREST;
         break;
      case GL_LINEAR:
         tex.minFilter = GGLTexture::GGL_LINEAR;
         break;
      default:
         assert(0);
         return;
      }
      break;
   default:
      assert(0);
      return;
   }
   // implementation restriction
   if (tex.magFilter != tex.minFilter)
      tex.magFilter = tex.minFilter = GGLTexture::GGL_LINEAR;
   ctx->tex.UpdateSampler(ctx->iface, ctx->tex.active);
}
void API_ENTRY(glTexParameteriv)(GLenum target, GLenum pname, const GLint* params)
{
   CALL_GL_API(glTexParameteriv, target, pname, params);
}
void glTexSubImage2D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const GLvoid* pixels)
{
   GLES2_GET_CONST_CONTEXT(ctx);
//   LOGD("agl2: glTexSubImage2D target=0x%.4X level=%d xoffset=%d yoffset=%d width=%d height=%d format=0x%.4X type=0x%.4X pixels=%p",
//        target, level, xoffset, yoffset, width, height, format, type, pixels);
   assert(0 == level);
   assert(target == ctx->tex.tmus[ctx->tex.active]->type);
   switch (type) {
   case GL_UNSIGNED_BYTE:
      break;
   case GL_UNSIGNED_SHORT_5_6_5:
      format = GL_UNSIGNED_SHORT_5_6_5;
      assert(4 == ctx->tex.unpack);
      break;
   default:
      assert(0);
   }
   GGLTexture & tex = *ctx->tex.tmus[ctx->tex.active];
   GGLPixelFormat texFormat = GGL_PIXEL_FORMAT_UNKNOWN;
   unsigned bytesPerPixel = 0;
   GetFormatAndBytesPerPixel(format, &bytesPerPixel, &texFormat);
   assert(texFormat == tex.format);
   assert(GL_UNSIGNED_BYTE == type);
   switch (target) {
   case GL_TEXTURE_2D:
      CopyTexture((char *)tex.levels, (const char *)pixels, bytesPerPixel, 0, 0, width, xoffset,
                  yoffset, tex.width, width, height);
      break;
   default:
      assert(0);
   }
   ctx->tex.UpdateSampler(ctx->iface, ctx->tex.active);
}
